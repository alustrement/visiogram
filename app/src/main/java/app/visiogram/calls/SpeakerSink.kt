package app.visiogram.calls

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import io.github.pytgcalls.models.Frame
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * The other side's voice, played by the app itself rather than by ntgcalls: ntgcalls queues playback
 * without any bound and reports no output latency, so the sound could drift behind the picture. Here the
 * queue is capped, the real output latency is measured for [VideoDelay], and every block actually played
 * goes to the echo canceller as its reference.
 *
 * Mono 48 kHz, in the 10 ms blocks that ntgcalls delivers for an external playback device.
 * One owner at a time (a private call or a group call); [stop] only stops the playback of its owner.
 */
object SpeakerSink {
    private const val TAG = "SpeakerSink"
    const val SAMPLE_RATE = 48000
    const val CHUNK_SAMPLES = SAMPLE_RATE / 100

    /** Beyond this backlog the oldest blocks are dropped, so that the delay never builds up. */
    private const val MAX_QUEUED_CHUNKS = 6

    private val queue = ArrayBlockingQueue<ShortArray>(MAX_QUEUED_CHUNKS)

    private val lock = Any()
    private var owner: Any? = null
    /** Identity of the running playback thread; a thread stops as soon as it is no longer the current one. */
    @Volatile private var session: Any? = null
    private var worker: Thread? = null

    /** Time between a block being handed over and it leaving the TV, in milliseconds; 0 while nothing plays. */
    @Volatile var latencyMs: Int = 0
        private set

    fun start(owner: Any) {
        synchronized(lock) {
            this.owner = owner
            if (session != null) return
            queue.clear()
            EchoCanceller.start()
            val token = Any()
            session = token
            worker = thread(name = "speaker-sink") { play(token) }
        }
    }

    fun stop(owner: Any) {
        val finished = synchronized(lock) {
            if (this.owner !== owner) return
            this.owner = null
            session = null
            worker.also { worker = null }
        }
        finished?.join(1000)
        EchoCanceller.stop()
        queue.clear()
        latencyMs = 0
    }

    /** Queues one 10 ms block, for local tests. */
    fun pushSamples(chunk: ShortArray) {
        if (session == null) return
        while (!queue.offer(chunk)) queue.poll()
    }

    /** Mixes one 10 ms block per remote participant (little-endian PCM) and queues the result. */
    fun push(frames: List<Frame>) {
        if (session == null || frames.isEmpty()) return
        val mixed = IntArray(CHUNK_SAMPLES)
        for (frame in frames) {
            val data = frame.data
            val samples = minOf(CHUNK_SAMPLES, data.size / 2)
            for (i in 0 until samples) {
                mixed[i] += (data[2 * i].toInt() and 0xFF) or (data[2 * i + 1].toInt() shl 8)
            }
        }
        val chunk = ShortArray(CHUNK_SAMPLES) { mixed[it].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
        while (!queue.offer(chunk)) queue.poll()
    }

    private fun play(token: Any) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = runCatching {
            AudioTrack.Builder()
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
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(max(minBuffer, CHUNK_SAMPLES * 2 * 4))
                .build()
        }.onFailure { Log.e(TAG, "AudioTrack failed", it) }.getOrNull() ?: return

        val silence = ShortArray(CHUNK_SAMPLES)
        val timestamp = AudioTimestamp()
        var written = 0L
        var blocks = 0
        track.play()
        Log.i(TAG, "Playing on ${track.routedDevice?.label()}, buffer ${track.bufferSizeInFrames} frames")
        try {
            while (session === token) {
                // Waiting a little longer than a block covers the network jitter; past that, play silence.
                val chunk = queue.poll(15, TimeUnit.MILLISECONDS) ?: silence
                EchoCanceller.render(chunk)
                val result = track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                if (result < 0) {
                    Log.e(TAG, "AudioTrack write failed: $result")
                    break
                }
                written += result
                if (++blocks % 50 == 0) latencyMs = measureLatency(track, timestamp, written)
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    /**
     * Output latency from the track's own timestamps: frames written but not yet presented, plus the queue.
     * Falls back to the buffer size when the device gives no timestamp.
     */
    private fun measureLatency(track: AudioTrack, timestamp: AudioTimestamp, written: Long): Int {
        val pendingFrames = if (track.getTimestamp(timestamp)) {
            val sinceTimestamp = (System.nanoTime() - timestamp.nanoTime) * SAMPLE_RATE / 1_000_000_000L
            written - timestamp.framePosition - sinceTimestamp
        } else {
            track.bufferSizeInFrames.toLong()
        }
        val measured = (pendingFrames * 1000 / SAMPLE_RATE).toInt() + queue.size * 10
        // Smoothed so that the video delay does not jitter with each measurement.
        val smoothed = if (latencyMs == 0) measured else (latencyMs * 3 + measured) / 4
        if (blocks(smoothed) != blocks(latencyMs)) Log.i(TAG, "Output latency ${smoothed} ms")
        return smoothed.coerceAtLeast(0)
    }

    /** Coarse buckets of 20 ms, to log only meaningful latency changes. */
    private fun blocks(ms: Int) = ms / 20
}
