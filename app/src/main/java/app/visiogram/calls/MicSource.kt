package app.visiogram.calls

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * The microphone, captured by the app itself so that its volume can be set: the call engine's own capture
 * has no gain control. Same voice-communication source as the call engine, mono 48 kHz, delivered in the
 * 10 ms blocks that ntgcalls expects from an external audio source.
 *
 * One owner at a time (a call or the settings test); [stop] only stops the capture of the owner that started it.
 */
object MicSource {
    private const val TAG = "MicSource"
    const val SAMPLE_RATE = 48000
    const val CHUNK_SAMPLES = SAMPLE_RATE / 100

    /** Above this level the gain is compressed instead of clipping. */
    private const val KNEE = 24576.0

    private val _level = MutableStateFlow(0f)
    /** Peak level after gain, from 0 (−60 dBFS or less) to 1 (0 dBFS). */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _device = MutableStateFlow<String?>(null)
    val device: StateFlow<String?> = _device.asStateFlow()

    private val lock = Any()
    private var owner: Any? = null
    /** Receives each 10 ms block, as samples and as little-endian PCM bytes. */
    @Volatile private var consumer: ((ShortArray, ByteArray) -> Unit)? = null
    /** Identity of the running capture thread; a thread stops as soon as it is no longer the current one. */
    @Volatile private var session: Any? = null
    private var worker: Thread? = null

    fun start(owner: Any, consumer: (samples: ShortArray, pcm: ByteArray) -> Unit) {
        synchronized(lock) {
            this.owner = owner
            this.consumer = consumer
            if (session != null) return
            val token = Any()
            session = token
            worker = thread(name = "mic-source") { capture(token) }
        }
    }

    fun stop(owner: Any) {
        val finished = synchronized(lock) {
            if (this.owner !== owner) return
            this.owner = null
            consumer = null
            session = null
            worker.also { worker = null }
        }
        finished?.join(1000)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested at startup; a refusal ends up below.
    private fun capture(token: Any) {
        val bufferSize = max(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), SAMPLE_RATE / 5)
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        }.onFailure { Log.w(TAG, "AudioRecord failed", it) }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            _device.value = "Micro indisponible (autorisation refusée ou micro absent)"
            record?.release()
            return
        }
        record.startRecording()
        _device.value = record.routedDevice?.label() ?: "Micro par défaut"

        val samples = ShortArray(CHUNK_SAMPLES)
        val pcm = ByteArray(CHUNK_SAMPLES * 2)
        while (session === token) {
            var filled = 0
            while (filled < CHUNK_SAMPLES && session === token) {
                val read = record.read(samples, filled, CHUNK_SAMPLES - filled)
                if (read <= 0) break
                filled += read
            }
            if (filled < CHUNK_SAMPLES) continue

            val peak = applyGain(samples, 10.0.pow(CallSettings.micGain.value / 20.0))
            _level.value = ((20 * log10(max(peak, 1) / 32768.0) + 60) / 60).toFloat().coerceIn(0f, 1f)
            for (i in 0 until CHUNK_SAMPLES) {
                val s = samples[i].toInt()
                pcm[2 * i] = s.toByte()
                pcm[2 * i + 1] = (s shr 8).toByte()
            }
            consumer?.invoke(samples, pcm)
        }
        record.stop()
        record.release()
        _level.value = 0f
    }

    /** Applies [gain] in place, linear below the knee and compressed above it; returns the peak. */
    private fun applyGain(samples: ShortArray, gain: Double): Int {
        var peak = 0
        for (i in samples.indices) {
            var y = samples[i] * gain
            val magnitude = abs(y)
            if (magnitude > KNEE) {
                val room = Short.MAX_VALUE - KNEE
                y = (KNEE + room * tanh((magnitude - KNEE) / room)) * (if (y < 0) -1 else 1)
            }
            val out = y.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            samples[i] = out.toShort()
            peak = max(peak, abs(out))
        }
        return peak
    }
}
