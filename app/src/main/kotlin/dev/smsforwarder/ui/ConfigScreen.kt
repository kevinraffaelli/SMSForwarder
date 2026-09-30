package dev.smsforwarder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.smsforwarder.domain.Predicate
import dev.smsforwarder.domain.Rule
import dev.smsforwarder.telegram.DeliveryStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * State of the one-time on-site setup that lets the phone run unattended
 * (Specification §9). Computed by [ConfigActivity] on every resume.
 */
data class SetupChecklist(
    val smsPermission: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    /** "Pause app activity if unused" is off; null when the device has no such setting. */
    val unusedAppRestrictionsOff: Boolean? = null,
    /** Android 15+ blocks SMS permissions for sideloaded apps until "Allow restricted settings". */
    val showRestrictedSettingsHint: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel,
    checklist: SetupChecklist,
    onRequestSms: () -> Unit,
    onRequestContacts: () -> Unit,
    onRequestBatteryOpt: () -> Unit,
    onManageUnusedAppRestrictions: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    val forwardingEnabled by viewModel.forwardingEnabled.collectAsStateWithLifecycle()
    val token by viewModel.telegramToken.collectAsStateWithLifecycle()
    val chatId by viewModel.telegramChatId.collectAsStateWithLifecycle()
    val chatLabel by viewModel.telegramChatLabel.collectAsStateWithLifecycle()
    val botUsername by viewModel.botUsername.collectAsStateWithLifecycle()
    val note by viewModel.telegramNote.collectAsStateWithLifecycle()
    val lastDelivery by viewModel.lastDelivery.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("SMS Forwarder") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    configured = token.isNotBlank() && chatId != null,
                    forwardingEnabled = forwardingEnabled,
                    chatLabel = chatLabel,
                    lastDelivery = lastDelivery,
                    onToggle = viewModel::setForwardingEnabled,
                )
            }
            item {
                TelegramCard(
                    savedToken = token,
                    botUsername = botUsername,
                    chatConfigured = chatId != null,
                    chatLabel = chatLabel,
                    note = note,
                    onSaveToken = { viewModel.saveToken(it) },
                    onDetectChat = { viewModel.detectChat() },
                    onSendTest = { viewModel.sendTest() },
                )
            }
            item {
                SetupChecklistCard(
                    checklist = checklist,
                    onRequestSms = onRequestSms,
                    onRequestBatteryOpt = onRequestBatteryOpt,
                    onManageUnusedAppRestrictions = onManageUnusedAppRestrictions,
                    onOpenAppInfo = onOpenAppInfo,
                    onRequestContacts = onRequestContacts,
                )
            }
            item {
                Text("Rules", style = MaterialTheme.typography.titleMedium)
            }
            items(rules, key = { it.id }) { rule ->
                RuleCard(
                    rule = rule,
                    onToggle = viewModel::toggleRule,
                    onDelete = viewModel::deleteRule,
                )
            }
            item {
                QuickAddRuleCard(onAdd = { name, pred -> viewModel.addRule(name, pred) })
            }
        }
    }
}

@Composable
private fun StatusCard(
    configured: Boolean,
    forwardingEnabled: Boolean,
    chatLabel: String,
    lastDelivery: DeliveryStatus.Last,
    onToggle: (Boolean) -> Unit,
) {
    val text = when {
        !configured -> "Not set up: connect Telegram below"
        !forwardingEnabled -> "Paused"
        else -> "Forwarding to ${chatLabel.ifBlank { "your Telegram chat" }}"
    }
    val last = when (lastDelivery) {
        DeliveryStatus.Last.None -> null
        is DeliveryStatus.Last.Sent -> "Last send OK at ${clock(lastDelivery.atMillis)}"
        is DeliveryStatus.Last.Failed -> "Last send failed at ${clock(lastDelivery.atMillis)}: ${lastDelivery.reason}"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (last != null) Text(last, style = MaterialTheme.typography.bodySmall)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = forwardingEnabled,
                    onCheckedChange = onToggle,
                    enabled = configured || forwardingEnabled,
                )
                Text("Forward SMS")
            }
        }
    }
}

@Composable
private fun TelegramCard(
    savedToken: String,
    botUsername: String?,
    chatConfigured: Boolean,
    chatLabel: String,
    note: String?,
    onSaveToken: (String) -> Unit,
    onDetectChat: () -> Unit,
    onSendTest: () -> Unit,
) {
    var token by remember(savedToken) { mutableStateOf(savedToken) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Telegram bot", style = MaterialTheme.typography.titleSmall)
            Text(
                "1. In Telegram, open @BotFather, send /newbot and copy the token it gives you.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bot token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(onClick = { onSaveToken(token) }, enabled = token.isNotBlank()) {
                Text("Save & check token")
            }
            if (botUsername != null) Text("Bot: @$botUsername", style = MaterialTheme.typography.bodyMedium)
            Text(
                "2. Send /start to your bot from your own Telegram account, then tap Detect chat.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDetectChat, enabled = savedToken.isNotBlank()) { Text("Detect chat") }
                Button(onClick = onSendTest, enabled = chatConfigured) { Text("Send test message") }
            }
            if (chatConfigured) Text("Chat: $chatLabel", style = MaterialTheme.typography.bodyMedium)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SetupChecklistCard(
    checklist: SetupChecklist,
    onRequestSms: () -> Unit,
    onRequestBatteryOpt: () -> Unit,
    onManageUnusedAppRestrictions: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onRequestContacts: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Unattended setup", style = MaterialTheme.typography.titleSmall)
            ChecklistRow("SMS permission", checklist.smsPermission, "Grant", onRequestSms)
            ChecklistRow("Battery: Unrestricted", checklist.batteryUnrestricted, "Open", onRequestBatteryOpt)
            checklist.unusedAppRestrictionsOff?.let { off ->
                ChecklistRow("\"Pause app activity if unused\" is off", off, "Open", onManageUnusedAppRestrictions)
            }
            if (checklist.showRestrictedSettingsHint && !checklist.smsPermission) {
                Text(
                    "If Android blocks the SMS permission: App info → ⋮ → Allow restricted settings, then grant it again.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onOpenAppInfo) { Text("App info") }
            }
            Text(
                "Also remove the SIM PIN and the screen lock on this phone, so forwarding resumes " +
                    "on its own after a reboot.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRequestContacts) { Text("Grant contacts (only for sender-name rules)") }
        }
    }
}

@Composable
private fun ChecklistRow(label: String, ok: Boolean, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = if (ok) "Done" else "Needs attention",
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!ok) TextButton(onClick = onAction) { Text(action) }
    }
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun clock(millis: Long): String =
    clockFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

@Composable
private fun RuleCard(
    rule: Rule,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${rule.predicates.size} predicate(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = { onToggle(rule.id) })
            IconButton(onClick = { onDelete(rule.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun QuickAddRuleCard(onAdd: (String, Predicate) -> Unit) {
    var name by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Quick add: keyword rule", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Rule name") }, singleLine = true,
            )
            OutlinedTextField(
                value = keyword, onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Keyword") }, singleLine = true,
            )
            Button(
                onClick = {
                    if (name.isNotBlank() && keyword.isNotBlank()) {
                        onAdd(name, Predicate.ContainsKeyword(keyword))
                        name = ""
                        keyword = ""
                    }
                },
            ) { Text("Add rule") }
        }
    }
}
