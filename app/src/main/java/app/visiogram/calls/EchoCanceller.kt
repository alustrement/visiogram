package app.visiogram.calls

import android.util.Log
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Acoustic echo cancellation (WebRTC AEC3, in libvisio_apm.so), with a high-pass filter and moderate noise
 * suppression. Without it, whatever the TV plays goes back into the microphone and the other side hears
 * themselves: ntgcalls' WebRTC has no audio processing of its own.
 *
 * [SpeakerSink] hands over every block it plays ([render]), [MicSource] every block it records ([capture]),
 * each from its own thread. Active between [start] and [stop]; outside of that, both calls do nothing.
 */
object EchoCanceller {
    private const val TAG = "EchoCanceller"

    /** Longest echo delay searched for: HDMI output, a sound system and the room. */
    private const val MAX_DELAY_MS = 960

    private val available = runCatching { System.loadLibrary("visio_apm") }
        .onFailure { Log.e(TAG, "Echo canceller unavailable", it) }
        .isSuccess

    private val lock = ReentrantReadWriteLock()
    private var handle = 0L
    private var captured = 0

    data class Stats(val erleDb: Double, val erlDb: Double, val delayMs: Double)

    /** Mean power of the microphone before and after processing, for the echo check. */
    data class Levels(val before: Double, val after: Double)

    // Power sums since resetLevels(), updated by the capture thread. Guarded by itself.
    private val levelSums = DoubleArray(2)
    private var levelSamples = 0L

    fun start() {
        lock.write {
            if (!available || handle != 0L) return
            handle = nativeCreate(SpeakerSink.SAMPLE_RATE, MAX_DELAY_MS)
            captured = 0
        }
    }

    fun stop() {
        lock.write {
            if (handle == 0L) return
            Log.i(TAG, "Last statistics: ${statsLocked()}")
            nativeDestroy(handle)
            handle = 0L
        }
    }

    /** One 10 ms block of what the speakers play, before it is played. */
    fun render(samples: ShortArray) {
        lock.read { if (handle != 0L) nativeRender(handle, samples) }
    }

    /** One 10 ms block of the microphone, cleaned in place. */
    fun capture(samples: ShortArray) {
        lock.read {
            if (handle == 0L) return
            val before = power(samples)
            nativeCapture(handle, samples, SpeakerSink.latencyMs)
            val after = power(samples)
            synchronized(levelSums) {
                levelSums[0] += before
                levelSums[1] += after
                levelSamples += samples.size
            }
            // Every 10 s, for the logs: how much echo is removed and at what delay it was found.
            if (++captured % 1000 == 0) Log.i(TAG, "Statistics: ${statsLocked()}")
        }
    }

    fun resetLevels() = synchronized(levelSums) {
        levelSums.fill(0.0)
        levelSamples = 0
    }

    /** Mean power before and after processing since [resetLevels]. */
    fun levels(): Levels = synchronized(levelSums) {
        if (levelSamples == 0L) Levels(0.0, 0.0) else Levels(levelSums[0] / levelSamples, levelSums[1] / levelSamples)
    }

    private fun power(samples: ShortArray): Double {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sum
    }

    /** ERLE (echo removed), ERL (echo already lost on the way) and estimated delay; NaN until known. */
    fun stats(): Stats? = lock.read { if (handle == 0L) null else statsLocked() }

    private fun statsLocked(): Stats = nativeStats(handle).let { Stats(it[0], it[1], it[2]) }

    @JvmStatic private external fun nativeCreate(sampleRate: Int, maxDelayMs: Int): Long
    @JvmStatic private external fun nativeDestroy(handle: Long)
    @JvmStatic private external fun nativeRender(handle: Long, samples: ShortArray)
    @JvmStatic private external fun nativeCapture(handle: Long, samples: ShortArray, delayMs: Int)
    @JvmStatic private external fun nativeStats(handle: Long): DoubleArray
}
