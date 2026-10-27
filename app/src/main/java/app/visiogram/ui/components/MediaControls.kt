package app.visiogram.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedIconButton
import androidx.tv.material3.Text
import app.visiogram.calls.CallSettings
import app.visiogram.calls.CameraSource
import app.visiogram.calls.MicSource
import app.visiogram.calls.VideoQuality
import app.visiogram.calls.listCameras
import app.visiogram.ui.theme.Success

private const val BRIGHTNESS_STEP = 1f
private const val CONTRAST_STEP = 1f
private const val ZOOM_STEP = 0.25f
private const val MIC_GAIN_STEP = 3f
private const val PAN_STEP = 0.2f
private const val AV_SYNC_STEP = 40

/**
 * Picture and microphone settings, shared by the Settings tab and the in-call settings panel.
 * Every change is applied live to the running capture.
 *
 * [inCall] hides what would interrupt a call (camera and quality reopen the camera) and shows the
 * microphone level instead, which the Settings tab displays with its own tests, and the picture delay,
 * which only means something during a call.
 * [firstFocus] is attached to the first control, for the in-call panel to focus it when it opens.
 */
@Composable
fun MediaControls(inCall: Boolean, modifier: Modifier = Modifier, firstFocus: FocusRequester? = null) {
    val context = LocalContext.current
    val cameras = remember { listCameras(context) }
    val cameraId by CallSettings.cameraId.collectAsState()
    val quality by CallSettings.quality.collectAsState()
    val brightness by CallSettings.exposure.collectAsState()
    val contrast by CallSettings.contrast.collectAsState()
    val zoom by CallSettings.zoom.collectAsState()
    val gain by CallSettings.micGain.collectAsState()
    val camera = cameras.firstOrNull { it.id == cameraId } ?: cameras.firstOrNull()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!inCall) {
            if (cameras.size > 1) {
                SettingRow(Icons.Filled.Videocam, "Caméra") {
                    cameras.forEach { info ->
                        Chip(info.label, selected = info.id == camera?.id) {
                            CallSettings.setCameraId(info.id)
                            CameraSource.reconfigure()
                        }
                    }
                }
            }
            SettingRow(Icons.Filled.Hd, "Qualité") {
                VideoQuality.entries.forEach { q ->
                    Chip(q.label, selected = q == quality) {
                        CallSettings.setQuality(q)
                        CameraSource.reconfigure()
                    }
                }
            }
        }
        Stepper(
            Icons.Filled.Brightness6, "Luminosité", signed(brightness),
            onMinus = { CallSettings.setExposure(brightness - BRIGHTNESS_STEP); CameraSource.applyControls() },
            onPlus = { CallSettings.setExposure(brightness + BRIGHTNESS_STEP); CameraSource.applyControls() },
            minusModifier = if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
        )
        Stepper(
            Icons.Filled.Contrast, "Contraste", signed(contrast),
            onMinus = { CallSettings.setContrast(contrast - CONTRAST_STEP); CameraSource.applyControls() },
            onPlus = { CallSettings.setContrast(contrast + CONTRAST_STEP); CameraSource.applyControls() },
        )
        Stepper(
            Icons.Filled.ZoomIn, "Cadrage", "×%.2f".format(zoom).trimEnd('0').trimEnd(',', '.'),
            onMinus = { CallSettings.setZoom(zoom - ZOOM_STEP); CameraSource.applyControls() },
            onPlus = { CallSettings.setZoom(zoom + ZOOM_STEP); CameraSource.applyControls() },
        )
        if (zoom > 1f) PositionRow()
        Stepper(
            Icons.Filled.Mic, "Volume micro", if (gain == 0f) "0 dB" else "%+.0f dB".format(gain),
            onMinus = { CallSettings.setMicGain(gain - MIC_GAIN_STEP) },
            onPlus = { CallSettings.setMicGain(gain + MIC_GAIN_STEP) },
        )
        if (inCall) {
            val level by MicSource.level.collectAsState()
            MicLevel(level)
            val avSync by CallSettings.avSyncMs.collectAsState()
            Stepper(
                Icons.Filled.Sync, "Synchro image", if (avSync == 0) "auto" else "%+d ms".format(avSync),
                onMinus = { CallSettings.setAvSync(avSync - AV_SYNC_STEP) },
                onPlus = { CallSettings.setAvSync(avSync + AV_SYNC_STEP) },
            )
        }
    }
}

@Composable
private fun PositionRow() {
    val panX by CallSettings.panX.collectAsState()
    val panY by CallSettings.panY.collectAsState()
    val pan = { dx: Float, dy: Float ->
        CallSettings.setPan(panX + dx, panY + dy)
        CameraSource.applyControls()
    }
    SettingRow(Icons.Filled.OpenWith, "Position") {
        SmallIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Vers la gauche") { pan(-PAN_STEP, 0f) }
        SmallIconButton(Icons.Filled.KeyboardArrowUp, "Vers le haut") { pan(0f, -PAN_STEP) }
        SmallIconButton(Icons.Filled.KeyboardArrowDown, "Vers le bas") { pan(0f, PAN_STEP) }
        SmallIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Vers la droite") { pan(PAN_STEP, 0f) }
        SmallIconButton(Icons.Filled.CenterFocusStrong, "Recentrer") {
            CallSettings.setPan(0f, 0f)
            CameraSource.applyControls()
        }
    }
}

/** Microphone level bar, from 0 (silence) to 1 (full scale); red when close to saturation. */
@Composable
fun MicLevel(level: Float, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.height(32.dp)) {
        Icon(Icons.Filled.GraphicEq, contentDescription = "Niveau du micro", modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(level)
                    .height(10.dp)
                    .background(if (level > 0.9f) MaterialTheme.colorScheme.error else Success),
            )
        }
    }
}

/** Icon and label on the left, controls on the right; one compact line per setting. */
@Composable
private fun SettingRow(icon: ImageVector, label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.width(104.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/** − value + controls; the buttons are never disabled so D-pad focus never gets lost at the limits. */
@Composable
private fun Stepper(
    icon: ImageVector,
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    minusModifier: Modifier = Modifier,
) {
    SettingRow(icon, label) {
        SmallIconButton(Icons.Filled.Remove, "Diminuer", minusModifier, onMinus)
        Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
        SmallIconButton(Icons.Filled.Add, "Augmenter", onClick = onPlus)
    }
}

@Composable
private fun SmallIconButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedIconButton(onClick = onClick, modifier = modifier.size(36.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick) { Text(label, maxLines = 1, softWrap = false) }
}

private fun signed(value: Float): String = when {
    value == 0f -> "0"
    value % 1f == 0f -> "%+.0f".format(value)
    else -> "%+.1f".format(value)
}
