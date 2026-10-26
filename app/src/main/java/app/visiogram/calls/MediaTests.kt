package app.visiogram.calls

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.Camera2Enumerator
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

private const val SAMPLE_RATE = 48000

data class CameraInfo(val id: String, val label: String)

/** Cameras known to Camera2, which is also what the call engine captures from. */
fun listCameras(context: Context): List<CameraInfo> {
    val manager = context.getSystemService(CameraManager::class.java)
    return runCatching { Camera2Enumerator(context).deviceNames.toList() }.getOrDefault(emptyList()).map { id ->
        val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
        val kind = when (characteristics?.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "Caméra USB"
            CameraCharacteristics.LENS_FACING_FRONT -> "Caméra avant"
            CameraCharacteristics.LENS_FACING_BACK -> "Caméra arrière"
            else -> "Caméra $id"
        }
        val maxSize = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.maxByOrNull { it.width * it.height }
        CameraInfo(id, if (maxSize != null) "$kind (jusqu'à ${maxSize.width}×${maxSize.height})" else kind)
    }
}

enum class MicTestPhase { IDLE, RECORDING, PLAYING }

/**
 * "Record a few seconds, then play back" check on top of [MicSource], so the meter and the recording
 * include the microphone volume setting, exactly as sent in a call.
 */
class MicTest {
    val level: StateFlow<Float> = MicSource.level
    val device: StateFlow<String?> = MicSource.device

    private val _phase = MutableStateFlow(MicTestPhase.IDLE)
    val phase: StateFlow<MicTestPhase> = _phase.asStateFlow()

    private val recording = ShortArray(SAMPLE_RATE * RECORD_SECONDS)
    @Volatile private var recorded = 0

    fun start() = MicSource.start(this) { samples, _ -> onSamples(samples) }

    fun stop() = MicSource.stop(this)

    fun recordAndPlay() {
        if (_phase.value != MicTestPhase.IDLE) return
        recorded = 0
        _phase.value = MicTestPhase.RECORDING
    }

    private fun onSamples(samples: ShortArray) {
        if (_phase.value != MicTestPhase.RECORDING) return
        val count = min(samples.size, recording.size - recorded)
        samples.copyInto(recording, recorded, 0, count)
        recorded += count
        if (recorded >= recording.size) {
            _phase.value = MicTestPhase.PLAYING
            thread(name = "mic-test-playback") {
                playPcm(recording.copyOf(recorded))
                _phase.value = MicTestPhase.IDLE
            }
        }
    }

    companion object {
        const val RECORD_SECONDS = 5
    }
}

/** Plays a short two-note chime on the call audio path; returns the output device it went to. */
fun playTestTone(): String? {
    val notes = listOf(660.0, 880.0)
    val noteSamples = SAMPLE_RATE * 2 / 5
    val pcm = ShortArray(noteSamples * notes.size)
    notes.forEachIndexed { n, frequency ->
        for (i in 0 until noteSamples) {
            // Short fade in and out on each note to avoid clicks.
            val envelope = min(1.0, min(i, noteSamples - i) / (SAMPLE_RATE * 0.02))
            pcm[n * noteSamples + i] = (sin(2 * PI * frequency * i / SAMPLE_RATE) * envelope * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
    }
    return playPcm(pcm)
}

private fun playPcm(pcm: ShortArray): String? {
    if (pcm.isEmpty()) return null
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.size * 2)
        .build()
    return try {
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(150)
        val device = track.routedDevice?.label()
        Thread.sleep(pcm.size * 1000L / SAMPLE_RATE)
        device
    } finally {
        track.release()
    }
}

internal fun AudioDeviceInfo.label(): String {
    val kind = when (type) {
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "intégré"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "casque filaire"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "sous-mixage"
        else -> "type $type"
    }
    val name = productName?.toString().orEmpty()
    return if (name.isBlank()) kind else "$name ($kind)"
}
