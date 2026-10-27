package app.visiogram.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import app.visiogram.telegram.Telegram
import app.visiogram.ui.components.QrCode
import app.visiogram.ui.components.TvTextField
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitOtherDeviceConfirmation
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitRegistration
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(state: AuthorizationState, telegram: Telegram) {
    val scope = rememberCoroutineScope()
    var error by remember(state) { mutableStateOf<String?>(null) }
    var busy by remember(state) { mutableStateOf(false) }

    fun submit(request: suspend () -> TdlResult<*>) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val result = request()
            if (result is TdlResult.Failure) error = result.message
            busy = false
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(64.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Visiogram", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(
                "Telegram sur votre appareil, avec les appels audio et vidéo.",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when (state) {
                is AuthorizationStateWaitPhoneNumber -> PhoneStep(
                    onQr = { submit { telegram.client.requestQrCodeAuthentication(longArrayOf()) } },
                    onPhone = { phone -> submit { telegram.client.setAuthenticationPhoneNumber(phone) } },
                )
                is AuthorizationStateWaitOtherDeviceConfirmation -> QrStep(state.link)
                is AuthorizationStateWaitCode -> CodeStep(
                    phoneNumber = state.codeInfo.phoneNumber,
                    onCode = { code -> submit { telegram.client.checkAuthenticationCode(code) } },
                )
                is AuthorizationStateWaitPassword -> PasswordStep(
                    hint = state.passwordHint,
                    onPassword = { password -> submit { telegram.client.checkAuthenticationPassword(password) } },
                )
                is AuthorizationStateWaitRegistration -> Text(
                    "Ce numéro n'a pas encore de compte Telegram. Créez-le d'abord depuis un téléphone.",
                    style = MaterialTheme.typography.titleMedium,
                )
                else -> Text("Connexion…", style = MaterialTheme.typography.titleMedium)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun PhoneStep(onQr: () -> Unit, onPhone: (String) -> Unit) {
    var phone by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Text("Connexion", style = MaterialTheme.typography.headlineMedium)
    Button(onClick = onQr, modifier = Modifier.focusRequester(focus)) {
        Text("Se connecter avec un QR code (recommandé)")
    }
    Spacer(Modifier.height(8.dp))
    Text("ou avec votre numéro de téléphone", color = MaterialTheme.colorScheme.onSurfaceVariant)
    TvTextField(
        value = phone,
        onValueChange = { phone = it },
        placeholder = "+33 6 12 34 56 78",
        keyboardType = KeyboardType.Phone,
        onDone = { if (phone.isNotBlank()) onPhone(phone) },
    )
    OutlinedButton(onClick = { if (phone.isNotBlank()) onPhone(phone) }) { Text("Recevoir le code") }
}

@Composable
private fun QrStep(link: String) {
    // Stacked: the login column is too narrow for the code and the instructions side by side.
    Text("Scannez ce code", style = MaterialTheme.typography.headlineMedium)
    QrCode(link, size = 260.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("1. Ouvrez Telegram sur votre téléphone", style = MaterialTheme.typography.bodyLarge)
        Text("2. Paramètres → Appareils → Connecter un appareil", style = MaterialTheme.typography.bodyLarge)
        Text("3. Visez ce QR code", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CodeStep(phoneNumber: String, onCode: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Text("Code de vérification", style = MaterialTheme.typography.headlineMedium)
    Text("Envoyé sur Telegram ou par SMS au $phoneNumber", color = MaterialTheme.colorScheme.onSurfaceVariant)
    TvTextField(code, { code = it }, "12345", Modifier.focusRequester(focus), KeyboardType.Number, onDone = { if (code.isNotBlank()) onCode(code) })
    Button(onClick = { if (code.isNotBlank()) onCode(code) }) { Text("Valider") }
}

@Composable
private fun PasswordStep(hint: String, onPassword: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Text("Mot de passe", style = MaterialTheme.typography.headlineMedium)
    Text("Validation en deux étapes activée" + if (hint.isNotBlank()) " (indice : $hint)" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
    TvTextField(password, { password = it }, "Mot de passe", Modifier.focusRequester(focus), password = true, onDone = { if (password.isNotEmpty()) onPassword(password) })
    Button(onClick = { if (password.isNotEmpty()) onPassword(password) }) { Text("Valider") }
}
