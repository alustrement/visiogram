package app.visiogram.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.visiogram.calls.GroupCallManager
import app.visiogram.telegram.Telegram
import app.visiogram.telegram.getOrThrow
import app.visiogram.ui.components.PhotoAvatar
import app.visiogram.ui.theme.Success
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypeSupergroup

private val Chat.isGroup: Boolean
    get() = type is ChatTypeBasicGroup || (type as? ChatTypeSupergroup)?.isChannel == false

@Composable
fun GroupsTab(
    telegram: Telegram,
    groupCalls: GroupCallManager,
    autoFocus: Boolean,
    onAutoFocused: () -> Unit,
    gridState: LazyGridState,
    contentPadding: PaddingValues,
) {
    var selected by remember { mutableStateOf<Chat?>(null) }
    val videoChats by telegram.videoChats.collectAsStateWithLifecycle()
    val isLive = { chat: Chat -> (videoChats[chat.id] ?: chat.videoChat).groupCallId != 0 }

    val groups by produceState<List<Chat>?>(null) {
        value = runCatching {
            telegram.client.getChats(ChatListMain(), limit = 200).getOrThrow().chatIds
                .map { telegram.chat(it) }
                .filter { it.isGroup }
        }.getOrDefault(emptyList())
    }

    val list = groups
    when {
        list == null -> Text("Chargement des groupes…", Modifier.padding(contentPadding))
        list.isEmpty() -> Text("Aucun groupe.", Modifier.padding(contentPadding))
        // Groups with a running video chat come first, otherwise Telegram's order (most recent activity).
        else -> GroupGrid(
            list.sortedByDescending(isLive), telegram, isLive, autoFocus, onAutoFocused, gridState, contentPadding,
            onSelect = { selected = it },
        )
    }

    selected?.let { chat ->
        val live = isLive(chat)
        CallChooserDialog(
            avatar = { PhotoAvatar(chat.photo?.small, chat.title, chat.id, telegram, 120.dp) },
            title = chat.title,
            subtitle = if (live) "Chat vidéo en cours" else "Aucun chat vidéo en cours",
            videoLabel = if (live) "Rejoindre en vidéo" else "Démarrer en vidéo",
            audioLabel = if (live) "Rejoindre en audio" else "Démarrer en audio",
            onCall = { video ->
                selected = null
                groupCalls.join(chat.id, video)
            },
            onDismiss = { selected = null },
            live = live,
        )
    }
}

@Composable
private fun GroupGrid(
    groups: List<Chat>,
    telegram: Telegram,
    isLive: (Chat) -> Boolean,
    autoFocus: Boolean,
    onAutoFocused: () -> Unit,
    gridState: LazyGridState,
    contentPadding: PaddingValues,
    onSelect: (Chat) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (autoFocus) {
            runCatching { firstFocus.requestFocus() }
            onAutoFocused()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(200.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        state = gridState,
        contentPadding = contentPadding,
    ) {
        items(groups, key = { it.id }) { chat ->
            Card(
                onClick = { onSelect(chat) },
                modifier = if (chat == groups.first()) Modifier.focusRequester(firstFocus) else Modifier,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PhotoAvatar(chat.photo?.small, chat.title, chat.id, telegram, 96.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        chat.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        if (isLive(chat)) "● Chat vidéo en cours" else " ",
                        style = MaterialTheme.typography.labelLarge,
                        color = Success,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
