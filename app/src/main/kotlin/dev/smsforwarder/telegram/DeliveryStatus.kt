package dev.smsforwarder.telegram

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Outcome of the most recent Telegram send, shown in the Config screen.
 *
 * Process memory only — never persisted (Specification §8: no forwarding
 * outcomes, logs or history on disk) — and it never holds SMS content.
 */
class DeliveryStatus {

    sealed interface Last {
        data object None : Last
        data class Sent(val atMillis: Long) : Last
        data class Failed(val atMillis: Long, val reason: String) : Last
    }

    private val _last = MutableStateFlow<Last>(Last.None)
    val last: StateFlow<Last> = _last.asStateFlow()

    fun record(result: TelegramClient.Result<*>, nowMillis: Long = System.currentTimeMillis()) {
        _last.value = if (result is TelegramClient.Result.Ok) {
            Last.Sent(nowMillis)
        } else {
            Last.Failed(nowMillis, describe(result))
        }
    }

    companion object {
        /** Human-readable explanation of a Bot API outcome. */
        fun describe(result: TelegramClient.Result<*>): String = when (result) {
            is TelegramClient.Result.Ok -> "OK"
            is TelegramClient.Result.RetryAfter ->
                "Telegram rate limit (retry after ${result.seconds}s)"
            is TelegramClient.Result.Unauthorized ->
                "Bot token rejected — check it in @BotFather (${result.description})"
            is TelegramClient.Result.Forbidden ->
                "The bot can't message you — send it /start or unblock it (${result.description})"
            is TelegramClient.Result.BadRequest ->
                "Telegram refused the request (${result.description})"
            is TelegramClient.Result.NetworkError ->
                "Can't reach Telegram (${result.description})"
        }
    }
}
