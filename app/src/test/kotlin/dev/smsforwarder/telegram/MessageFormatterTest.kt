package dev.smsforwarder.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class MessageFormatterTest {

    @Test
    fun composesThreeLineTemplate() {
        val text = MessageFormatter.compose("+15551234567", "Your code is 123456", 0L, ZoneOffset.UTC)

        assertEquals("SMS from +15551234567\n1970-01-01T00:00\nYour code is 123456", text)
    }

    @Test
    fun shortTextIsUnchanged() {
        val text = "a".repeat(MessageFormatter.MAX_LENGTH)

        assertEquals(text, MessageFormatter.truncate(text))
    }

    @Test
    fun longTextIsCutToTelegramLimit() {
        val text = MessageFormatter.compose("+1", "b".repeat(5_000), 0L, ZoneOffset.UTC)

        assertEquals(MessageFormatter.MAX_LENGTH, text.length)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun truncationNeverSplitsASurrogatePair() {
        // The emoji's high surrogate would be the last kept char without the guard.
        val text = "a".repeat(MessageFormatter.MAX_LENGTH - 2) + "😀".repeat(10)

        val cut = MessageFormatter.truncate(text)

        assertTrue(cut.length <= MessageFormatter.MAX_LENGTH)
        assertFalse(Character.isHighSurrogate(cut[cut.length - 2]))
        assertTrue(cut.endsWith("…"))
    }
}
