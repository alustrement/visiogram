package app.visiogram.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.VideoSink
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The webcam, captured by the app itself so that brightness and framing can be set: the call engine's own
 * capture exposes neither. Zoom is applied by the camera (crop region). Brightness and contrast are a tone
 * curve applied here, because UVC webcams such as the Shield's advertise exposure compensation but ignore it.
 *
 * Frames are packed I420. They go to the running call through ntgcalls' external camera source (the
 * [start] consumer) and to the local previews ([previewSink]). One owner at a time: a call or the
 * settings preview; [stop] only stops the capture of the owner that started it.
 *
 * The camera is reopened automatically after a fatal error: the Shield's driver shuts the camera down
 * when a single MJPEG frame from the USB webcam arrives corrupted.
 */
@SuppressLint("StaticFieldLeak") // Holds the application context only.
object CameraSource {
    private const val TAG = "CameraSource"
    private const val MAX_REOPEN_ATTEMPTS = 6

    private lateinit var context: Context
    private val manager get() = context.getSystemService(CameraManager::class.java)
    private val handler = Handler(HandlerThread("camera-source").apply { start() }.looper)

    @Volatile var previewSink: VideoSink? = null

    private val _stats = MutableStateFlow<String?>(null)
    /** Measured size, frame rate and average brightness of the captured video. */
    val stats: StateFlow<String?> = _stats.asStateFlow()

    // Everything below is confined to the camera thread.
    private var owner: Any? = null
    private var consumer: ((ByteArray, Int, Int) -> Unit)? = null
    private var cameraId: String? = null
    private var quality: VideoQuality? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var request: CaptureRequest.Builder? = null
    private var packed = ByteArray(0)
    private var row = ByteArray(0)
    private var toneLut: ByteArray? = null
    private var frames = 0
    private var windowStart = 0L
    /** Consecutive reopen attempts without a frame in between. */
    private var failures = 0
    private val reopen = Runnable {
        if (owner != null && device == null) open(selectedCameraId(), CallSettings.quality.value)
    }

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun hasCamera(): Boolean = runCatching { manager.cameraIdList.isNotEmpty() }.getOrDefault(false)

    /** Starts (or takes over) the capture for [owner]; [consumer] receives each packed I420 frame and its size. */
    fun start(owner: Any, consumer: ((ByteArray, Int, Int) -> Unit)? = null) {
        handler.post {
            this.owner = owner
            this.consumer = consumer
            val id = selectedCameraId()
            val quality = CallSettings.quality.value
            if (device != null && id == cameraId && quality == this.quality) return@post
            close()
            open(id, quality)
        }
    }

    fun stop(owner: Any) {
        handler.post {
            if (this.owner !== owner) return@post
            this.owner = null
            consumer = null
            handler.removeCallbacks(reopen)
            failures = 0
            close()
            _stats.value = null
        }
    }

    /** Reopens the camera after the chosen camera or quality changed. */
    fun reconfigure() {
        handler.post {
            if (owner == null) return@post
            close()
            open(selectedCameraId(), CallSettings.quality.value)
        }
    }

    /** Applies the current brightness, contrast, zoom and pan settings to the running capture. */
    fun applyControls() {
        handler.post {
            toneLut = toneLut()
            val builder = request ?: return@post
            val id = cameraId ?: return@post
            setControls(builder, id)
            runCatching { session?.setRepeatingRequest(builder.build(), null, handler) }
                .onFailure { Log.w(TAG, "setRepeatingRequest failed", it) }
        }
    }

    private fun selectedCameraId(): String? {
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        return CallSettings.cameraId.value?.takeIf { it in ids } ?: ids.firstOrNull()
    }

