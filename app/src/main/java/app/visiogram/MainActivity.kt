package app.visiogram

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import app.visiogram.ui.CallScreen
import app.visiogram.ui.GroupCallScreen
import app.visiogram.ui.HomeScreen
import app.visiogram.ui.LoginScreen
import app.visiogram.ui.theme.VisiogramTheme
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut
import dev.g000sha256.tdl.dto.AuthorizationStateReady

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VisiogramApp
        setContent {
            VisiogramTheme {
                Root(app)
            }
        }
    }
}

@Composable
private fun Root(app: VisiogramApp) {
    val telegram = app.telegram
    val auth by telegram.authState.collectAsStateWithLifecycle()
    val call by app.calls.state.collectAsStateWithLifecycle()
    val groupCall by app.groupCalls.state.collectAsStateWithLifecycle()

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    KeepScreenOn(call != null || groupCall != null)
    // Kept here so that the home screen comes back on the same tab after a call.
    var homeTab by rememberSaveable { mutableIntStateOf(0) }

    // The root Surface provides the default content color for every Text below.
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RectangleShape,
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ),
    ) {
        val state = auth
        when {
            !telegram.isConfigured -> Message(
                "Identifiants Telegram manquants",
                "Renseignez telegram.apiId et telegram.apiHash dans local.properties (voir my.telegram.org), puis recompilez.",
            )
            state == null -> Message("Visiogram", "Connexion à Telegram…")
            state is AuthorizationStateLoggingOut || state is AuthorizationStateClosed -> Message("Visiogram", "Déconnexion…")
            // Not composed under a call screen: its lists would otherwise take the D-pad focus behind the call.
            state is AuthorizationStateReady -> if (call == null && groupCall == null) {
                HomeScreen(telegram, app.calls, app.groupCalls, tab = homeTab, onTabChange = { homeTab = it })
            }
            else -> LoginScreen(state, telegram)
        }

        groupCall?.let { GroupCallScreen(it, app.groupCalls, telegram) }
        call?.let { CallScreen(it, app.calls, telegram) }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    LaunchedEffect(enabled) {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
private fun Message(title: String, body: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text(body, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        }
    }
}
