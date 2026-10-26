package app.visiogram.calls

import android.util.Log
import io.github.pytgcalls.VideoRotation
import io.github.pytgcalls.models.Frame
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/** Packed I420 byte arrays (decoded by ntgcalls, or captured by [CameraSource]) wrapped for a WebRTC [VideoSink]. */
object I420FrameRenderer {

    private const val TAG = "I420FrameRenderer"

    fun render(frame: Frame, sink: VideoSink) {
        render(frame.data, frame.frame_data.width, frame.frame_data.height, frame.frame_data.rotation.degrees, sink)
    }

    fun render(data: ByteArray, width: Int, height: Int, rotationDegrees: Int, sink: VideoSink) {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaSize = width * height
        val chromaSize = chromaWidth * chromaHeight
        if (width <= 0 || height <= 0 || data.size < lumaSize + 2 * chromaSize) {
            Log.w(TAG, "Dropping frame ${width}x$height with ${data.size} bytes")
            return
        }

        val buffer = JavaI420Buffer.allocate(width, height)
        buffer.dataY.put(data, 0, lumaSize)
        buffer.dataU.put(data, lumaSize, chromaSize)
        buffer.dataV.put(data, lumaSize + chromaSize, chromaSize)

        val videoFrame = VideoFrame(buffer, rotationDegrees, System.nanoTime())
        try {
            sink.onFrame(videoFrame)
        } finally {
            videoFrame.release()
        }
    }

    private val VideoRotation.degrees: Int
        get() = when (this) {
            VideoRotation.VIDEO_ROTATION_0 -> 0
            VideoRotation.VIDEO_ROTATION_90 -> 90
            VideoRotation.VIDEO_ROTATION_180 -> 180
            VideoRotation.VIDEO_ROTATION_270 -> 270
        }
}
