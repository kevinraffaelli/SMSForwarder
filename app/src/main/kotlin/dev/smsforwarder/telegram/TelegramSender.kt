package dev.smsforwarder.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible

/**
 * Sends one forwarded SMS with at most one quick retry (Specification §6, §7).
 *
 * The text lives only in memory for the duration of this call — nothing is
 * queued or written to disk — so a send that still fails is dropped.
 */
class TelegramSender(
    private val sendOnce: (token: String, chatId: Long, text: String) -> TelegramClient.Result<Unit>,
) {

    suspend fun send(token: String, chatId: Long, text: String): TelegramClient.Result<Unit> {
        val first = attempt(token, chatId, text)
        val waitMs = when (first) {
            is TelegramClient.Result.NetworkError -> NETWORK_RETRY_DELAY_MS
            is TelegramClient.Result.RetryAfter -> first.seconds * 1_000L
            else -> return first
        }
        if (waitMs > MAX_RETRY_WAIT_MS) return first
        delay(waitMs)
        return attempt(token, chatId, text)
    }

    private suspend fun attempt(token: String, chatId: Long, text: String) =
        runInterruptible(Dispatchers.IO) { sendOnce(token, chatId, text) }

    companion object {
        const val NETWORK_RETRY_DELAY_MS = 1_000L

        /** Longer waits would outlive the SMS broadcast's time budget. */
        const val MAX_RETRY_WAIT_MS = 3_000L
    }
}
