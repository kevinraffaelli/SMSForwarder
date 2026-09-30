package dev.smsforwarder.telegram

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramSenderTest {

    private fun senderReturning(vararg results: TelegramClient.Result<Unit>): Pair<TelegramSender, () -> Int> {
        val queue = ArrayDeque(results.toList())
        var calls = 0
        val sender = TelegramSender { _, _, _ ->
            calls++
            queue.removeFirst()
        }
        return sender to { calls }
    }

    @Test
    fun retriesOnceAfterNetworkError() = runTest {
        val (sender, calls) = senderReturning(
            TelegramClient.Result.NetworkError("timeout"),
            TelegramClient.Result.Ok(Unit),
        )

        assertEquals(TelegramClient.Result.Ok(Unit), sender.send("t", 1, "x"))
        assertEquals(2, calls())
    }

    @Test
    fun secondFailureIsReturned_noThirdAttempt() = runTest {
        val (sender, calls) = senderReturning(
            TelegramClient.Result.NetworkError("timeout"),
            TelegramClient.Result.NetworkError("still down"),
        )

        assertEquals(TelegramClient.Result.NetworkError("still down"), sender.send("t", 1, "x"))
        assertEquals(2, calls())
    }

    @Test
    fun permanentErrorsAreNotRetried() = runTest {
        for (error in listOf(
            TelegramClient.Result.Unauthorized("Unauthorized"),
            TelegramClient.Result.Forbidden("blocked"),
            TelegramClient.Result.BadRequest("chat not found"),
        )) {
            val (sender, calls) = senderReturning(error)

            assertEquals(error, sender.send("t", 1, "x"))
            assertEquals(1, calls())
        }
    }

    @Test
    fun shortRetryAfterIsHonored() = runTest {
        val (sender, calls) = senderReturning(
            TelegramClient.Result.RetryAfter(2),
            TelegramClient.Result.Ok(Unit),
        )

        assertEquals(TelegramClient.Result.Ok(Unit), sender.send("t", 1, "x"))
        assertEquals(2, calls())
    }

    @Test
    fun longRetryAfterGivesUp() = runTest {
        val (sender, calls) = senderReturning(TelegramClient.Result.RetryAfter(30))

        assertEquals(TelegramClient.Result.RetryAfter(30), sender.send("t", 1, "x"))
        assertEquals(1, calls())
    }
}
