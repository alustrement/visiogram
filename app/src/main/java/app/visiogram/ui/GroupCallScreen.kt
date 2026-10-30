package app.visiogram.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.visiogram.calls.CameraSource
import app.visiogram.calls.GroupCallManager
import app.visiogram.calls.GroupCallPhase
import app.visiogram.calls.GroupCallUiState
import app.visiogram.calls.GroupParticipant
import app.visiogram.telegram.Telegram
import app.visiogram.ui.components.PhotoAvatar
import app.visiogram.ui.components.VideoRenderer
import app.visiogram.ui.theme.Danger
import app.visiogram.ui.theme.Success
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A camera or screen-share tile of one participant. */
private data class Tile(val key: String, val participant: GroupParticipant, val isScreen: Boolean)

private const val MAX_TILES = 16

@Composable
fun GroupCallScreen(call: GroupCallUiState, groupCalls: GroupCallManager, telegram: Telegram) {
    var pinned by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    // Pinning moves the tile elsewhere in the tree, so focus is handed back to it explicitly.
    var focusKey by remember { mutableStateOf<String?>(null) }
    val tileFocus = remember { FocusRequester() }
    LaunchedEffect(pinned, focusKey) { if (focusKey != null) runCatching { tileFocus.requestFocus() } }
    // Back only leaves the full-screen tile; it never silently leaves the video chat.
    BackHandler { pinned = null }

    val tiles = call.participants.flatMap { p ->
        listOfNotNull(Tile(p.key, p, isScreen = false), p.screenSsrc?.let { Tile("${p.key}:screen", p, isScreen = true) })
    }
    val pinnedTile = tiles.firstOrNull { it.key == pinned }
    val shown = if (pinnedTile != null) listOf(pinnedTile) else tiles.take(MAX_TILES)

    // The title and the controls fold away with the HUD, and the tiles take their room.
    val hud = rememberCallHud(autoHide = call.phase == GroupCallPhase.ACTIVE && !showSettings)
    val hudFraction = animateFloatAsState(if (hud.visible) 1f else 0f, label = "hud")

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .wakesHud(hud)
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Column(Modifier.collapsible({ hudFraction.value }, towardsTop = true)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(call.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(16.dp))
                Text(statusText(call), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(16.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) {
                Text(
                    if (call.phase == GroupCallPhase.ENDED) call.endReason.orEmpty() else "Connexion au chat vidéo…",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                TileGrid(shown, call, groupCalls, telegram, focusKey, tileFocus, onToggle = { key ->
                    pinned = if (pinnedTile?.key == key) null else key
                    focusKey = key
                })
            }
        }

        if (call.phase != GroupCallPhase.ENDED) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .collapsible({ hudFraction.value }, towardsTop = false),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(20.dp))
                Controls(call, groupCalls, onSettings = { showSettings = true })
            }
        }
    }

    if (showSettings && call.phase != GroupCallPhase.ENDED) CallSettingsPanel(onClose = { showSettings = false })
}

/**
 * Takes [fraction] of its height and fades by as much, its content sliding out over the top or bottom edge.
 * At 0 it is still composed, so a focused control inside keeps the focus.
 */
private fun Modifier.collapsible(fraction: () -> Float, towardsTop: Boolean) = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height * fraction()).roundToInt()
        layout(placeable.width, height) { placeable.place(0, if (towardsTop) height - placeable.height else 0) }
    }
    .graphicsLayer { alpha = fraction() }

private fun statusText(call: GroupCallUiState): String = when (call.phase) {
    GroupCallPhase.JOINING -> "Connexion…"
    GroupCallPhase.ACTIVE -> call.participants.size.let { if (it > 1) "$it participants" else "$it participant" }
    GroupCallPhase.ENDED -> call.endReason ?: "Chat vidéo terminé"
}

@Composable
private fun TileGrid(
    tiles: List<Tile>,
    call: GroupCallUiState,
    groupCalls: GroupCallManager,
    telegram: Telegram,
    focusKey: String?,
    tileFocus: FocusRequester,
    onToggle: (String) -> Unit,
) {
    val columns = ceil(sqrt(tiles.size.toDouble())).toInt().coerceAtLeast(1)
    val rows = tiles.chunked(columns)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile ->
                    ParticipantTile(
                        tile, call, groupCalls, telegram,
                        onClick = { onToggle(tile.key) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .then(if (tile.key == focusKey) Modifier.focusRequester(tileFocus) else Modifier),
                    )
                }
                // Keep the last row's tiles the same width as the others.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ParticipantTile(
    tile: Tile,
    call: GroupCallUiState,
    groupCalls: GroupCallManager,
    telegram: Telegram,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = tile.participant
    val shape = RoundedCornerShape(16.dp)
    val remoteSsrc = if (tile.isScreen) p.screenSsrc else p.videoSsrc?.takeIf { !p.videoPaused }
    val showLocal = p.isCurrentUser && !tile.isScreen && call.cameraEnabled

    Surface(
        onClick = onClick,
        modifier = modifier.then(if (p.isSpeaking) Modifier.border(3.dp, Success, shape) else Modifier),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1B2129), focusedContainerColor = Color(0xFF1B2129)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(4.dp, Color.White), shape = shape)),
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                showLocal -> VideoRenderer(
                    sinkKey = Unit,
                    attach = { CameraSource.previewSink = it },
                    detach = { if (CameraSource.previewSink === it) CameraSource.previewSink = null },
                    mirror = true,
                    modifier = Modifier.fillMaxSize(),
                )
                remoteSsrc != null -> VideoRenderer(
                    sinkKey = remoteSsrc,
                    attach = { groupCalls.setVideoSink(remoteSsrc, it) },
                    detach = { groupCalls.removeVideoSink(remoteSsrc, it) },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> PhotoAvatar(p.photo, p.name, p.colorSeed, telegram, 96.dp, Modifier.align(Alignment.Center))
            }
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (p.isMuted && !tile.isScreen) {
                    Icon(Icons.Filled.MicOff, contentDescription = "Micro coupé", tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                val name = if (p.isCurrentUser) "Vous" else p.name
                Text(
                    if (tile.isScreen) "Écran de $name" else name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Controls(call: GroupCallUiState, groupCalls: GroupCallManager, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { primaryFocus.requestFocus() } }
    val micLocked = call.micMuted && !call.canUnmute

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        CallButton(
            if (call.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
            when {
                micLocked -> "Micro coupé par un admin"
                call.micMuted -> "Activer le micro"
                else -> "Couper le micro"
            },
            Color.White.copy(alpha = if (call.micMuted) 0.9f else 0.2f),
            groupCalls::toggleMute,
            Modifier.focusRequester(primaryFocus),
        )
        CallButton(
            if (call.cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
            if (call.cameraEnabled) "Couper la caméra" else "Activer la caméra",
            Color.White.copy(alpha = if (call.cameraEnabled) 0.2f else 0.9f),
            groupCalls::toggleCamera,
        )
        CallButton(Icons.Filled.Tune, "Réglages", Color.White.copy(alpha = 0.2f), onSettings)
        CallButton(Icons.Filled.CallEnd, "Quitter", Danger, { groupCalls.leave() })
        if (call.canEndForAll) {
            CallButton(Icons.Filled.Close, "Terminer pour tous", Danger.copy(alpha = 0.6f), groupCalls::endForAll)
        }
    }
}
