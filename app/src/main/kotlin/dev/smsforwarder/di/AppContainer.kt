package dev.smsforwarder.di

import android.content.Context
import dev.smsforwarder.contacts.ContactsResolver
import dev.smsforwarder.persistence.ConfigRepository
import dev.smsforwarder.persistence.RuleRepository
import dev.smsforwarder.rules.RuleEngine
import dev.smsforwarder.telegram.DeliveryStatus
import dev.smsforwarder.telegram.TelegramClient
import dev.smsforwarder.telegram.TelegramSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

interface AppContainer {
    val ruleRepository: RuleRepository
    val configRepository: ConfigRepository
    val ruleEngine: RuleEngine
    val contactsResolver: ContactsResolver
    val telegramClient: TelegramClient
    val telegramSender: TelegramSender
    val deliveryStatus: DeliveryStatus
    val appScope: CoroutineScope
}

class AppContainerImpl(private val appContext: Context) : AppContainer {

    override val appScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val configRepository: ConfigRepository =
        ConfigRepository(appContext)

    override val ruleRepository: RuleRepository =
        RuleRepository(appContext)

    override val ruleEngine: RuleEngine = RuleEngine()

    override val contactsResolver: ContactsResolver =
        ContactsResolver(appContext)

    override val telegramClient: TelegramClient = TelegramClient()

    override val telegramSender: TelegramSender =
        TelegramSender(telegramClient::sendMessage)

    override val deliveryStatus: DeliveryStatus = DeliveryStatus()
}
