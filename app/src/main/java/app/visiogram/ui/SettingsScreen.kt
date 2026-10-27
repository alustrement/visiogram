package app.visiogram.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import app.visiogram.calls.CallSettings
import app.visiogram.calls.CameraSource
import app.visiogram.calls.MicSource
import app.visiogram.calls.MicTest
import app.visiogram.calls.MicTestPhase
import app.visiogram.calls.listCameras
import app.visiogram.calls.playTestTone
import app.visiogram.ui.components.MediaControls
import app.visiogram.ui.components.MicLevel
import app.visiogram.ui.components.VideoRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Camera preview, microphone and speaker checks, and the shared [MediaControls], laid out to fit one TV screen.
 * Tests only run while this tab is visible and the app is in the foreground; the tab is not composed during a call.
 */
@Composable
fun SettingsTab(onLogOut: () -> Unit) {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    val cameras = remember { listCameras(context) }
    val cameraId by CallSettings.cameraId.collectAsState()
    val camera = cameras.firstOrNull { it.id == cameraId } ?: cameras.firstOrNull()

    // Shared by the microphone tests on the right and the echo check on the left.
    val micTest = remember { MicTest() }
    if (active) {
        DisposableEffect(micTest) {
            micTest.start()
            onDispose { micTest.stop() }
        }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    camera == null -> Text("Aucune caméra détectée. Branchez une webcam USB.", color = Color.White)
                    !active -> Text("Aperçu en pause", color = Color.White.copy(alpha = 0.7f))
                    else -> CameraTest()
                }
            }
            EchoTest(micTest, active)
            LogOutButton(onLogOut)
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MediaControls(inCall = false)
            AudioTests(micTest, active)
        }
    }
}

/** Log-out button with a confirmation dialog whose focus starts on "Annuler". */
@Composable
private fun LogOutButton(onLogOut: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { confirming = true }, modifier = Modifier.padding(top = 12.dp)) {
        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Se déconnecter")
    }
    if (!confirming) return

    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
    Dialog(onDismissRequest = { confirming = false }) {
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Se déconnecter de Telegram ?", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Il faudra se reconnecter avec un QR code ou un numéro de téléphone.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = { confirming = false }, modifier = Modifier.focusRequester(cancelFocus)) { Text("Annuler") }
                Button(
                    onClick = {
                        confirming = false
                        onLogOut()
                    },
                ) { Text("Se déconnecter") }
            }
        }
    }
}

@Composable
private fun CameraTest() {
    val owner = remember { Any() }
    DisposableEffect(owner) {
        CameraSource.start(owner)
        onDispose { CameraSource.stop(owner) }
    }
    val stats by CameraSource.stats.collectAsState()
    Box(Modifier.fillMaxSize()) {
        VideoRenderer(
            sinkKey = Unit,
            attach = { CameraSource.previewSink = it },
            detach = { if (CameraSource.previewSink === it) CameraSource.previewSink = null },
            mirror = true,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            stats ?: "Ouverture de la caméra…",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Microphone device and level, "record then play back", and the speaker test tone. */
@Composable
private fun AudioTests(test: MicTest, active: Boolean) {
    val scope = rememberCoroutineScope()
    var playingTone by remember { mutableStateOf(false) }
    val level by MicSource.level.collectAsState()
    val device by MicSource.device.collectAsState()
    val phase by test.phase.collectAsState()

    MicLevel(if (active) level else 0f)
    Text(
        if (active) device ?: "Ouverture du micro…" else "Micro en pause",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = test::recordAndPlay, enabled = active && phase == MicTestPhase.IDLE) {
            Icon(Icons.Filled.FiberManualRecord, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when (phase) {
                    MicTestPhase.IDLE -> "Enregistrer ${MicTest.RECORD_SECONDS} s"
                    MicTestPhase.RECORDING -> "Parlez…"
                    MicTestPhase.PLAYING -> "Lecture…"
                    MicTestPhase.ECHO -> "Enregistrer ${MicTest.RECORD_SECONDS} s"
                },
            )
        }
        Button(
            onClick = {
                playingTone = true
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { playTestTone() } }
                    playingTone = false
                }
            },
            enabled = active && !playingTone,
        ) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (playingTone) "Lecture…" else "Son de test")
        }
    }
}

/** Echo check: the TV plays speech-like sound for a few seconds, then the result of the echo canceller shows. */
@Composable
private fun EchoTest(test: MicTest, active: Boolean) {
    val phase by test.phase.collectAsState()
    val result by test.echoResult.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = test::testEcho, enabled = active && phase == MicTestPhase.IDLE) {
            Icon(Icons.Filled.SurroundSound, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (phase == MicTestPhase.ECHO) "Test en cours…" else "Tester l'écho")
        }
        Text(
            result ?: "Joue un son sur la télé et mesure l'écho qui reviendrait dans le micro.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
