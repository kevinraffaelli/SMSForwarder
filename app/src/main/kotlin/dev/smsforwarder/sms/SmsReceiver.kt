package dev.smsforwarder.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.di.AppContainer
import dev.smsforwarder.domain.Sms
import dev.smsforwarder.telegram.DeliveryStatus
import dev.smsforwarder.telegram.MessageFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SMS_RECEIVED receiver — the event-driven trigger for the whole app
 * (ProjectDescription.md: "No polling"). It is declared in the manifest, so
 * Android starts the process for every incoming SMS; no long-running service
 * is needed (Specification §9).
 *
 * The work runs in [AppContainer.appScope]; the broadcast is held via
 * [goAsync] for at most [BROADCAST_BUDGET_MS], so a slow network can't trip the
 * receiver time limit (a send still running then carries on in the scope).
 *
 * The receiver does NOT persist the SMS (Specification §8). It builds an
 * in-memory [Sms] and forwards it (or drops it). A failed send is dropped per
 * §7's no-buffering rule.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val from = messages[0].displayOriginatingAddress ?: messages[0].originatingAddress ?: ""
        val body = messages.joinToString(separator = "") { it.displayMessageBody ?: it.messageBody ?: "" }
        val timestamp = messages.maxOf { it.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis() }

        val container = (context.applicationContext as? SmsForwarderApp)?.container ?: return
        val sms = Sms(from = from, body = body, timestamp = timestamp)

        val pending = goAsync()
        val work = container.appScope.launch { forward(container, sms) }
        container.appScope.launch {
            try {
                withTimeoutOrNull(BROADCAST_BUDGET_MS) { work.join() }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun forward(container: AppContainer, sms: Sms) {
        try {
            val config = container.configRepository
            if (!config.forwardingEnabled.first()) return
            val token = config.telegramToken.first()
            val chatId = config.telegramChatId.first()
            if (token.isBlank() || chatId == null) {
                Log.i(TAG, "Telegram not set up; SMS dropped")
                return
            }
            val ruleId = container.ruleEngine.evaluate(
                rules = container.ruleRepository.current(),
                sms = sms,
                contacts = container.contactsResolver,
            )
            if (ruleId == null) {
                Log.v(TAG, "No rule matched")
                return
            }
            val text = MessageFormatter.compose(sms.from, sms.body, sms.timestamp)
            val result = container.telegramSender.send(token, chatId, text)
            container.deliveryStatus.record(result)
            Log.i(TAG, "Forward (rule=$ruleId): ${DeliveryStatus.describe(result)}")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Forwarding failed; SMS dropped", t)
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"

        /** Stay under the ~10 s limit for holding a broadcast via goAsync(). */
        private const val BROADCAST_BUDGET_MS = 9_000L
    }
}
