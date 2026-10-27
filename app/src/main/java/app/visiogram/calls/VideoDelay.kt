package app.visiogram.calls

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.pytgcalls.models.Frame
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Holds remote video back so that it shows when its sound is heard.
 *
 * WebRTC already lines the picture up with the sound it hands over, but takes that sound as played at once:
 * the time it then spends in [SpeakerSink] and in the TV's audio path ([SpeakerSink.latencyMs]) is added here,
 * plus the user's own correction ([CallSettings.avSyncMs]) for what cannot be measured, such as a Bluetooth
 * headset on the other side.
 */
class VideoDelay(name: String) {
    private val thread = HandlerThread(name).apply { start() }
    private val handler = Handler(thread.looper)

    // Frames waiting on the handler, released by clear() if they never get rendered. Guarded by itself.
    private val pending = LinkedHashSet<VideoFrame>()

    fun render(frame: Frame, sink: VideoSink) {
        val delayMs = (SpeakerSink.latencyMs + CallSettings.avSyncMs.value).coerceIn(0, MAX_DELAY_MS)
        if (delayMs == 0) {
            I420FrameRenderer.render(frame, sink)
            return
        }
        val videoFrame = I420FrameRenderer.toVideoFrame(frame) ?: return
        synchronized(pending) {
            // A stalled renderer must not pile up frames: drop the new one instead.
            if (pending.size >= MAX_PENDING) {
                videoFrame.release()
                return
            }
            pending += videoFrame
        }
        handler.postAtTime({
            val due = synchronized(pending) { pending.remove(videoFrame) }
            if (due) {
                try {
                    sink.onFrame(videoFrame)
                } finally {
                    videoFrame.release()
                }
            }
        }, SystemClock.uptimeMillis() + delayMs)
    }

    /** Drops the frames still waiting, e.g. when the call ends. */
    fun clear() {
        handler.removeCallbacksAndMessages(null)
        val dropped = synchronized(pending) { pending.toList().also { pending.clear() } }
        dropped.forEach { it.release() }
    }

    private companion object {
        const val MAX_DELAY_MS = 1000
        /** One second at 30 images/s for a few participants. */
        const val MAX_PENDING = 120
    }
}
