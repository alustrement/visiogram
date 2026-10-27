package app.visiogram.calls

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.visiogram.telegram.Telegram
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.Call
import dev.g000sha256.tdl.dto.CallDiscardReason
import dev.g000sha256.tdl.dto.CallDiscardReasonDeclined
import dev.g000sha256.tdl.dto.CallDiscardReasonDisconnected
import dev.g000sha256.tdl.dto.CallDiscardReasonMissed
import dev.g000sha256.tdl.dto.CallProtocol
import dev.g000sha256.tdl.dto.CallServer
import dev.g000sha256.tdl.dto.CallServerTypeTelegramReflector
import dev.g000sha256.tdl.dto.CallServerTypeWebrtc
import dev.g000sha256.tdl.dto.CallStateDiscarded
import dev.g000sha256.tdl.dto.CallStateError
import dev.g000sha256.tdl.dto.CallStateExchangingKeys
import dev.g000sha256.tdl.dto.CallStateHangingUp
import dev.g000sha256.tdl.dto.CallStatePending
import dev.g000sha256.tdl.dto.CallStateReady
import dev.g000sha256.tdl.dto.UpdateNewCallSignalingData
import dev.g000sha256.tdl.dto.User
import io.github.pytgcalls.ConnectionState
import io.github.pytgcalls.NTgCalls
import io.github.pytgcalls.media.StreamDevice
import io.github.pytgcalls.media.StreamMode
import io.github.pytgcalls.media.StreamStatus
import io.github.pytgcalls.p2p.RTCServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.webrtc.VideoSink

enum class CallPhase { INCOMING, OUTGOING, CONNECTING, ACTIVE, ENDED }

data class CallUiState(
    val callId: Int,
    val userId: Long,
    val user: User?,
    val isOutgoing: Boolean,
    val isVideo: Boolean,
    val phase: CallPhase,
    val emojis: List<String> = emptyList(),
    val micMuted: Boolean = false,
    val cameraEnabled: Boolean = isVideo,
    val remoteVideoActive: Boolean = false,
    val remoteMicMuted: Boolean = false,
    val activeSinceMs: Long = 0L,
    val endReason: String? = null,
)

/**
 * Bridges TDLib private calls (signaling, key exchange, servers) with ntgcalls (WebRTC media).
 *
 * TDLib performs the DH exchange itself, so ntgcalls is fed the final key through `skipExchange`.
 * ntgcalls identifies a P2P call by the remote user id.
 */
