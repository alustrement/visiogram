package app.visiogram.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.visiogram.calls.CallManager
import app.visiogram.calls.CallPhase
import app.visiogram.calls.CameraSource
import app.visiogram.calls.CallUiState
import app.visiogram.telegram.Telegram
import app.visiogram.ui.components.Avatar
import app.visiogram.ui.components.VideoRenderer
import app.visiogram.ui.components.displayName
import app.visiogram.ui.theme.Danger
import app.visiogram.ui.theme.Success
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CallScreen(call: CallUiState, calls: CallManager, telegram: Telegram) {
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    // The remote control's Back key must never silently leave a call.
    BackHandler {}

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        VideoRenderer(
            sinkKey = Unit,
            attach = { calls.remoteVideoSink = it },
            detach = { if (calls.remoteVideoSink === it) calls.remoteVideoSink = null },
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (call.remoteVideoActive) 1f else 0f),
        )

        if (!call.remoteVideoActive) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(call.user, telegram, 180.dp)
                Spacer(Modifier.height(24.dp))
                Text(call.user?.displayName.orEmpty(), style = MaterialTheme.typography.displaySmall, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(statusText(call), style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.7f))
            }
        } else {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(call.user?.displayName.orEmpty(), style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(statusText(call), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f))
            }
        }

        if (call.emojis.isNotEmpty() && call.phase != CallPhase.ENDED) {
            Text(
                call.emojis.joinToString(" "),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp),
            )
        }

        if (call.cameraEnabled && call.phase != CallPhase.ENDED) {
            VideoRenderer(
                sinkKey = Unit,
                attach = { CameraSource.previewSink = it },
                detach = { if (CameraSource.previewSink === it) CameraSource.previewSink = null },
                mirror = true,
                fill = true,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(32.dp)
                    .size(width = 320.dp, height = 180.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }

        Controls(
            call = call,
            onAccept = { scope.launch { calls.accept() } },
            onHangUp = { scope.launch { calls.hangUp() } },
            onToggleMute = { scope.launch { calls.toggleMute() } },
            onToggleCamera = { scope.launch { calls.toggleCamera() } },
            onSettings = { showSettings = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
        )
    }

    if (showSettings && call.phase != CallPhase.ENDED) CallSettingsPanel(onClose = { showSettings = false })
}

@Composable
private fun statusText(call: CallUiState): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(call.phase) {
        while (call.phase == CallPhase.ACTIVE) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    return when (call.phase) {
        CallPhase.INCOMING -> if (call.isVideo) "Appel vidéo entrant…" else "Appel audio entrant…"
        CallPhase.OUTGOING -> "Appel en cours…"
        CallPhase.CONNECTING -> "Connexion…"
        CallPhase.ACTIVE -> {
            val seconds = ((now - call.activeSinceMs) / 1000).coerceAtLeast(0)
            "%d:%02d".format(seconds / 60, seconds % 60) + if (call.remoteMicMuted) " · micro coupé" else ""
        }
        CallPhase.ENDED -> call.endReason ?: "Appel terminé"
    }
}

@Composable
private fun Controls(
    call: CallUiState,
    onAccept: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (call.phase == CallPhase.ENDED) return
    val primaryFocus = remember { FocusRequester() }
    val incoming = call.phase == CallPhase.INCOMING
    LaunchedEffect(incoming) { runCatching { primaryFocus.requestFocus() } }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        if (incoming) {
            CallButton(Icons.Filled.Call, "Décrocher", Success, onAccept, Modifier.focusRequester(primaryFocus))
            CallButton(Icons.Filled.CallEnd, "Refuser", Danger, onHangUp)
        } else {
            CallButton(
                if (call.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                if (call.micMuted) "Activer le micro" else "Couper le micro",
                Color.White.copy(alpha = if (call.micMuted) 0.9f else 0.2f),
                onToggleMute,
            )
            CallButton(
                if (call.cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                if (call.cameraEnabled) "Couper la caméra" else "Activer la caméra",
                Color.White.copy(alpha = if (call.cameraEnabled) 0.2f else 0.9f),
                onToggleCamera,
            )
            CallButton(Icons.Filled.Tune, "Réglages", Color.White.copy(alpha = 0.2f), onSettings)
            CallButton(Icons.Filled.CallEnd, "Raccrocher", Danger, onHangUp, Modifier.focusRequester(primaryFocus))
        }
    }
}

@Composable
internal fun CallButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = modifier.size(88.dp),
            colors = IconButtonDefaults.colors(
                containerColor = color,
                contentColor = Color.White,
                focusedContainerColor = color,
                focusedContentColor = Color.White,
            ),
            scale = IconButtonDefaults.scale(focusedScale = 1.15f),
            border = IconButtonDefaults.border(
                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(4.dp, Color.White)),
            ),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}
