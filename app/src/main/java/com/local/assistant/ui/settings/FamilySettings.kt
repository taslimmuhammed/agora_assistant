package com.local.assistant.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.live.LiveApi
import com.local.assistant.live.LiveError
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassShapes
import com.local.assistant.ui.theme.glassFieldColors
import com.local.assistant.wake.WakeWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A contact picked in the phone's contacts: their name and the number chosen. */
private data class Picked(val name: String, val number: String)

/** The roles someone can be added as, and how they're named here. */
private val ROLES = listOf("family" to "Family", "caregiver" to "Looks after her", "provider" to "Shop or service")

/** Marks the contacts picker as adding someone new rather than changing someone's number. */
private const val NEW = "\u0000new"

/**
 * Who Luna messages on WhatsApp for her: each one's number picked from the phone's contacts (no
 * contacts permission: the picker hands over only the one chosen) and kept on Luna's server, so
 * it's there for every call after. New people (a neighbour, the chemist) are added the same way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FamilyOnWhatsApp(settings: SettingsStore, api: LiveApi, configured: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var family by remember { mutableStateOf<List<LiveApi.Member>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pickingFor by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<Picked?>(null) }
    var removing by remember { mutableStateOf<LiveApi.Member?>(null) }
    var hello by remember { mutableStateOf(settings.lunaSaysHello) }

    /** Runs [block] against the server; what it returns is said under the list. */
    fun ask(doing: String, block: suspend () -> String) {
        status = doing
        scope.launch {
            status = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LiveError) {
                e.message
            } catch (e: Exception) {
                "That didn't work: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Puts [saved] in the list in place of whoever it was, keeping what the save found out. */
    fun keep(saved: LiveApi.Member) {
        family = if (family.any { it.id == saved.id }) family.map { if (it.id == saved.id) saved else it } else family + saved
    }

    // [configured] comes from the page above, so this follows the server field as it's typed in.
    LaunchedEffect(configured) {
        if (configured && !loaded) {
            try {
                family = api.family().members
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = (e as? LiveError)?.message ?: "Couldn't load the family."
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val forId = pickingFor ?: return@rememberLauncherForActivityResult
        pickingFor = null
        val picked = result.data?.data?.let { pickedAt(context, it) } ?: return@rememberLauncherForActivityResult
        if (forId == NEW) {
            adding = picked
            return@rememberLauncherForActivityResult
        }
        val member = family.firstOrNull { it.id == forId } ?: return@rememberLauncherForActivityResult
        ask("Saving ${member.name}'s number…") {
            val saved = api.setPhone(member.id, picked.number, introduce = hello)
            keep(saved)
            outcome(saved)
        }
    }
    fun pickFor(id: String) {
        pickingFor = id
        pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Family on WhatsApp", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
        Text(
            when {
                !configured -> "Add the server above first."
                family.isEmpty() -> "Who ${WakeWord.NAME} messages for you. Load the family, then pick each one's number from your contacts."
                else -> "Pick each one's number from your contacts. It's kept on ${WakeWord.NAME}'s server for every call after."
            },
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
    }
    family.forEach { member ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val what = listOfNotNull(member.relation?.takeIf { it.isNotBlank() }, "shop or service".takeIf { member.role == "provider" && member.relation.isNullOrBlank() })
                Text(
                    member.name + what.joinToString("") { " · $it" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary,
                )
                Text(
                    when {
                        member.phone.isNullOrBlank() -> "No number yet"
                        member.onWhatsApp == false -> "${member.phone} · not on WhatsApp"
                        else -> member.phone
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (member.onWhatsApp == false || member.phone.isNullOrBlank()) AppColors.Danger else AppColors.TextSecondary,
                )
            }
            if (member.added) TextButton(onClick = { removing = member }) { Text("Remove", color = AppColors.TextSecondary) }
            OutlinedButton(onClick = { pickFor(member.id) }) { Text(if (member.phone.isNullOrBlank()) "Pick" else "Change") }
        }
    }
    FlowRow(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(enabled = configured, onClick = { pickFor(NEW) }) { Text("Add from contacts") }
        OutlinedButton(enabled = configured, onClick = {
            ask("Fetching the family…") {
                family = api.family().members
                loaded = true
                "Family loaded: ${family.count { it.reachable }} of ${family.size} can get WhatsApp messages."
            }
        }) { Text(if (family.isEmpty()) "Load family" else "Refresh") }
    }
    SwitchRow(
        "${WakeWord.NAME} says hello",
        "When a number is added, ${WakeWord.NAME} sends them a WhatsApp message saying who she is and how to answer.",
        checked = hello,
        onCheckedChange = {
            hello = it
            settings.lunaSaysHello = it
        },
    )
    status?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }

    adding?.let { picked ->
        AddPersonDialog(
            picked = picked,
            onDismiss = { adding = null },
            onAdd = { name, relation, role ->
                adding = null
                ask("Adding $name…") {
                    val added = api.addMember(name, relation, role, picked.number, introduce = hello)
                    keep(added)
                    outcome(added)
                }
            },
        )
    }
    removing?.let { member ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${member.name}?") },
            text = { Text("${WakeWord.NAME} won't message them any more. Their contact stays on your phone.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    ask("Removing ${member.name}…") {
                        api.removeMember(member.id)
                        family = family.filterNot { it.id == member.id }
                        "${member.name} is removed."
                    }
                }) { Text("Remove", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** Who someone picked from the contacts is to her, before they're added. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddPersonDialog(picked: Picked, onDismiss: () -> Unit, onAdd: (name: String, relation: String, role: String) -> Unit) {
    var name by remember { mutableStateOf(picked.name) }
    var relation by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("family") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${picked.name.ifBlank { picked.number }}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(picked.number, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = GlassShapes.Field,
                    colors = glassFieldColors(),
                )
                OutlinedTextField(
                    value = relation,
                    onValueChange = { relation = it },
                    label = { Text(if (role == "provider") "What they are (chemist, grocery shop)" else "Who they are to her (daughter, neighbour)") },
                    singleLine = true,
                    shape = GlassShapes.Field,
                    colors = glassFieldColors(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in ROLES) {
                        FilterChip(
                            selected = value == role,
                            onClick = { role = value },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Glass.Raised,
                                labelColor = AppColors.TextPrimary,
                                selectedContainerColor = AppColors.Accent,
                                selectedLabelColor = AppColors.OnAccent,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onAdd(name.trim(), relation.trim(), role) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What a save found out, said plainly. */
private fun outcome(saved: LiveApi.Member): String = buildString {
    append("${saved.name}'s number is saved.")
    when {
        saved.onWhatsApp == false -> append(" It isn't on WhatsApp, so ${WakeWord.NAME} can't message it.")
        saved.introduced -> append(" ${WakeWord.NAME} said hello to them on WhatsApp.")
        saved.onWhatsApp == null -> append(" WhatsApp isn't signed in on the server, so it couldn't be checked.")
    }
}

/** The name and number of the phone entry the contacts picker returned; the pick itself grants the read. */
private fun pickedAt(context: Context, uri: Uri): Picked? = runCatching {
    val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
    context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null
        val number = c.getString(0)?.trim().orEmpty()
        if (number.isEmpty()) null else Picked(name = c.getString(1)?.trim().orEmpty(), number = number)
    }
}.getOrNull()
