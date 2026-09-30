package dev.smsforwarder.telegram

import java.time.Instant
import java.time.ZoneId

/**
 * Renders the SMS into the plain-text message sent to the Telegram chat
 * (Specification §6 step 5, and §11 scaffold option).
 *
 * v1 template (decided at scaffolding):
 *
 * ```
 * SMS from <sender>
 * <local timestamp>
 * <body>
 * ```
 *
 * TODO(sms-forwarder): richer, user-editable template deferred to a future
 *   spec — see Specification §11. Keep this single point of contact for the
 *   format so a richer template only needs edits here.
 */
object MessageFormatter {

    /** Bot API limit for `sendMessage` text. */
    const val MAX_LENGTH = 4096

    fun compose(
        from: String,
        body: String,
        timestampMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val ts = Instant.ofEpochMilli(timestampMillis)
            .atZone(zone)
            .toLocalDateTime()
            .toString()
        val text = buildString {
            append("SMS from ").append(from).append('\n')
            append(ts).append('\n')
            append(body)
        }
        return truncate(text)
    }

    /** Cuts [text] to [MAX_LENGTH] with a trailing ellipsis, never splitting a surrogate pair. */
    fun truncate(text: String): String {
        if (text.length <= MAX_LENGTH) return text
        var end = MAX_LENGTH - 1
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end) + "…"
    }
}
