package app.vinkje.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.vinkje.R
import app.vinkje.deck.LoginCredentials
import app.vinkje.deck.LoginFlow

private const val SCHEME = "https://"

/**
 * Nextcloud Login Flow v2: the browser does the Authentik login, we poll for the app password.
 * With [onCancel] it is the "log in again" screen, opened from the home screen.
 */
@Composable
fun LoginScreen(onLoggedIn: suspend (LoginCredentials) -> Unit, initialServer: String? = null, onCancel: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf(initialServer ?: SCHEME) }
    var waiting by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun start() {
        error = null
        waiting = scope.launch {
            try {
                val flow = LoginFlow(userAgent = "Taken (Android)")
                val started = withContext(Dispatchers.IO) { flow.start(server) }
                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(started.login))
                val deadline = System.currentTimeMillis() + 20 * 60 * 1000
                while (System.currentTimeMillis() < deadline) {
                    delay(2000)
                    val creds = runCatching { withContext(Dispatchers.IO) { flow.poll(started.poll) } }.getOrNull()
                    if (creds != null) {
                        onLoggedIn(creds)
                        return@launch
                    }
                }
                error = "Inloggen duurde te lang. Probeer het opnieuw."
            } catch (e: Exception) {
                error = "Kan ${server.removePrefix("https://")} niet bereiken. Klopt het adres, en staat je VPN aan?"
            } finally {
                waiting = null
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Text(
                if (onCancel == null) "Log in met je Nextcloud-account."
                else "Log opnieuw in met je Nextcloud-account. Wat je intussen hebt gewijzigd, gaat daarna alsnog naar Nextcloud.",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (waiting == null) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it.trim() },
                    label = { Text("Server") },
                    placeholder = { Text("https://cloud.example.org") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = ::start, enabled = server.length > SCHEME.length, modifier = Modifier.fillMaxWidth()) { Text("Inloggen") }
                if (onCancel != null) TextButton(onClick = onCancel) { Text("Later") }
            } else {
                CircularProgressIndicator()
                Text("Rond het inloggen af in de browser en kom dan hier terug.")
                TextButton(onClick = { waiting?.cancel() }) { Text("Annuleren") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
