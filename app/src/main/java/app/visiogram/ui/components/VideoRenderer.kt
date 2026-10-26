package app.visiogram.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.visiogram.calls.Media
import org.webrtc.RendererCommon
import org.webrtc.TextureViewRenderer
import org.webrtc.VideoSink

/**
 * WebRTC renderer that registers itself as a frame sink through [attach] and unregisters through [detach].
 * Changing [sinkKey] re-registers the same view, e.g. when a participant's video ssrc changes.
 *
 * The view's lifetime follows AndroidView's own callbacks: the renderer is released in `onRelease` only,
 * never because of a recomposition.
 */
@Composable
fun VideoRenderer(
    sinkKey: Any?,
    attach: (VideoSink) -> Unit,
    detach: (VideoSink) -> Unit,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    fill: Boolean = false,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureViewRenderer(context).apply { init(Media.eglBase.eglBaseContext, null) }
        },
        update = { view ->
            view.setScalingType(if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            view.setMirror(mirror)
            val current = view.tag as? Attachment
            if (current == null || current.key != sinkKey) {
                current?.detach?.invoke(view)
                attach(view)
                // Keep the detach that matches this registration (it may capture the old key).
                view.tag = Attachment(sinkKey, detach)
            }
        },
        onRelease = { view ->
            (view.tag as? Attachment)?.detach?.invoke(view)
            view.tag = null
            view.release()
        },
    )
}

private class Attachment(val key: Any?, val detach: (VideoSink) -> Unit)
