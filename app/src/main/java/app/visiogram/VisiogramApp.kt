package app.visiogram

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import app.visiogram.calls.CallManager
import app.visiogram.calls.CallPhase
import app.visiogram.calls.CallSettings
import app.visiogram.calls.CameraSource
import app.visiogram.calls.GroupCallManager
import app.visiogram.calls.Media
import app.visiogram.telegram.Telegram
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.webrtc.ContextUtils

class VisiogramApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var telegram: Telegram
        private set
    lateinit var calls: CallManager
        private set
    lateinit var groupCalls: GroupCallManager
        private set

    override fun onCreate() {
        super.onCreate()
        // ntgcalls' WebRTC capturers read the application context from here.
        ContextUtils.initialize(this)
        CallSettings.init(this)
        CameraSource.init(this)
        telegram = Telegram(this, scope)
        calls = CallManager(this, telegram, scope)
        groupCalls = GroupCallManager(telegram, scope)
        // One call at a time: a private call arriving during a video chat gets a busy reply, and vice versa.
        calls.isOtherCallActive = { groupCalls.isActive }
        groupCalls.isOtherCallActive = { calls.state.value?.let { it.phase != CallPhase.ENDED } == true }
        if (BuildConfig.DEBUG) scope.launch(Dispatchers.IO) { Media.logDevices() }

        // A closed TDLib client cannot be reused: after a logout (or a session revoked elsewhere),
        // restart the process so a fresh client shows the login screen.
        scope.launch {
            telegram.authState.collect { if (it is AuthorizationStateClosed) restart() }
        }
        scope.launch {
            calls.state.map { it?.phase == CallPhase.INCOMING }.distinctUntilChanged().collect { incoming ->
                if (incoming) bringToFront()
            }
        }
    }

    private fun restart() {
        startActivity(Intent.makeRestartActivityTask(ComponentName(this, MainActivity::class.java)))
        Runtime.getRuntime().exit(0)
    }

    private fun bringToFront() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { startActivity(intent) }
    }
}
