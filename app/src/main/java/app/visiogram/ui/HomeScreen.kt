package app.visiogram.ui

import app.visiogram.ui.theme.Success
import app.visiogram.ui.theme.Brand
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Border
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import app.visiogram.calls.CallManager
import app.visiogram.calls.GroupCallManager
import app.visiogram.telegram.Telegram
import app.visiogram.telegram.getOrThrow
import app.visiogram.ui.components.Avatar
import app.visiogram.ui.components.displayName
import dev.g000sha256.tdl.dto.User
import kotlinx.coroutines.launch

private val tabs = listOf("Groupes", "Contacts", "Réglages")

@Composable
fun HomeScreen(telegram: Telegram, calls: CallManager, groupCalls: GroupCallManager, tab: Int, onTabChange: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    // Only the first list shown at startup grabs the focus; switching tabs must leave it on the tab row.
    var autoFocus by remember { mutableStateOf(true) }
    val me by produceState<User?>(null) { value = runCatching { telegram.client.getMe().getOrThrow() }.getOrNull() }
    // The settings tab has no list to focus: when the screen comes back on it after a call, focus its tab instead.
    val selectedTabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (tab >= 2) {
            runCatching { selectedTabFocus.requestFocus() }
            autoFocus = false
        }
    }

    // The grid fills the screen under the top bar, which moves up with the first row so it reads as part of the page
    // and scrolls away with it. It stays outside the grid so switching tabs does not dispose the focused tab.
    val gridState = remember(tab) { LazyGridState() }
    var topBarHeight by remember { mutableIntStateOf(0) }
    val topBarHidden by remember(gridState) {
        derivedStateOf {
            if (gridState.firstVisibleItemIndex == 0) minOf(gridState.firstVisibleItemScrollOffset, topBarHeight) else topBarHeight
        }
    }
    val topBarHeightDp = with(LocalDensity.current) { topBarHeight.toDp() }

    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp),
    ) {
        val onAutoFocused = { autoFocus = false }
        val contentPadding = PaddingValues(top = topBarHeightDp + 28.dp, bottom = 12.dp)
        when (tab) {
            0 -> GroupsTab(telegram, groupCalls, autoFocus, onAutoFocused, gridState, contentPadding)
            1 -> ContactsTab(telegram, calls, autoFocus, onAutoFocused, gridState, contentPadding)
            else -> Box(Modifier.padding(top = topBarHeightDp + 16.dp)) {
                SettingsTab(onLogOut = { scope.launch { telegram.logOut() } })
            }
        }

        Row(
            Modifier
                .onSizeChanged { topBarHeight = it.height }
                .offset { IntOffset(0, -topBarHidden) }
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Coming up from any page lands on the selected tab, never on whichever tab is geometrically closest,
            // and brings the whole bar back into view.
            TabRow(
                selectedTabIndex = tab,
                modifier = Modifier
                    .focusProperties { onEnter = { selectedTabFocus.requestFocus() } }
                    .onFocusChanged { if (it.hasFocus && tab < 2) scope.launch { gridState.animateScrollToItem(0) } }
                    .focusGroup(),
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onFocus = { onTabChange(index) },
                        modifier = if (tab == index) Modifier.focusRequester(selectedTabFocus) else Modifier,
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            me?.let {
                Avatar(it, telegram, 32.dp)
                Spacer(Modifier.width(10.dp))
                Text(it.displayName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Video / audio chooser: who is being called on top, two large action tiles below.
 * A Dialog keeps D-pad focus inside it; Back cancels.
 */
@Composable
internal fun CallChooserDialog(
    avatar: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    videoLabel: String,
    audioLabel: String,
    onCall: (video: Boolean) -> Unit,
    onDismiss: () -> Unit,
    live: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(620.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
                .padding(36.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                avatar()
                Spacer(Modifier.width(24.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    subtitle?.let {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (live) {
                                Box(Modifier.size(10.dp).background(Success, CircleShape))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                it,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (live) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ActionTile(Icons.Filled.Videocam, videoLabel, Brand, { onCall(true) }, Modifier.weight(1f).focusRequester(focus))
                ActionTile(Icons.Filled.Call, audioLabel, Success, { onCall(false) }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Retour pour annuler",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** Large tinted tile with an icon and a label; fills with its color and gets a white border when focused. */
@Composable
private fun ActionTile(icon: ImageVector, label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.height(140.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = color.copy(alpha = 0.18f),
            contentColor = Color.White,
            focusedContainerColor = color,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}
