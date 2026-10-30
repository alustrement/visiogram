package app.visiogram.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart

private const val HUD_TIMEOUT_MS = 5_000L

/**
 * Call controls and info that hide after a while without input; any key, tap or mouse move brings them back.
 * Hidden controls stay composed and keep the focus, so the input that wakes the HUD is swallowed
 * (see [wakesHud]): it must not press the invisible button underneath.
 */
@Stable
class CallHud {
    var visible by mutableStateOf(true)
        internal set
    internal val inputs = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal var swallowedKey: Key? = null

    /** Shows the HUD and restarts its timeout; returns whether it was hidden. */
    fun wake(): Boolean {
        val wasHidden = !visible
        visible = true
        inputs.tryEmit(Unit)
        return wasHidden
    }
}

/** The HUD only hides while [autoHide] holds, e.g. once the call is connected. */
@Composable
fun rememberCallHud(autoHide: Boolean): CallHud {
    val hud = remember { CallHud() }
    LaunchedEffect(autoHide) {
        hud.visible = true
        if (!autoHide) return@LaunchedEffect
        hud.inputs.onStart { emit(Unit) }.collectLatest {
            hud.visible = true
            delay(HUD_TIMEOUT_MS)
            hud.visible = false
        }
    }
    return hud
}

/** Wakes [hud] on any input reaching this node, swallowing the key press or pointer gesture that woke it. */
fun Modifier.wakesHud(hud: CallHud): Modifier = this
    .onPreviewKeyEvent { event ->
        when (event.type) {
            // Key repeats of the waking press are swallowed too.
            KeyEventType.KeyDown -> (hud.wake() || event.key == hud.swallowedKey).also { if (it) hud.swallowedKey = event.key }
            KeyEventType.KeyUp -> (event.key == hud.swallowedKey).also { if (it) hud.swallowedKey = null }
            else -> false
        }
    }
    .pointerInput(hud) {
        awaitPointerEventScope {
            var swallowing = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Exit) continue
                if (hud.wake() && event.type == PointerEventType.Press) swallowing = true
                if (swallowing) {
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) swallowing = false
                }
            }
        }
    }
