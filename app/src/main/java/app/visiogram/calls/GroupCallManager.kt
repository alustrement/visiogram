package app.visiogram.calls

import android.util.Log
import app.visiogram.telegram.Telegram
import app.visiogram.telegram.TelegramException
import app.visiogram.telegram.getOrThrow
import app.visiogram.ui.components.displayName
import dev.g000sha256.tdl.dto.File
import dev.g000sha256.tdl.dto.GroupCall
import dev.g000sha256.tdl.dto.GroupCallJoinParameters
import dev.g000sha256.tdl.dto.GroupCallParticipant
import dev.g000sha256.tdl.dto.GroupCallParticipantVideoInfo
import dev.g000sha256.tdl.dto.MessageSender
import dev.g000sha256.tdl.dto.MessageSenderChat
import dev.g000sha256.tdl.dto.MessageSenderUser
import io.github.pytgcalls.ConnectionKind
import io.github.pytgcalls.ConnectionState
import io.github.pytgcalls.NTgCalls
import io.github.pytgcalls.media.StreamDevice
import io.github.pytgcalls.media.StreamMode
import io.github.pytgcalls.models.SsrcGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.VideoSink
import java.util.concurrent.ConcurrentHashMap

enum class GroupCallPhase { JOINING, ACTIVE, ENDED }

data class GroupParticipant(
    val key: String,
    val name: String,
    val photo: File?,
    val colorSeed: Long,
    val isCurrentUser: Boolean,
    val isMuted: Boolean,
    val isSpeaking: Boolean,
    /** ntgcalls ssrc of the participant's camera, once it is being received. */
    val videoSsrc: Long?,
    val videoPaused: Boolean,
    val screenSsrc: Long?,
)

data class GroupCallUiState(
    val chatId: Long,
    val groupCallId: Int,
    val title: String,
    val phase: GroupCallPhase,
    val cameraEnabled: Boolean,
    val participants: List<GroupParticipant> = emptyList(),
    val micMuted: Boolean = false,
    val canUnmute: Boolean = true,
    val canEndForAll: Boolean = false,
    val endReason: String? = null,
)

/**
 * Bridges TDLib video chats (create, join, participants) with ntgcalls group calls (WebRTC media).
 *
 * Join flow: ntgcalls `createCall` gives the join payload, TDLib `joinVideoChat` returns the server's
 * answer, which goes back to ntgcalls `connect`. Remote audio is picked up by ntgcalls automatically;
 * each remote camera or screen share must be subscribed to with `addIncomingVideo`.
 * ntgcalls identifies a group call by the chat id.
 */
