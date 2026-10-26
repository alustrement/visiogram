package app.visiogram.telegram

import android.content.Context
import android.os.Build
import android.util.Log
import app.visiogram.BuildConfig
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.File
import dev.g000sha256.tdl.dto.User
import dev.g000sha256.tdl.dto.VideoChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class TelegramException(val code: Int, message: String) : Exception("$code: $message")

fun <T> TdlResult<T>.getOrThrow(): T = when (this) {
    is TdlResult.Success -> result
    is TdlResult.Failure -> throw TelegramException(code, message)
}

/** Single TDLib client for the whole app: authorization state, user and chat caches. */
class Telegram(private val context: Context, private val scope: CoroutineScope) {

    val client: TdlClient = TdlClient.create()

    val isConfigured: Boolean = BuildConfig.TELEGRAM_API_ID != 0 && BuildConfig.TELEGRAM_API_HASH.isNotBlank()

    private val _authState = MutableStateFlow<AuthorizationState?>(null)
    val authState: StateFlow<AuthorizationState?> = _authState.asStateFlow()

    private val _users = MutableStateFlow<Map<Long, User>>(emptyMap())
    val users: StateFlow<Map<Long, User>> = _users.asStateFlow()

    private val _chats = MutableStateFlow<Map<Long, Chat>>(emptyMap())

    // Chat objects are immutable snapshots, so the live video chat state is tracked on its own.
    private val _videoChats = MutableStateFlow<Map<Long, VideoChat>>(emptyMap())
    val videoChats: StateFlow<Map<Long, VideoChat>> = _videoChats.asStateFlow()

    init {
        // tdl-coroutines updates are not replayed: subscribe before the first request starts the receiver.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.authorizationStateUpdates.collect { onAuthorizationState(it.authorizationState) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.userUpdates.collect { update -> _users.update { it + (update.user.id to update.user) } }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.newChatUpdates.collect { update ->
                _chats.update { it + (update.chat.id to update.chat) }
                _videoChats.update { it + (update.chat.id to update.chat.videoChat) }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.chatVideoChatUpdates.collect { update -> _videoChats.update { it + (update.chatId to update.videoChat) } }
        }
        if (isConfigured) {
            scope.launch {
                client.getAuthorizationState()
            }
        }
    }

    private suspend fun onAuthorizationState(state: AuthorizationState) {
        Log.i(TAG, "Authorization state: ${state::class.simpleName}")
        if (state is AuthorizationStateWaitTdlibParameters) {
            val result = client.setTdlibParameters(
                useTestDc = false,
                databaseDirectory = context.filesDir.resolve("tdlib").absolutePath,
                filesDirectory = context.cacheDir.resolve("tdlib-files").absolutePath,
                databaseEncryptionKey = ByteArray(0),
                useFileDatabase = true,
                useChatInfoDatabase = true,
                useMessageDatabase = true,
                useSecretChats = false,
                apiId = BuildConfig.TELEGRAM_API_ID,
                apiHash = BuildConfig.TELEGRAM_API_HASH,
                systemLanguageCode = Locale.getDefault().language,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                systemVersion = "Android ${Build.VERSION.RELEASE}",
                applicationVersion = BuildConfig.VERSION_NAME,
            )
            if (result is TdlResult.Failure) Log.e(TAG, "setTdlibParameters failed: ${result.message}")
        }
        _authState.value = state
    }

    /** Logs out and destroys the local data; TDLib then reaches authorizationStateClosed and the app restarts. */
    suspend fun logOut() {
        val result = client.logOut()
        if (result is TdlResult.Failure) Log.e(TAG, "logOut failed: ${result.message}")
    }

    suspend fun user(userId: Long): User =
        _users.value[userId] ?: client.getUser(userId).getOrThrow().also { user ->
            _users.update { it + (user.id to user) }
        }

    suspend fun chat(chatId: Long): Chat =
        _chats.value[chatId] ?: client.getChat(chatId).getOrThrow().also { chat ->
            _chats.update { it + (chat.id to chat) }
        }

    /** Returns the local path of [file], downloading it first if needed. */
    suspend fun download(file: File): String? {
        if (file.local.isDownloadingCompleted) return file.local.path
        return when (val result = client.downloadFile(file.id, priority = 1, offset = 0, limit = 0, synchronous = true)) {
            is TdlResult.Success -> result.result.local.path.takeIf { result.result.local.isDownloadingCompleted }
            is TdlResult.Failure -> null
        }
    }

    companion object {
        private const val TAG = "Telegram"
    }
}
