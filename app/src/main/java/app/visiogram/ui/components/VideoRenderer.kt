package app.visiogram.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
 * The renderer always crops the frame to the shape of its view, so the view takes the frame's shape, centred
 * in [modifier]'s bounds: the whole picture shows. [onAspectRatio] reports that shape (width / height, rotation
 * applied) for layouts that adapt to it.
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
    onAspectRatio: (Float) -> Unit = {},
) {
    var aspectRatio by remember { mutableStateOf<Float?>(null) }
    val reportAspectRatio by rememberUpdatedState(onAspectRatio)
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = aspectRatio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize(),
            factory = { context ->
                TextureViewRenderer(context).apply {
                    val view = this
                    init(Media.eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() {}

                        // Called on the render thread.
                        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                            if (width <= 0 || height <= 0) return
                            val ratio = if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width
                            view.post {
                                aspectRatio = ratio
                                reportAspectRatio(ratio)
                            }
                        }
                    })
                }
            },
            update = { view ->
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
}

private class Attachment(val key: Any?, val detach: (VideoSink) -> Unit)