class CallManager(
    private val context: Context,
    private val telegram: Telegram,
    private val scope: CoroutineScope,
) {
    private val tdl get() = telegram.client
    private val ntg = NTgCalls()
    private val videoDelay = VideoDelay("call-video")

    /** Set by the app: true while a group video chat is running, so incoming private calls get a busy reply. */
    var isOtherCallActive: () -> Boolean = { false }

    private val _state = MutableStateFlow<CallUiState?>(null)
    val state: StateFlow<CallUiState?> = _state.asStateFlow()

    @Volatile var remoteVideoSink: VideoSink? = null

    // Guarded by ntgMutex: the user id of the ntgcalls P2P call, and signaling received before it exists.
    private val ntgMutex = Mutex()
    private var ntgCallUserId: Long? = null
    private val pendingSignaling = ArrayList<ByteArray>()

    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null

    val protocol: CallProtocol by lazy {
        val p = NTgCalls.getProtocol()
        Log.i(TAG, "ntgcalls protocol layers ${p.min_layer}..${p.max_layer}, versions ${p.library_versions}")
        CallProtocol(
            udpP2p = p.udp_p2p,
            udpReflector = p.udp_reflector,
            minLayer = p.min_layer,
            maxLayer = p.max_layer,
            libraryVersions = p.library_versions.toTypedArray(),
        )
    }

    init {
        ntg.onSignalingData { userId, data ->
            val call = _state.value?.takeIf { it.userId == userId } ?: return@onSignalingData
            scope.launch { tdl.sendCallSignalingData(call.callId, data) }
        }
        ntg.onConnectionChange { userId, info ->
            Log.i(TAG, "ntgcalls connection $userId: ${info.state} (${info.kind})")
            when (info.state) {
                ConnectionState.CONNECTED -> _state.update {
                    it?.copy(phase = CallPhase.ACTIVE, activeSinceMs = it.activeSinceMs.takeIf { t -> t > 0 } ?: SystemClock.elapsedRealtime())
                }
                ConnectionState.FAILED, ConnectionState.TIMEOUT -> scope.launch { hangUp(disconnected = true) }
                else -> Unit
            }
        }
        ntg.onRemoteSourceChange { _, source ->
            Log.i(TAG, "Remote source ${source.device} -> ${source.state}")
            when (source.device) {
                StreamDevice.CAMERA, StreamDevice.SCREEN ->
                    _state.update { it?.copy(remoteVideoActive = source.state == StreamStatus.ACTIVE) }
                StreamDevice.MICROPHONE ->
                    _state.update { it?.copy(remoteMicMuted = source.state != StreamStatus.ACTIVE) }
                else -> Unit
            }
        }
        // The local preview comes straight from CameraSource; only the remote sound and picture arrive here.
        ntg.onFrames { _, mode, device, frames ->
            if (mode != StreamMode.PLAYBACK) return@onFrames
            when (device) {
                StreamDevice.MICROPHONE -> SpeakerSink.push(frames)
                StreamDevice.CAMERA, StreamDevice.SCREEN -> {
                    val sink = remoteVideoSink ?: return@onFrames
                    frames.lastOrNull()?.let { videoDelay.render(it, sink) }
                }
                else -> Unit
            }
        }

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            tdl.callUpdates.collect { onCallUpdate(it.call) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            tdl.newCallSignalingDataUpdates.collect { onSignalingData(it) }
        }
    }

    // region Public actions

    suspend fun startCall(userId: Long, isVideo: Boolean) {
        if (_state.value?.let { it.phase != CallPhase.ENDED } == true || isOtherCallActive()) return
        when (val result = tdl.createCall(userId, protocol, isVideo)) {
            is TdlResult.Success -> {
                // The pending updateCall usually arrives first; only fill in if it has not.
                if (_state.value?.callId != result.result.id) {
                    _state.value = CallUiState(result.result.id, userId, userOrNull(userId), isOutgoing = true, isVideo = isVideo, phase = CallPhase.OUTGOING)
                    startRingback()
                }
            }
            is TdlResult.Failure -> {
                Log.e(TAG, "createCall failed: ${result.message}")
                _state.value = CallUiState(0, userId, userOrNull(userId), isOutgoing = true, isVideo = isVideo, phase = CallPhase.ENDED, endReason = result.message)
                clearEndedLater(0)
            }
        }
    }

    suspend fun accept() {
        val call = _state.value?.takeIf { it.phase == CallPhase.INCOMING } ?: return
        stopRinging()
        _state.update { it?.copy(phase = CallPhase.CONNECTING) }
        val result = tdl.acceptCall(call.callId, protocol)
        if (result is TdlResult.Failure) Log.e(TAG, "acceptCall failed: ${result.message}")
    }

    suspend fun hangUp(disconnected: Boolean = false) {
        val call = _state.value?.takeIf { it.phase != CallPhase.ENDED } ?: return
        val duration = if (call.activeSinceMs > 0) ((SystemClock.elapsedRealtime() - call.activeSinceMs) / 1000).toInt() else 0
        val result = tdl.discardCall(
            callId = call.callId,
            isDisconnected = disconnected,
            inviteLink = "",
            duration = duration,
            isVideo = call.cameraEnabled,
            connectionId = 0,
        )
        // Normally the discarded updateCall finishes the call; make sure the UI does not hang if it never comes.
        if (result is TdlResult.Failure) finish(call.callId, result.message)
    }

    suspend fun toggleMute() {
        val call = _state.value ?: return
        val muted = !call.micMuted
        withNtg(call.userId) { if (muted) ntg.mute(it) else ntg.unmute(it) }
        _state.update { it?.copy(micMuted = muted) }
    }

    suspend fun toggleCamera() {
        val call = _state.value ?: return
        val enabled = !call.cameraEnabled
        withNtg(call.userId) {
            ntg.setStreamSources(it, StreamMode.CAPTURE, Media.captureDescription(enabled))
            if (enabled) Media.startCamera(this, ntg, it) else CameraSource.stop(this)
        }
        _state.update { it?.copy(cameraEnabled = enabled) }
    }

    // endregion

    private suspend fun onCallUpdate(call: Call) {
        Log.i(TAG, "Call ${call.id} (user ${call.userId}, video=${call.isVideo}, outgoing=${call.isOutgoing}): ${call.state::class.simpleName}")
        val current = _state.value
        val busy = isOtherCallActive() || (current != null && current.callId != call.id && current.phase != CallPhase.ENDED)
        if (busy && current?.callId != call.id) {
            // Already in a call: reject any other incoming call as busy.
            if (!call.isOutgoing && call.state is CallStatePending) {
                tdl.discardCall(call.id, isDisconnected = false, inviteLink = "", duration = 0, isVideo = call.isVideo, connectionId = 0)
            }
            return
        }

        when (val state = call.state) {
            is CallStatePending -> if (current?.callId != call.id) {
                _state.value = CallUiState(
                    callId = call.id,
                    userId = call.userId,
                    user = userOrNull(call.userId),
                    isOutgoing = call.isOutgoing,
                    isVideo = call.isVideo,
                    phase = if (call.isOutgoing) CallPhase.OUTGOING else CallPhase.INCOMING,
                )
                if (call.isOutgoing) startRingback() else startRinging()
            }
            is CallStateExchangingKeys -> {
                stopRinging()
                _state.update { it?.copy(phase = CallPhase.CONNECTING) }
            }
            is CallStateReady -> {
                stopRinging()
                _state.update {
                    it?.copy(
                        phase = if (it.phase == CallPhase.ACTIVE) CallPhase.ACTIVE else CallPhase.CONNECTING,
                        emojis = state.emojis.toList(),
                    )
                }
                startMedia(call, state)
            }
            is CallStateHangingUp -> Unit
            is CallStateDiscarded -> finish(call.id, state.reason.label())
            is CallStateError -> finish(call.id, state.error.message)
        }
    }

    private suspend fun startMedia(call: Call, ready: CallStateReady) = withContext(Dispatchers.IO) {
        ntgMutex.withLock {
            if (ntgCallUserId == call.userId) return@withLock
            try {
                Media.logDevices()
                ntg.createP2pCall(call.userId)
                ntgCallUserId = call.userId
                // ntgcalls queues signaling received between creation and connectP2p itself.
                pendingSignaling.forEach { ntg.sendSignalingData(call.userId, it) }
                pendingSignaling.clear()

                ntg.setStreamSources(call.userId, StreamMode.CAPTURE, Media.captureDescription(call.isVideo))
                Media.startMicrophone(this@CallManager, ntg, call.userId)
                if (call.isVideo) Media.startCamera(this@CallManager, ntg, call.userId)
                SpeakerSink.start(this@CallManager)
                ntg.setStreamSources(call.userId, StreamMode.PLAYBACK, Media.playbackDescription())
                ntg.skipExchange(call.userId, ready.encryptionKey, call.isOutgoing)
                ntg.connectP2p(
                    call.userId,
                    ready.servers.map { it.toRtcServer() },
                    ready.protocol.libraryVersions.toList(),
                    ready.allowP2p,
                    ready.customParameters.ifBlank { null },
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start media for call ${call.id}", e)
                scope.launch { hangUp(disconnected = true) }
            }
        }
    }

    private suspend fun onSignalingData(update: UpdateNewCallSignalingData) = withContext(Dispatchers.IO) {
        ntgMutex.withLock {
            val call = _state.value?.takeIf { it.callId == update.callId } ?: return@withLock
            if (ntgCallUserId == call.userId) {
                runCatching { ntg.sendSignalingData(call.userId, update.data) }
                    .onFailure { Log.w(TAG, "sendSignalingData failed", it) }
            } else {
                pendingSignaling += update.data
            }
        }
    }

    private suspend fun finish(callId: Int, reason: String?) {
        if (_state.value?.callId != callId) return
        stopRinging()
        stopMedia()
        _state.update { it?.copy(phase = CallPhase.ENDED, endReason = reason) }
        clearEndedLater(callId)
    }

    private fun clearEndedLater(callId: Int) {
        scope.launch {
            delay(2500)
            _state.update { if (it?.callId == callId && it.phase == CallPhase.ENDED) null else it }
        }
    }

    private suspend fun stopMedia() = withContext(Dispatchers.IO) {
        ntgMutex.withLock {
            CameraSource.stop(this@CallManager)
            MicSource.stop(this@CallManager)
            SpeakerSink.stop(this@CallManager)
            videoDelay.clear()
            ntgCallUserId?.let { userId -> runCatching { ntg.stop(userId) }.onFailure { Log.w(TAG, "stop failed", it) } }
            ntgCallUserId = null
            pendingSignaling.clear()
        }
    }

    private suspend fun withNtg(userId: Long, block: (Long) -> Unit) = withContext(Dispatchers.IO) {
        ntgMutex.withLock {
            if (ntgCallUserId == userId) runCatching { block(userId) }.onFailure { Log.w(TAG, "ntgcalls call failed", it) }
        }
    }

    private fun startRinging() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            play()
        }
    }

    /** Ringback tone while the other side has not answered, on the music stream so that it follows the TV volume. */
    private fun startRingback() {
        if (ringback != null) return
        ringback = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }
            .onFailure { Log.w(TAG, "Ringback tone unavailable", it) }
            .getOrNull()
            ?.apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) }
    }

    /** Stops both the incoming ringtone and the outgoing ringback tone. */
    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        ringback?.release()
        ringback = null
    }

    private suspend fun userOrNull(userId: Long): User? = runCatching { telegram.user(userId) }.getOrNull()

    private fun CallDiscardReason.label(): String? = when (this) {
        is CallDiscardReasonDeclined -> "Appel refusé"
        is CallDiscardReasonMissed -> "Appel manqué"
        is CallDiscardReasonDisconnected -> "Connexion perdue"
        else -> null
    }

    private fun CallServer.toRtcServer(): RTCServer = when (val type = type) {
        is CallServerTypeTelegramReflector ->
            RTCServer(id, ipAddress, ipv6Address, port, null, null, true, false, type.isTcp, type.peerTag)
        is CallServerTypeWebrtc ->
            RTCServer(id, ipAddress, ipv6Address, port, type.username, type.password, type.supportsTurn, type.supportsStun, false, null)
        else -> error("Unknown call server type $type")
    }

    companion object {
        private const val TAG = "CallManager"
    }
}
