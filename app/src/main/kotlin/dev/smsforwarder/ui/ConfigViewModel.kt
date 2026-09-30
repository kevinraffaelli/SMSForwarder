package dev.smsforwarder.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.smsforwarder.di.AppContainer
import dev.smsforwarder.domain.Predicate
import dev.smsforwarder.domain.Rule
import dev.smsforwarder.telegram.DeliveryStatus
import dev.smsforwarder.telegram.TelegramClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ConfigViewModel(
    private val container: AppContainer,
) : ViewModel() {

    val rules: StateFlow<List<Rule>> =
        container.ruleRepository.rules
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val forwardingEnabled: StateFlow<Boolean> =
        container.configRepository.forwardingEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val telegramToken: StateFlow<String> =
        container.configRepository.telegramToken
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val telegramChatId: StateFlow<Long?> =
        container.configRepository.telegramChatId
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val telegramChatLabel: StateFlow<String> =
        container.configRepository.telegramChatLabel
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val lastDelivery: StateFlow<DeliveryStatus.Last> = container.deliveryStatus.last

    // Setup feedback for the Telegram card — memory only, never persisted.
    private val _botUsername = MutableStateFlow<String?>(null)
    val botUsername: StateFlow<String?> = _botUsername.asStateFlow()

    private val _telegramNote = MutableStateFlow<String?>(null)
    val telegramNote: StateFlow<String?> = _telegramNote.asStateFlow()

    fun setForwardingEnabled(value: Boolean) = viewModelScope.launch {
        container.configRepository.setForwardingEnabled(value)
    }

    /** Saves the token, then validates it with `getMe` (Specification §4). */
    fun saveToken(token: String) = viewModelScope.launch {
        container.configRepository.setTelegramToken(token)
        val saved = container.configRepository.telegramToken.first()
        when (val r = withContext(Dispatchers.IO) { container.telegramClient.getMe(saved) }) {
            is TelegramClient.Result.Ok -> {
                _botUsername.value = r.value.username
                _telegramNote.value =
                    "Token OK. In Telegram, send /start to @${r.value.username}, then tap Detect chat."
            }
            else -> {
                _botUsername.value = null
                _telegramNote.value = DeliveryStatus.describe(r)
            }
        }
    }

    /** One-shot `getUpdates`: remembers the latest private chat that messaged the bot. */
    fun detectChat() = viewModelScope.launch {
        val token = container.configRepository.telegramToken.first()
        when (val r = withContext(Dispatchers.IO) { container.telegramClient.findLatestPrivateChat(token) }) {
            is TelegramClient.Result.Ok -> {
                val chat = r.value
                if (chat == null) {
                    _telegramNote.value =
                        "No message found. Send /start to your bot in Telegram, then tap Detect chat again."
                } else {
                    container.configRepository.setTelegramChat(chat.id, chat.label)
                    _telegramNote.value = "Messages will go to ${chat.label}. Tap Send test message."
                }
            }
            else -> _telegramNote.value = DeliveryStatus.describe(r)
        }
    }

    fun sendTest() = viewModelScope.launch {
        val token = container.configRepository.telegramToken.first()
        val chatId = container.configRepository.telegramChatId.first()
        if (chatId == null) {
            _telegramNote.value = "Detect your chat first."
            return@launch
        }
        val r = container.telegramSender.send(token, chatId, "SMS Forwarder test message: setup works.")
        container.deliveryStatus.record(r)
        _telegramNote.value =
            if (r is TelegramClient.Result.Ok) "Test message sent. Check Telegram." else DeliveryStatus.describe(r)
    }

    fun saveRules(rules: List<Rule>) = viewModelScope.launch {
        container.ruleRepository.setRules(rules)
    }

    fun addRule(name: String, predicate: Predicate) = viewModelScope.launch {
        val id = UUID.randomUUID().toString()
        val newRule = Rule(id = id, name = name, enabled = true, predicates = listOf(predicate))
        val updated = rules.value + newRule
        container.ruleRepository.setRules(updated)
    }

    fun deleteRule(id: String) = viewModelScope.launch {
        container.ruleRepository.setRules(rules.value.filterNot { it.id == id })
    }

    fun toggleRule(id: String) = viewModelScope.launch {
        container.ruleRepository.setRules(
            rules.value.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        )
    }
}

@Composable
fun rememberConfigViewModel(container: AppContainer): ConfigViewModel {
    return remember(container) { ConfigViewModel(container) }
}
