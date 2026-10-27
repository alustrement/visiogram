package app.visiogram.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.visiogram.calls.EchoResult
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
fun SettingsTab(navBarFocus: FocusRequester, onLogOut: () -> Unit) {
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

    // Two columns for the D-pad: coming down from the nav bar lands on the first action ("Tester le son"),
    // the values on the right are entered at their top, and going up from either column returns to the nav bar.
    val firstAction = remember { FocusRequester() }
    val firstValue = remember { FocusRequester() }
    Row(
        Modifier
            .fillMaxSize()
            .focusProperties {
                onEnter = { firstAction.requestFocus() }
                onExit = { if (requestedFocusDirection == FocusDirection.Up) navBarFocus.requestFocus() }
            }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
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
            Actions(micTest, active, firstAction, onLogOut)
        }

        Column(
            Modifier
                .weight(1f)
                .focusProperties { onEnter = { firstValue.requestFocus() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MediaControls(inCall = false, firstFocus = firstValue)
            MicMeter(active)
        }
    }
}

private enum class AudioCheck { ECHO, MICROPHONE, SOUND }

/**
 * The four actions of the tab, alike and two by two. A check opens a dialog that follows it and then shows
 * its result; logging out asks for confirmation first.
 */
@Composable
private fun Actions(test: MicTest, active: Boolean, firstFocus: FocusRequester, onLogOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<AudioCheck?>(null) }
    var confirmingLogOut by remember { mutableStateOf(false) }
    // Result of the last sound check: the output it played on, or null while it plays.
    var soundDevice by remember { mutableStateOf<Result<String?>?>(null) }
    val phase by test.phase.collectAsState()
    val idle = active && phase == MicTestPhase.IDLE

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(
                Icons.AutoMirrored.Filled.VolumeUp, "Tester le son", enabled = active,
                modifier = Modifier.weight(1f).focusRequester(firstFocus),
            ) {
                soundDevice = null
                shown = AudioCheck.SOUND
                scope.launch { soundDevice = withContext(Dispatchers.IO) { runCatching { playTestTone() } } }
            }
            ActionButton(Icons.Filled.Mic, "Tester le micro", enabled = idle, modifier = Modifier.weight(1f)) {
                test.recordAndPlay()
                shown = AudioCheck.MICROPHONE
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(Icons.Filled.SurroundSound, "Tester l'écho", enabled = idle, modifier = Modifier.weight(1f)) {
                test.testEcho()
                shown = AudioCheck.ECHO
            }
            ActionButton(Icons.AutoMirrored.Filled.Logout, "Se déconnecter", modifier = Modifier.weight(1f)) {
                confirmingLogOut = true
            }
        }
    }

    when (shown) {
        AudioCheck.ECHO -> EchoCheckDialog(test) { shown = null }
        AudioCheck.MICROPHONE -> MicrophoneCheckDialog(test) { shown = null }
        AudioCheck.SOUND -> SoundCheckDialog(soundDevice) { shown = null }
        null -> Unit
    }
    if (confirmingLogOut) {
        LogOutDialog(
            onCancel = { confirmingLogOut = false },
            onConfirm = {
                confirmingLogOut = false
                onLogOut()
            },
        )
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
private fun EchoCheckDialog(test: MicTest, onClose: () -> Unit) {
    val result by test.echoResult.collectAsState()
    val outcome = result
    CheckDialog(
        title = "Test de l'écho",
        running = outcome == null,
        message = when {
            outcome == null -> "L'appareil joue un son pendant ${MicTest.ECHO_TEST_SECONDS} secondes, au volume habituel des appels."
            outcome.isFailure -> "Test impossible : ${outcome.exceptionOrNull()?.message}."
            else -> echoSummary(outcome.getOrThrow())
        },
        onClose = onClose,
    )
}

// Non-breaking spaces keep a value and its unit on the same line.
private fun echoSummary(result: EchoResult): String = buildString {
    appendLine("Sans annulation, le son de l'appareil revient dans le micro %+.0f\u00A0dB au-dessus du bruit de la pièce.".format(result.beforeDb))
    appendLine("Avec l'annulation d'écho\u00A0: %+.0f\u00A0dB.".format(result.afterDb))
    result.delayMs?.let { appendLine("L'écho revient après %.0f\u00A0ms.".format(it)) }
    append(
        when {
            result.beforeDb < 6 -> "L'appareil s'entend à peine dans le micro : montez le volume pour un test parlant."
            result.afterDb <= 3 -> "Votre correspondant ne devrait pas entendre d'écho."
            else -> "Un peu d'écho peut rester : baissez le volume ou éloignez le micro des haut-parleurs."
        },
    )
}

@Composable
private fun MicrophoneCheckDialog(test: MicTest, onClose: () -> Unit) {
    val phase by test.phase.collectAsState()
    val device by MicSource.device.collectAsState()
    CheckDialog(
        title = "Test du micro",
        running = phase != MicTestPhase.IDLE,
        message = when (phase) {
            MicTestPhase.RECORDING -> "Parlez pendant ${MicTest.RECORD_SECONDS} secondes…"
            MicTestPhase.PLAYING -> "Réécoute de l'enregistrement…"
            else -> "Votre voix devait s'entendre comme votre correspondant l'entendra" +
                (device?.let { ", avec le micro $it." } ?: ".") +
                "\nTrop faible ou trop forte : réglez le volume du micro."
        },
        onClose = onClose,
    )
}

@Composable
private fun SoundCheckDialog(device: Result<String?>?, onClose: () -> Unit) {
    CheckDialog(
        title = "Test du son",
        running = device == null,
        message = when {
            device == null -> "Lecture d'un carillon sur la sortie audio des appels…"
            device.isFailure -> "Lecture impossible : ${device.exceptionOrNull()?.message}."
            else -> "Le carillon a été joué" + (device.getOrNull()?.let { " sur $it" } ?: "") +
                ".\nRien entendu ? Vérifiez le volume et la sortie audio de l'appareil."
        },
        onClose = onClose,
    )
}

/** Dialog that follows a running check, then shows its result with a "Fermer" button. Back closes it at any time. */
@Composable
private fun CheckDialog(title: String, running: Boolean, message: String, onClose: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(running) { if (!running) runCatching { closeFocus.requestFocus() } }
    SettingsDialog(onDismiss = onClose) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (running) {
            Text("En cours…", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        } else {
            Button(onClick = onClose, modifier = Modifier.focusRequester(closeFocus)) { Text("Fermer") }
        }
    }
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** Log-out confirmation, whose focus starts on "Annuler". */
@Composable
private fun LogOutDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
    SettingsDialog(onDismiss = onCancel) {
        Text("Se déconnecter de Telegram ?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Il faudra se reconnecter avec un QR code ou un numéro de téléphone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) { Text("Annuler") }
            Button(onClick = onConfirm) { Text("Se déconnecter") }
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

/** Microphone level and device, under the microphone volume setting. */
@Composable
private fun MicMeter(active: Boolean) {
    val level by MicSource.level.collectAsState()
    val device by MicSource.device.collectAsState()
    MicLevel(if (active) level else 0f)
    Text(
        if (active) device ?: "Ouverture du micro…" else "Micro en pause",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
