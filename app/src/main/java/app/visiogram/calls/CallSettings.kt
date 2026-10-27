package app.visiogram.calls

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VideoQuality(val label: String, val width: Int, val height: Int) {
    SD("480p", 640, 480),
    HD("720p", 1280, 720),
    FHD("1080p", 1920, 1080),
}

/**
 * User choices applied to every call: camera, resolution, brightness, contrast, framing (zoom and position),
 * microphone volume and sound/picture synchronisation.
 */
object CallSettings {
    private const val KEY_CAMERA = "camera_id"
    private const val KEY_QUALITY = "video_quality"
    private const val KEY_EXPOSURE = "exposure_ev"
    private const val KEY_ZOOM = "zoom"
    private const val KEY_CONTRAST = "contrast"
    private const val KEY_MIC_GAIN = "mic_gain_db"
    private const val KEY_PAN_X = "pan_x"
    private const val KEY_PAN_Y = "pan_y"
    private const val KEY_AV_SYNC = "av_sync_ms"

    val exposureRange = -8f..8f
    val contrastRange = -8f..8f
    val zoomRange = 1f..3f
    val micGainRange = -12f..24f
    val panRange = -1f..1f
    val avSyncRange = -200..600

    private lateinit var prefs: SharedPreferences

    private val _cameraId = MutableStateFlow<String?>(null)
    /** Camera2 id of the chosen camera, or null for the first one. */
    val cameraId: StateFlow<String?> = _cameraId.asStateFlow()

    private val _quality = MutableStateFlow(VideoQuality.HD)
    val quality: StateFlow<VideoQuality> = _quality.asStateFlow()

    private val _exposure = MutableStateFlow(0f)
    /** Brightness correction, from −8 (darker) to +8 (brighter), applied by CameraSource. */
    val exposure: StateFlow<Float> = _exposure.asStateFlow()

    private val _contrast = MutableStateFlow(0f)
    /** Contrast correction, from −8 (flatter) to +8 (punchier), applied by CameraSource. */
    val contrast: StateFlow<Float> = _contrast.asStateFlow()

    private val _micGain = MutableStateFlow(0f)
    /** Microphone gain in dB, applied by MicSource before the audio is sent. */
    val micGain: StateFlow<Float> = _micGain.asStateFlow()

    private val _panX = MutableStateFlow(0f)
    /**
     * Horizontal position of the zoomed framing, −1 (left edge) to +1 (right edge), as seen in the
     * mirrored self-view; CameraSource converts it to sensor coordinates.
     */
    val panX: StateFlow<Float> = _panX.asStateFlow()

    private val _panY = MutableStateFlow(0f)
    /** Vertical position of the zoomed framing, −1 (top edge) to +1 (bottom edge). */
    val panY: StateFlow<Float> = _panY.asStateFlow()

    private val _zoom = MutableStateFlow(1f)
    /** Centered digital zoom, applied by the camera through its crop region. */
    val zoom: StateFlow<Float> = _zoom.asStateFlow()

    private val _avSyncMs = MutableStateFlow(0)
    /**
     * Extra delay given to the remote picture, in milliseconds, on top of the measured audio latency:
     * positive when the sound still comes after the picture (a Bluetooth headset on the other side, say).
     */
    val avSyncMs: StateFlow<Int> = _avSyncMs.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("call_settings", Context.MODE_PRIVATE)
        _cameraId.value = prefs.getString(KEY_CAMERA, null)
        _quality.value = prefs.getString(KEY_QUALITY, null)
            ?.let { name -> VideoQuality.entries.firstOrNull { it.name == name } }
            ?: VideoQuality.HD
        _exposure.value = prefs.getFloat(KEY_EXPOSURE, 0f).coerceIn(exposureRange)
        _zoom.value = prefs.getFloat(KEY_ZOOM, 1f).coerceIn(zoomRange)
        _contrast.value = prefs.getFloat(KEY_CONTRAST, 0f).coerceIn(contrastRange)
        _micGain.value = prefs.getFloat(KEY_MIC_GAIN, 0f).coerceIn(micGainRange)
        _panX.value = prefs.getFloat(KEY_PAN_X, 0f).coerceIn(panRange)
        _panY.value = prefs.getFloat(KEY_PAN_Y, 0f).coerceIn(panRange)
        _avSyncMs.value = prefs.getInt(KEY_AV_SYNC, 0).coerceIn(avSyncRange)
    }

    fun setCameraId(id: String) {
        _cameraId.value = id
        prefs.edit().putString(KEY_CAMERA, id).apply()
    }

    fun setQuality(quality: VideoQuality) {
        _quality.value = quality
        prefs.edit().putString(KEY_QUALITY, quality.name).apply()
    }

    fun setExposure(ev: Float) {
        _exposure.value = ev.coerceIn(exposureRange)
        prefs.edit().putFloat(KEY_EXPOSURE, _exposure.value).apply()
    }

    fun setZoom(zoom: Float) {
        _zoom.value = zoom.coerceIn(zoomRange)
        prefs.edit().putFloat(KEY_ZOOM, _zoom.value).apply()
    }

    fun setContrast(contrast: Float) {
        _contrast.value = contrast.coerceIn(contrastRange)
        prefs.edit().putFloat(KEY_CONTRAST, _contrast.value).apply()
    }

    fun setMicGain(db: Float) {
        _micGain.value = db.coerceIn(micGainRange)
        prefs.edit().putFloat(KEY_MIC_GAIN, _micGain.value).apply()
    }

    fun setPan(x: Float, y: Float) {
        _panX.value = x.coerceIn(panRange)
        _panY.value = y.coerceIn(panRange)
        prefs.edit().putFloat(KEY_PAN_X, _panX.value).putFloat(KEY_PAN_Y, _panY.value).apply()
    }

    fun setAvSync(ms: Int) {
        _avSyncMs.value = ms.coerceIn(avSyncRange)
        prefs.edit().putInt(KEY_AV_SYNC, _avSyncMs.value).apply()
    }
}