class GroupCallManager(
    private val telegram: Telegram,
    private val scope: CoroutineScope,
) {
    private val tdl get() = telegram.client
    private val ntg = NTgCalls()
    private val videoDelay = VideoDelay("group-call-video")

    /** Set by the app: true while a private call is running. */
    var isOtherCallActive: () -> Boolean = { false }

    private val _state = MutableStateFlow<GroupCallUiState?>(null)
    val state: StateFlow<GroupCallUiState?> = _state.asStateFlow()

    val isActive: Boolean get() = _state.value?.phase.let { it == GroupCallPhase.JOINING || it == GroupCallPhase.ACTIVE }

    /** Video sinks of remote participants, by ntgcalls ssrc. Read from ntgcalls' frame thread. */
    private val sinks = ConcurrentHashMap<Long, VideoSink>()

    // Everything below is confined to `serial`: ntgcalls calls block, and TDLib updates race with the join.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private var ntgChatId: Long? = null
    private var connected = false
    private var joined = false
    private var ntgMuted = false
    private var myUserId = 0L
    private val participants = LinkedHashMap<String, GroupCallParticipant>()
    private val profiles = HashMap<String, Profile>()
    /** Subscribed remote videos: endpoint id → ssrc. */
    private val videoSsrcs = HashMap<String, Long>()

    private class Profile(val name: String, val photo: File?)

    init {
        ntg.onConnectionChange { chatId, info ->
            if (chatId != _state.value?.chatId || info.kind != ConnectionKind.NORMAL) return@onConnectionChange
            Log.i(TAG, "ntgcalls group connection $chatId: ${info.state}")
            when (info.state) {
                ConnectionState.CONNECTED -> _state.update { it?.copy(phase = GroupCallPhase.ACTIVE) }
                ConnectionState.FAILED, ConnectionState.TIMEOUT -> leave("Connexion perdue")
                else -> Unit
            }
        }
        // The local preview comes straight from CameraSource; only remote video is rendered from here.
        ntg.onFrames { _, mode, device, frames ->
            if (mode != StreamMode.PLAYBACK) return@onFrames
            when (device) {
                // One block per speaking participant, mixed by SpeakerSink.
                StreamDevice.MICROPHONE -> SpeakerSink.push(frames)
                // One callback can carry frames of several participants: render the latest of each.
                StreamDevice.CAMERA, StreamDevice.SCREEN -> frames.associateBy { it.ssrc and 0xFFFFFFFFL }.forEach { (ssrc, frame) ->
                    sinks[ssrc]?.let { videoDelay.render(frame, it) }
                }
                else -> Unit
            }
        }

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            tdl.groupCallParticipantUpdates.collect { update ->
                withContext(serial) { onParticipant(update.groupCallId, update.participant) }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            tdl.groupCallUpdates.collect { update -> withContext(serial) { onGroupCall(update.groupCall) } }
        }
    }

    // region Public actions

    /** Joins the chat's video chat, starting one first if none is running. */
    fun join(chatId: Long, withVideo: Boolean) {
        scope.launch(serial) {
            if (isActive || isOtherCallActive()) return@launch
            val chat = runCatching { telegram.chat(chatId) }.getOrNull()
            _state.value = GroupCallUiState(chatId, 0, chat?.title.orEmpty(), GroupCallPhase.JOINING, cameraEnabled = withVideo)
            try {
                var groupCallId = telegram.videoChats.value[chatId]?.groupCallId ?: chat?.videoChat?.groupCallId ?: 0
                if (groupCallId == 0) {
                    groupCallId = tdl.createVideoChat(chatId, title = "", startDate = 0, isRtmpStream = false).getOrThrow().id
                }
                _state.update { it?.copy(groupCallId = groupCallId) }
                // Participant updates are only sent once the call has been fetched.
                val groupCall = tdl.getGroupCall(groupCallId).getOrThrow()
                if (groupCall.isRtmpStream) throw UnsupportedCallException()
                myUserId = tdl.getMe().getOrThrow().id
                _state.update { it?.copy(canEndForAll = groupCall.canBeManaged) }

                Media.logDevices()
                val payload = ntg.createCall(chatId)
                ntgChatId = chatId
                ntg.setStreamSources(chatId, StreamMode.CAPTURE, Media.captureDescription(withVideo))
                Media.startMicrophone(this@GroupCallManager, ntg, chatId)
                if (withVideo) Media.startCamera(this@GroupCallManager, ntg, chatId)
                SpeakerSink.start(this@GroupCallManager)
                ntg.setStreamSources(chatId, StreamMode.PLAYBACK, Media.playbackDescription())

                val startMuted = groupCall.muteNewParticipants && !groupCall.canBeManaged
                val parameters = GroupCallJoinParameters(
                    audioSourceId = JSONObject(payload).getInt("ssrc"),
                    payload = payload,
                    isMuted = startMuted,
                    isMyVideoEnabled = withVideo,
                )
                val response = tdl.joinVideoChat(groupCallId, participantId = null, joinParameters = parameters, inviteHash = "").getOrThrow().text
                joined = true
                // Large broadcasts are served as MTProto segments instead of WebRTC; not supported yet.
                if (JSONObject(response).run { has("stream") || has("rtmp") }) throw UnsupportedCallException()
                ntg.connect(chatId, response, false)
                connected = true
                applyOwnMute(startMuted)
                _state.update { it?.copy(micMuted = startMuted, canUnmute = !startMuted) }
                syncVideos()
                tdl.loadGroupCallParticipants(groupCallId, limit = 100)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to join the video chat of $chatId", e)
                end(e.joinErrorLabel())
            }
        }
    }

    /** Leaves the video chat; [reason] is shown briefly when the call ended on its own. */
    fun leave(reason: String? = null) {
        scope.launch(serial) { end(reason) }
    }

    fun endForAll() {
        scope.launch(serial) {
            val call = _state.value?.takeIf { it.canEndForAll } ?: return@launch
            val result = runCatching { tdl.endGroupCall(call.groupCallId).getOrThrow() }
            result.onFailure { Log.w(TAG, "endGroupCall failed", it) }
            end(null)
        }
    }

    fun toggleMute() {
        scope.launch(serial) {
            val call = _state.value?.takeIf { it.phase == GroupCallPhase.ACTIVE } ?: return@launch
            val muted = !call.micMuted
            if (!muted && !call.canUnmute) return@launch
            val me = participants.values.firstOrNull { it.isCurrentUser }?.participantId ?: MessageSenderUser(myUserId)
            val result = runCatching { tdl.toggleGroupCallParticipantIsMuted(call.groupCallId, me, muted).getOrThrow() }
            result.onFailure { Log.w(TAG, "toggleGroupCallParticipantIsMuted failed", it) }
            if (result.isSuccess) {
                applyOwnMute(muted)
                _state.update { it?.copy(micMuted = muted) }
            }
        }
    }

    fun toggleCamera() {
        scope.launch(serial) {
            val call = _state.value?.takeIf { it.phase == GroupCallPhase.ACTIVE } ?: return@launch
            val enabled = !call.cameraEnabled
            runCatching { ntg.setStreamSources(call.chatId, StreamMode.CAPTURE, Media.captureDescription(enabled)) }
                .onFailure { Log.w(TAG, "setStreamSources failed", it); return@launch }
            if (enabled) Media.startCamera(this@GroupCallManager, ntg, call.chatId) else CameraSource.stop(this@GroupCallManager)
            tdl.toggleGroupCallIsMyVideoEnabled(call.groupCallId, enabled)
            _state.update { it?.copy(cameraEnabled = enabled) }
        }
    }

    fun setVideoSink(ssrc: Long, sink: VideoSink) {
        sinks[ssrc] = sink
    }

    fun removeVideoSink(ssrc: Long, sink: VideoSink) {
        sinks.remove(ssrc, sink)
    }

    // endregion

    private suspend fun onParticipant(groupCallId: Int, participant: GroupCallParticipant) {
        if (groupCallId != _state.value?.groupCallId) return
        val key = participant.participantId.key()
        // An empty order means the participant left the list.
        if (participant.order.isEmpty()) participants.remove(key) else participants[key] = participant
        if (key !in profiles) profiles[key] = profileOf(participant.participantId)

        if (participant.isCurrentUser && participant.order.isNotEmpty()) {
            val muted = participant.isMutedForAllUsers
            applyOwnMute(muted)
            _state.update { it?.copy(micMuted = muted, canUnmute = !muted || participant.canUnmuteSelf) }
        }
        syncVideos()
        publishParticipants()
    }

    private fun onGroupCall(groupCall: GroupCall) {
        val call = _state.value ?: return
        if (groupCall.id != call.groupCallId || call.phase == GroupCallPhase.ENDED) return
        _state.update { it?.copy(canEndForAll = groupCall.canBeManaged) }
        when {
            !groupCall.isActive -> {
                joined = false
                endInSerial("Le chat vidéo est terminé")
            }
            groupCall.needRejoin -> {
                Log.w(TAG, "Video chat ${groupCall.id} needs a rejoin")
                endInSerial("Connexion perdue, rejoignez le chat vidéo")
            }
        }
    }

    private fun endInSerial(reason: String) {
        scope.launch(serial) { end(reason) }
    }

    /** Subscribes to every remote camera and screen share, and drops the ones that went away. */
    private fun syncVideos() {
        val chatId = ntgChatId?.takeIf { connected } ?: return
        val wanted = HashMap<String, Pair<GroupCallParticipant, GroupCallParticipantVideoInfo>>()
        participants.values.filter { !it.isCurrentUser }.forEach { p ->
            p.videoInfo?.let { wanted[it.endpointId] = p to it }
            p.screenSharingVideoInfo?.let { wanted[it.endpointId] = p to it }
        }
        (videoSsrcs.keys - wanted.keys).forEach { endpoint ->
            runCatching { ntg.removeIncomingVideo(chatId, endpoint) }.onFailure { Log.w(TAG, "removeIncomingVideo failed", it) }
            videoSsrcs.remove(endpoint)
        }
        wanted.forEach { (endpoint, entry) ->
            if (endpoint in videoSsrcs) return@forEach
            val (participant, video) = entry
            val groups = video.sourceGroups.map { SsrcGroup(it.semantics, it.sourceIds.toList()) }
            runCatching { ntg.addIncomingVideo(chatId, participant.participantId.id(), endpoint, groups) }
                .onSuccess { ssrc -> if (ssrc != 0) videoSsrcs[endpoint] = ssrc.toLong() and 0xFFFFFFFFL }
                .onFailure { Log.w(TAG, "addIncomingVideo failed for $endpoint", it) }
        }
    }

    private fun publishParticipants() {
        val list = participants.entries
            .sortedWith(compareByDescending<Map.Entry<String, GroupCallParticipant>> { it.value.isCurrentUser }.thenByDescending { it.value.order })
            .map { (key, p) ->
                val profile = profiles[key]
                GroupParticipant(
                    key = key,
                    name = profile?.name.orEmpty(),
                    photo = profile?.photo,
                    colorSeed = p.participantId.id(),
                    isCurrentUser = p.isCurrentUser,
                    isMuted = p.isMutedForAllUsers || p.isMutedForCurrentUser,
                    isSpeaking = p.isSpeaking,
                    videoSsrc = p.videoInfo?.let { videoSsrcs[it.endpointId] },
                    videoPaused = p.videoInfo?.isPaused ?: false,
                    screenSsrc = p.screenSharingVideoInfo?.let { videoSsrcs[it.endpointId] },
                )
            }
        _state.update { it?.copy(participants = list) }
    }

    private fun applyOwnMute(muted: Boolean) {
        val chatId = ntgChatId ?: return
        if (muted == ntgMuted) return
        runCatching { if (muted) ntg.mute(chatId) else ntg.unmute(chatId) }
            .onSuccess { ntgMuted = muted }
            .onFailure { Log.w(TAG, "mute/unmute failed", it) }
    }

    private suspend fun end(reason: String?) {
        val call = _state.value ?: return
        if (call.phase == GroupCallPhase.ENDED) return
        if (joined) runCatching { tdl.leaveGroupCall(call.groupCallId) }
        CameraSource.stop(this)
        MicSource.stop(this)
        SpeakerSink.stop(this)
        videoDelay.clear()
        ntgChatId?.let { chatId -> runCatching { ntg.stop(chatId) }.onFailure { Log.w(TAG, "stop failed", it) } }
        ntgChatId = null
        connected = false
        joined = false
        ntgMuted = false
        participants.clear()
        videoSsrcs.clear()
        sinks.clear()

        if (reason == null) {
            _state.value = null
        } else {
            _state.value = call.copy(phase = GroupCallPhase.ENDED, endReason = reason, participants = emptyList())
            scope.launch {
                delay(2500)
                _state.update { if (it?.chatId == call.chatId && it.phase == GroupCallPhase.ENDED) null else it }
            }
        }
    }

    private suspend fun profileOf(sender: MessageSender): Profile = runCatching {
        when (sender) {
            is MessageSenderUser -> telegram.user(sender.userId).let { Profile(it.displayName, it.profilePhoto?.small) }
            is MessageSenderChat -> telegram.chat(sender.chatId).let { Profile(it.title, it.photo?.small) }
            else -> Profile("", null)
        }
    }.getOrElse { Profile("", null) }

    private fun MessageSender.id(): Long = when (this) {
        is MessageSenderUser -> userId
        is MessageSenderChat -> chatId
        else -> 0L
    }

    private fun MessageSender.key(): String = when (this) {
        is MessageSenderUser -> "user:$userId"
        is MessageSenderChat -> "chat:$chatId"
        else -> toString()
    }

    private class UnsupportedCallException : Exception("Diffusion en direct non prise en charge")

    private fun Exception.joinErrorLabel(): String = when {
        this is UnsupportedCallException -> "Les diffusions en direct ne sont pas encore prises en charge"
        this is TelegramException && (message.orEmpty().contains("ADMIN") || message.orEmpty().contains("FORBIDDEN")) ->
            "Seul un administrateur du groupe peut démarrer un chat vidéo"
        this is TelegramException -> "Impossible de rejoindre le chat vidéo ($message)"
        else -> "Impossible de rejoindre le chat vidéo"
    }

    companion object {
        private const val TAG = "GroupCallManager"
    }
}
