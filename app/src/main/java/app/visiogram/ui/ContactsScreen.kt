package app.visiogram.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.visiogram.calls.CallManager
import app.visiogram.telegram.Telegram
import app.visiogram.telegram.getOrThrow
import app.visiogram.ui.components.Avatar
import app.visiogram.ui.components.displayName
import dev.g000sha256.tdl.dto.User
import kotlinx.coroutines.launch

@Composable
fun ContactsTab(telegram: Telegram, calls: CallManager, autoFocus: Boolean, onAutoFocused: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<User?>(null) }

    val contacts by produceState<List<User>?>(null) {
        value = runCatching {
            telegram.client.getContacts().getOrThrow().userIds
                .map { telegram.user(it) }
                .sortedBy { it.displayName.lowercase() }
        }.getOrDefault(emptyList())
    }

    val list = contacts
    when {
        list == null -> Text("Chargement des contacts…")
        list.isEmpty() -> Text("Aucun contact. Ajoutez des contacts depuis votre téléphone.")
        else -> ContactGrid(list, telegram, autoFocus, onAutoFocused, onSelect = { selected = it })
    }

    selected?.let { user ->
        CallChooserDialog(
            avatar = { Avatar(user, telegram, 120.dp) },
            title = user.displayName,
            subtitle = null,
            videoLabel = "Appel vidéo",
            audioLabel = "Appel audio",
            onCall = { video ->
                selected = null
                scope.launch { calls.startCall(user.id, video) }
            },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun ContactGrid(contacts: List<User>, telegram: Telegram, autoFocus: Boolean, onAutoFocused: () -> Unit, onSelect: (User) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (autoFocus) {
            runCatching { firstFocus.requestFocus() }
            onAutoFocused()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(180.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        items(contacts, key = { it.id }) { user ->
            Card(
                onClick = { onSelect(user) },
                modifier = if (user == contacts.first()) Modifier.focusRequester(firstFocus) else Modifier,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(user, telegram, 96.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        user.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
