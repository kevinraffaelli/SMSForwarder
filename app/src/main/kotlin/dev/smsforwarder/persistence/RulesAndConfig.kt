package dev.smsforwarder.persistence

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Keys for the rules & config store (Specification §8). */
private object Keys {
    val Rules = stringPreferencesKey("rules_json")
    val ForwardingEnabled = booleanPreferencesKey("forwarding_enabled")
    val TelegramToken = stringPreferencesKey("telegram_token")
    val TelegramChatId = longPreferencesKey("telegram_chat_id")
    val TelegramChatLabel = stringPreferencesKey("telegram_chat_label")
    val Theme = stringPreferencesKey("theme")
}

private val Context.rulesStore by preferencesDataStore(name = "rules")
private val Context.configStore by preferencesDataStore(name = "config")

/** JSON encoder used by both repositories. */
internal val rulesJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

/**
 * State-backed application configuration (Specification §8: rules + config,
 * no message history). Two DataStore files because rules are large/JSON and
 * the config keys are small and primitive — keeps file contention low.
 */
class RuleRepository(context: Context) {

    private val store = context.rulesStore

    val rules: Flow<List<dev.smsforwarder.domain.Rule>> =
        store.data.map { p ->
            val raw = p[Keys.Rules] ?: "[]"
            rulesJson.decodeFromString(ListSerializer(dev.smsforwarder.domain.Rule.serializer()), raw)
        }

    suspend fun setRules(rules: List<dev.smsforwarder.domain.Rule>) {
        store.edit { p ->
            p[Keys.Rules] = rulesJson.encodeToString(ListSerializer(dev.smsforwarder.domain.Rule.serializer()), rules)
        }
    }

    suspend fun current(): List<dev.smsforwarder.domain.Rule> = rules.first()
}

class ConfigRepository(context: Context) {

    private val store = context.configStore

    /** Master on/off switch for forwarding (checked by the SMS receiver). */
    val forwardingEnabled: Flow<Boolean> =
        store.data.map { it[Keys.ForwardingEnabled] ?: false }

    /** Bot token from @BotFather (Specification §3). Kept out of backups (§8). */
    val telegramToken: Flow<String> =
        store.data.map { it[Keys.TelegramToken] ?: "" }

    /** The owner's private chat with the bot; null until detected (§4). */
    val telegramChatId: Flow<Long?> =
        store.data.map { it[Keys.TelegramChatId] }

    /** Display name of that chat, for the UI only. */
    val telegramChatLabel: Flow<String> =
        store.data.map { it[Keys.TelegramChatLabel] ?: "" }

    val theme: Flow<String> =
        store.data.map { it[Keys.Theme] ?: "system" }

    suspend fun setForwardingEnabled(value: Boolean) =
        store.edit { p -> p[Keys.ForwardingEnabled] = value }

    suspend fun setTelegramToken(value: String) =
        store.edit { p -> p[Keys.TelegramToken] = value.trim() }

    suspend fun setTelegramChat(id: Long, label: String) =
        store.edit { p ->
            p[Keys.TelegramChatId] = id
            p[Keys.TelegramChatLabel] = label
        }

    suspend fun setTheme(value: String) =
        store.edit { p -> p[Keys.Theme] = value }
}
