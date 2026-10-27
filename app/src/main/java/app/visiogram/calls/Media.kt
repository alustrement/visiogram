package app.visiogram.calls

import android.util.Log
import io.github.pytgcalls.NTgCalls
import io.github.pytgcalls.VideoRotation
import io.github.pytgcalls.media.AudioDescription
import io.github.pytgcalls.media.MediaDescription
import io.github.pytgcalls.media.MediaSource
import io.github.pytgcalls.media.StreamDevice
import io.github.pytgcalls.media.VideoDescription
import io.github.pytgcalls.models.FrameData
import org.webrtc.EglBase

/**
 * Media devices, stream descriptions and the EGL context shared by private and group calls.
 *
 * The audio mode is deliberately left at MODE_NORMAL: on the Shield, MODE_IN_COMMUNICATION routes every
 * output to a silent "DummySpeaker" instead of HDMI, and a TV has no earpiece or hardware echo canceller
 * that the mode would enable.
 */
object Media {
    private const val TAG = "Media"

    val eglBase: EglBase by lazy { EglBase.create() }

    private val devices by lazy { NTgCalls.getMediaDevices() }

    fun logDevices() {
        devices.microphone.forEach { Log.i(TAG, "Microphone: ${it.name} ${it.metadata}") }
        devices.speaker.forEach { Log.i(TAG, "Speaker: ${it.name} ${it.metadata}") }
        devices.camera.forEach { Log.i(TAG, "Camera: ${it.name} ${it.metadata}") }
    }

    fun captureDescription(withCamera: Boolean): MediaDescription {
        // The microphone is captured by MicSource (for its volume setting) and pushed as an external source.
        val microphone = AudioDescription(MediaSource.EXTERNAL, MicSource.SAMPLE_RATE, 1, "", false)
        // The camera is captured by CameraSource (for exposure and zoom) and pushed as an external source.
        val camera = if (withCamera && CameraSource.hasCamera()) {
            val quality = CallSettings.quality.value
            VideoDescription(MediaSource.EXTERNAL, quality.width, quality.height, 30, "", false)
        } else {
            null
        }
        return MediaDescription(microphone, null, camera, null)
    }

    /** Feeds [CameraSource] frames to the ntgcalls call identified by [callId], on behalf of [owner]. */
    fun startCamera(owner: Any, ntg: NTgCalls, callId: Long) {
        CameraSource.start(owner) { data, width, height ->
            runCatching {
                ntg.sendExternalFrame(callId, StreamDevice.CAMERA, data, FrameData(System.currentTimeMillis(), VideoRotation.VIDEO_ROTATION_0, width, height))
            }
        }
    }

    /** Feeds [MicSource] audio to the ntgcalls call identified by [callId], on behalf of [owner]. */
    fun startMicrophone(owner: Any, ntg: NTgCalls, callId: Long) {
        MicSource.start(owner) { _, pcm ->
            runCatching {
                ntg.sendExternalFrame(callId, StreamDevice.MICROPHONE, pcm, FrameData(System.currentTimeMillis(), VideoRotation.VIDEO_ROTATION_0, 0, 0))
            }
        }
    }

    /**
     * In PLAYBACK mode ntgcalls reads the slots as remote devices: incoming audio is only negotiated when the
     * microphone slot is set, and incoming video only when the camera or screen slot is set. All are external
     * sinks, delivered through onFrames: the sound goes to [SpeakerSink], the picture through a [VideoDelay].
     */
    fun playbackDescription(): MediaDescription {
        val remoteAudio = AudioDescription(MediaSource.EXTERNAL, SpeakerSink.SAMPLE_RATE, 1, "", false)
        // Size and frame rate are unknown before the first frame; ntgcalls takes -1/-1/0 as "as received".
        val remoteVideo = VideoDescription(MediaSource.EXTERNAL, -1, -1, 0, "", false)
        return MediaDescription(remoteAudio, null, remoteVideo, remoteVideo)
    }
}
