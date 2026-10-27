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
import java.util.Random
import kotlin.math.PI
import kotlin.math.log10
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

enum class MicTestPhase { IDLE, RECORDING, PLAYING, ECHO }

/**
 * Outcome of the echo check: how far above the room noise the TV's sound reached the microphone, before and
 * after processing, and the echo delay found by the canceller.
 */
data class EchoResult(val beforeDb: Double, val afterDb: Double, val delayMs: Double?)

/**
 * "Record a few seconds, then play back" check on top of [MicSource], so the meter and the recording
 * include the microphone volume setting, exactly as sent in a call.
 *
 * Also the echo check: speech-like sound played through [SpeakerSink] as in a call, while the microphone goes
 * through the [EchoCanceller]; the result says how much of the TV's sound would still reach the other side.
 */
class MicTest {
    val level: StateFlow<Float> = MicSource.level
    val device: StateFlow<String?> = MicSource.device

    private val _phase = MutableStateFlow(MicTestPhase.IDLE)
    val phase: StateFlow<MicTestPhase> = _phase.asStateFlow()

    private val recording = ShortArray(SAMPLE_RATE * RECORD_SECONDS)
    @Volatile private var recorded = 0

    private val _echoResult = MutableStateFlow<Result<EchoResult>?>(null)
    /** Last echo check, null while one runs. */
    val echoResult: StateFlow<Result<EchoResult>?> = _echoResult.asStateFlow()

    fun start() = MicSource.start(this) { samples, _ -> onSamples(samples) }

    fun stop() = MicSource.stop(this)

    fun recordAndPlay() {
        if (_phase.value != MicTestPhase.IDLE) return
        recorded = 0
        _phase.value = MicTestPhase.RECORDING
    }

    fun testEcho() {
        if (_phase.value != MicTestPhase.IDLE) return
        _phase.value = MicTestPhase.ECHO
        _echoResult.value = null
        thread(name = "echo-test") {
            _echoResult.value = runCatching { runEchoTest() }
            _phase.value = MicTestPhase.IDLE
        }
    }

    /**
     * One second of room noise, then [ECHO_TEST_SECONDS] of sound. Over the last seconds, once the canceller
     * has converged, the microphone is compared with the room noise before and after processing: how loud the
     * TV comes back into the microphone, and how much of it is left.
     */
    private fun runEchoTest(): EchoResult {
        SpeakerSink.start(this)
        try {
            checkNotNull(EchoCanceller.stats()) { "l'annulation d'écho n'a pas pu démarrer" }
            Thread.sleep(300)
            EchoCanceller.resetLevels()
            Thread.sleep(1000)
            val room = EchoCanceller.levels()

            val blocks = ECHO_TEST_SECONDS * 100
            val measureFrom = blocks - 300
            val sound = SpeechLikeNoise()
            val started = System.nanoTime()
            for (block in 0 until blocks) {
                if (block == measureFrom) {
                    EchoCanceller.resetLevels()
                    // Starts the canceller's own one-second statistics windows, read at the end.
                    EchoCanceller.stats()
                }
                SpeakerSink.pushSamples(sound.next(block))
                // Paced in real time: SpeakerSink keeps only a few blocks ahead.
                val due = started + (block + 1) * 10_000_000L
                val wait = (due - System.nanoTime()) / 1_000_000
                if (wait > 0) Thread.sleep(wait)
            }
            val echo = EchoCanceller.levels()
            val delay = EchoCanceller.stats()?.delayMs?.takeUnless { it.isNaN() }
            return EchoResult(db(echo.before, room.before), db(echo.after, room.after), delay)
        } finally {
            SpeakerSink.stop(this)
        }
    }

    private fun db(power: Double, reference: Double) = 10 * log10(power.coerceAtLeast(1.0) / reference.coerceAtLeast(1.0))

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
        // Long enough for the canceller to converge before the last 3 seconds, which are measured.
        const val ECHO_TEST_SECONDS = 12
    }
}

/**
 * Noise shaped like speech for the echo check: weighted towards low frequencies, in syllable-like bursts
 * (about 4 per second) with a short pause every 5.
 */
private class SpeechLikeNoise {
    private val random = Random(1)
    private var lowPass = 0.0

    fun next(block: Int): ShortArray {
        val voiced = (block / 25) % 5 != 4
        return ShortArray(SpeakerSink.CHUNK_SAMPLES) { i ->
            val t = (block * SpeakerSink.CHUNK_SAMPLES + i).toDouble() / SAMPLE_RATE
            lowPass += 0.15 * (random.nextGaussian() - lowPass)
            val envelope = if (voiced) 0.5 + 0.5 * sin(2 * PI * 4 * t) else 0.0
            (lowPass * envelope * Short.MAX_VALUE).toInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
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
    val kind: String? = when (type) {
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "intégré"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "casque filaire"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "sous-mixage"
        // The Shield's internal audio bus, which leads to HDMI: its product name says enough.
        AudioDeviceInfo.TYPE_BUS -> null
        else -> "type $type"
    }
    val name = productName?.toString().orEmpty()
    return when {
        name.isBlank() -> kind ?: "sortie audio"
        kind == null -> name
        else -> "$name ($kind)"
    }
}