    @SuppressLint("MissingPermission") // Checked just below.
    private fun open(id: String?, quality: VideoQuality) {
        if (id == null) {
            _stats.value = "Aucune caméra détectée"
            return
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _stats.value = "Accès à la caméra refusé"
            return
        }
        val size = outputSize(id, quality)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use(::onImage) }, handler)
        this.reader = reader
        cameraId = id
        this.quality = quality
        toneLut = toneLut()
        frames = 0
        windowStart = 0L

        handler.removeCallbacks(reopen)
        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    // A close() or reopen happened while the camera was opening.
                    if (this@CameraSource.reader !== reader) {
                        camera.close()
                        return
                    }
                    device = camera
                    createSession(camera, reader, id)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera $id disconnected")
                    camera.close()
                    if (device === camera) recover()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "Camera $id error $error")
                    camera.close()
                    if (device === camera || this@CameraSource.reader === reader) recover()
                }
            }, handler)
        }.onFailure {
            Log.w(TAG, "openCamera failed", it)
            recover()
        }
    }

    @Suppress("DEPRECATION") // The SessionConfiguration variant needs API 28; minSdk is 26.
    private fun createSession(camera: CameraDevice, reader: ImageReader, id: String) {
        camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (this@CameraSource.reader !== reader) {
                    session.close()
                    return
                }
                this@CameraSource.session = session
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(reader.surface)
                    targetFpsRange(id)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                }
                setControls(builder, id)
                request = builder
                runCatching { session.setRepeatingRequest(builder.build(), null, handler) }
                    .onFailure { Log.w(TAG, "setRepeatingRequest failed", it) }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.w(TAG, "Capture session configuration failed")
                if (this@CameraSource.reader === reader) recover()
            }
        }, handler)
    }

    private fun setControls(builder: CaptureRequest.Builder, id: String) {
        val characteristics = manager.getCameraCharacteristics(id)
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoom = CallSettings.zoom.value.coerceIn(1f, maxZoom)
        val output = reader?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
        var width = active.width() / zoom
        var height = active.height() / zoom
        // Keep the output's aspect ratio so the camera does not stretch the cropped area.
        if (width / height > output) width = height * output else height = width / output
        // Pan within the room left by the zoom. The self-view is mirrored, so "right" in it is the sensor's left.
        val left = active.left + ((active.width() - width) / 2 * (1 - CallSettings.panX.value)).toInt()
        val top = active.top + ((active.height() - height) / 2 * (1 + CallSettings.panY.value)).toInt()
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + width.toInt(), top + height.toInt()))
    }

    /** The quality's size if the camera offers it, otherwise the largest smaller one. */
    private fun outputSize(id: String, quality: VideoQuality): Size {
        val sizes = manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            .orEmpty()
        return sizes.firstOrNull { it.width == quality.width && it.height == quality.height }
            ?: sizes.filter { it.width <= quality.width && it.height <= quality.height }.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(quality.width, quality.height)
    }

    /** Up to 30 frames/s, letting the camera go down to 15 in low light rather than lower. */
    private fun targetFpsRange(id: String): Range<Int>? {
        val ranges = manager.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        return ranges.filter { it.upper == 30 }.minByOrNull { kotlin.math.abs(it.lower - 15) }
    }

    /** Closes the failed camera and reopens it after a delay that grows with consecutive failures. */
    private fun recover() {
        close()
        if (owner == null) return
        if (failures >= MAX_REOPEN_ATTEMPTS) {
            _stats.value = "Caméra indisponible, débranchez puis rebranchez la webcam"
            return
        }
        val delay = 500L shl failures
        failures++
        _stats.value = "Redémarrage de la caméra…"
        handler.removeCallbacks(reopen)
        handler.postDelayed(reopen, delay)
    }

    private fun onImage(image: Image) {
        failures = 0
        val width = image.width
        val height = image.height
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val size = width * height + 2 * chromaWidth * chromaHeight
        if (packed.size != size) packed = ByteArray(size)

        val planes = image.planes
        copyPlane(planes[0], width, height, 0)
        toneLut?.let { lut ->
            for (i in 0 until width * height) packed[i] = lut[packed[i].toInt() and 0xFF]
        }
        copyPlane(planes[1], chromaWidth, chromaHeight, width * height)
        copyPlane(planes[2], chromaWidth, chromaHeight, width * height + chromaWidth * chromaHeight)

        consumer?.invoke(packed, width, height)
        previewSink?.let { I420FrameRenderer.render(packed, width, height, 0, it) }
        measure(width, height)
    }

    /** Copies one YUV_420_888 plane into [packed], dropping row padding and pixel stride. */
    private fun copyPlane(plane: Image.Plane, width: Int, height: Int, offset: Int) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            if (pixelStride == 1) {
                buffer.get(packed, offset + y * width, width)
            } else {
                val length = minOf(buffer.remaining(), (width - 1) * pixelStride + 1)
                if (row.size < length) row = ByteArray(maxOf(rowStride, length))
                buffer.get(row, 0, length)
                val out = offset + y * width
                for (x in 0 until width) packed[out + x] = row[x * pixelStride]
            }
        }
    }

    private fun measure(width: Int, height: Int) {
        val now = SystemClock.elapsedRealtime()
        if (windowStart == 0L) windowStart = now
        frames++
        if (now - windowStart < 1000) return
        var sum = 0L
        var count = 0
        var i = 0
        val luma = width * height
        while (i < luma) {
            sum += packed[i].toInt() and 0xFF
            count++
            i += 211
        }
        val brightness = (sum * 100 / (count.coerceAtLeast(1) * 255L)).toInt()
        _stats.value = "${width}×$height · ${frames * 1000 / (now - windowStart)} images/s · luminosité $brightness %"
        frames = 0
        windowStart = now
    }

    /**
     * Tone curve on luma, null when neutral.
     * Brightness −8…+8 is a gamma of 16…1/16: mid-tones move while black and white stay in place.
     * Contrast −8…+8 scales the distance to mid-grey by 0.25…4.
     */
    private fun toneLut(): ByteArray? {
        val brightness = CallSettings.exposure.value
        val contrast = CallSettings.contrast.value
        if (brightness == 0f && contrast == 0f) return null
        val gamma = 2.0.pow(-brightness / 2.0)
        val factor = 2.0.pow(contrast / 4.0)
        return ByteArray(256) { i ->
            val v = ((i / 255.0).pow(gamma) - 0.5) * factor + 0.5
            (255 * v).roundToInt().coerceIn(0, 255).toByte()
        }
    }

    private fun close() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        request = null
        cameraId = null
        quality = null
    }
}
