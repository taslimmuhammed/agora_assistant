package com.local.assistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.local.assistant.BuildConfig
import com.local.assistant.data.prefs.SecretStore
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.live.CheckIn
import com.local.assistant.live.CheckIns
import com.local.assistant.live.LiveCalls
import com.local.assistant.live.LiveError
import com.local.assistant.ui.live.IncomingCallActivity
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.GlassShapes
import com.local.assistant.ui.theme.glassFieldColors
import com.local.assistant.wake.WakeWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** How the main page sums up Live voice. */
internal fun liveSummary(settings: SettingsStore, calls: LiveCalls): String = when {
    !calls.api.configured -> "Off · needs ${WakeWord.NAME}'s server"
    !settings.liveVoice -> "Off · the assistant runs on the phone"
    settings.checkInTimes.isEmpty() -> "On · calls through Agora when online"
    else -> "On · ${settings.checkInTimes.size} check-in calls a day"
}

/**
 * Live voice (Agora): the power button, "Hey Luna" and the chat's Call button start a call with
 * Luna's server instead of the phone's own model, while the phone is online, and Luna rings at
 * each medicine's time. The server, its key and the voice are set here, over the build's own; the
 * demo buttons drive the hackathon story without waiting for 9 o'clock.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveSettings(settings: SettingsStore, secrets: SecretStore, calls: LiveCalls, checkIns: CheckIns) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(settings.liveVoice) }
    var server by remember { mutableStateOf(settings.liveServerUrl.orEmpty()) }
    var key by remember { mutableStateOf(secrets.get(SecretStore.LIVE_APP_KEY).orEmpty()) }
    var voice by remember { mutableStateOf(settings.murfVoice.orEmpty()) }
    var doses by remember { mutableStateOf(settings.checkInTimes) }
    var status by remember { mutableStateOf<String?>(null) }

    /** Runs [block] against the server, saying how it went under the buttons. */
    fun ask(doing: String, done: String, block: suspend () -> Unit) {
        status = doing
        scope.launch {
            status = try {
                block()
                done
            } catch (e: CancellationException) {
                throw e
            } catch (e: LiveError) {
                e.message
            } catch (e: Exception) {
                "That didn't work: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    Text(
        "Talk to ${WakeWord.NAME} as on a phone call, in Indian English, with the family hub behind her: she can tell your " +
            "family, and answer from what they've said. The call goes through Agora to ${WakeWord.NAME}'s server and a cloud " +
            "model; what's done on the phone (reminders, lists, alarms) is done here as in a chat, with Undo. Offline, or " +
            "with this off, everything stays on the phone as before.",
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    SwitchRow(
        "Live voice (Agora)",
        when {
            !calls.api.configured -> "Add the server below to turn it on."
            on -> "On: the power button, “${WakeWord.PHRASE}” and Call in the chat start a call when online."
            else -> "Off: the assistant runs on the phone."
        },
        checked = on && calls.api.configured,
        onCheckedChange = { checked ->
            on = checked
            settings.liveVoice = checked
            // Check-ins ring only while this is on.
            checkIns.schedule()
        },
    )
    Field(
        title = "Server",
        hint = BuildConfig.LIVE_SERVER_URL.ifBlank { "https://luna.example.com" },
        value = server,
        keyboard = KeyboardType.Uri,
        onChange = {
            server = it
            settings.liveServerUrl = it
        },
    )
    Field(
        title = "App key",
        hint = if (BuildConfig.LIVE_APP_KEY.isNotBlank()) "The build's own" else "The server's APP_KEY",
        value = key,
        secret = true,
        onChange = {
            key = it
            secrets.put(SecretStore.LIVE_APP_KEY, it)
        },
    )
    Field(
        title = "Murf voice",
        hint = "The server's (Anusha)",
        value = voice,
        onChange = {
            voice = it
            settings.murfVoice = it
        },
    )

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Check-in calls", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
        Text(
            if (doses.isEmpty()) {
                "${WakeWord.NAME} rings at each medicine's time the family set in the hub. Refresh to fetch them."
            } else {
                doses.joinToString("\n") { "${it.time} · ${it.name}" + (it.detail?.let { detail -> " ($detail)" } ?: "") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
    }
    FlowRow(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(enabled = calls.api.configured, onClick = {
            ask("Fetching the medicines…", "Check-ins set from the family hub.") { doses = checkIns.refresh() }
        }) { Text("Refresh check-ins") }
    }

    Text(
        "Demo",
        style = MaterialTheme.typography.titleSmall,
        color = AppColors.TextPrimary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
    )
    FlowRow(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Rings here and now, as the 9:00 alarm would: no server needed to see the screen.
        OutlinedButton(onClick = {
            val checkIn = doses.firstOrNull() ?: SAMPLE
            CheckIns.ring(context, checkIn)
            // The full screen opens by itself only on a locked or sleeping phone; here it's in use.
            context.startActivity(IncomingCallActivity.intent(context, checkIn))
        }) { Text("Simulate check-in") }
        OutlinedButton(enabled = calls.api.configured, onClick = {
            ask("Telling the server…", "It's 9:30 on the server: the dose is missed and the family told.") { calls.api.demo("missed-dose") }
        }) { Text("Skip to 9:30") }
        OutlinedButton(enabled = calls.api.configured, onClick = {
            ask("Resetting…", "The demo family is as it was at the start.") { calls.api.demo("reset") }
        }) { Text("Reset demo") }
    }
    status?.let {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
    }
}

/** A line of text saved as it's typed: each is read only when a call starts. */
@Composable
private fun Field(
    title: String,
    hint: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.trim()) },
            placeholder = { Text(hint) },
            singleLine = true,
            shape = GlassShapes.Field,
            colors = glassFieldColors(),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/** For "Simulate check-in" before the hub's medicines are fetched. */
private val SAMPLE = CheckIn(doseId = "demo-dose", name = "BP tablet", detail = "Amlodipine 5 mg", time = "09:00")
