package dev.smsforwarder.telegram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for the official Telegram Bot API (Specification §3).
 *
 * Only three methods are used: `getMe` (validate the token), `getUpdates`
 * (one-shot, to learn the owner's chat id during setup) and `sendMessage`
 * (forwarding). Calls are blocking — run them off the main thread.
 *
 * Pure JVM (no `android.*` imports) so it can be unit-tested on the host.
 * Message text is never logged or stored, and the bot token is redacted from
 * any error text this class returns.
 */
class TelegramClient(
    private val baseUrl: String = "https://api.telegram.org",
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 4_000,
) {

    /** Outcome of one Bot API call. */
    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>

        /** 429: Telegram asks us to wait [seconds] before retrying. */
        data class RetryAfter(val seconds: Int) : Result<Nothing>

        /** 401/404, or a malformed token: token wrong or revoked in @BotFather. */
        data class Unauthorized(val description: String) : Result<Nothing>

        /** 403: e.g. the owner blocked the bot or never sent it /start. */
        data class Forbidden(val description: String) : Result<Nothing>

        /** Other client errors, such as "chat not found". */
        data class BadRequest(val description: String) : Result<Nothing>

        /** No usable response: DNS, connect/read timeout, TLS, or a 5xx. */
        data class NetworkError(val description: String) : Result<Nothing>
    }

    data class Bot(val username: String)

    data class Chat(val id: Long, val label: String)

    fun getMe(token: String): Result<Bot> =
        call(token, "getMe", EMPTY).map { result ->
            Bot(username = (result as? JsonObject)?.string("username").orEmpty())
        }

    /**
     * Returns the most recent private chat that messaged the bot (the owner
     * sending /start), or null if there is none. Telegram keeps pending
     * updates for 24 hours.
     */
    fun findLatestPrivateChat(token: String): Result<Chat?> =
        call(token, "getUpdates", EMPTY).map { result ->
            (result as? JsonArray)?.asReversed()?.firstNotNullOfOrNull { privateChatOf(it) }
        }

    fun sendMessage(token: String, chatId: Long, text: String): Result<Unit> {
        val body = buildJsonObject {
            put("chat_id", chatId)
            put("text", text)
            // Never let Telegram fetch links from an SMS: a preview request
            // could consume a single-use login or password-reset link.
            putJsonObject("link_preview_options") { put("is_disabled", true) }
        }
        return call(token, "sendMessage", body).map { }
    }

    private fun call(token: String, method: String, body: JsonObject): Result<JsonElement> {
        if (!TOKEN_FORMAT.matches(token)) return Result.Unauthorized("token format looks wrong")
        val connection = try {
            URL("$baseUrl/bot$token/$method").openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return networkError(e, token)
        }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            parseResponse(status, raw)
        } catch (e: IOException) {
            networkError(e, token)
        } finally {
            connection.disconnect()
        }
    }

    private fun networkError(e: IOException, token: String): Result<Nothing> =
        Result.NetworkError((e.message ?: e.javaClass.simpleName).replace(token, "<token>"))

    internal companion object {
        /** `<bot id>:<secret>` as issued by @BotFather; also keeps the URL path safe. */
        private val TOKEN_FORMAT = Regex("^[0-9]+:[A-Za-z0-9_-]+$")

        private val EMPTY = JsonObject(emptyMap())

        fun parseResponse(status: Int, raw: String): Result<JsonElement> {
            val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return when (status) {
                    in 200..299 -> Result.BadRequest("Unexpected response from Telegram")
                    else -> classify(status, "HTTP $status", retryAfter = null)
                }
            if ((obj["ok"] as? JsonPrimitive)?.booleanOrNull == true) {
                return Result.Ok(obj["result"] ?: JsonNull)
            }
            val code = (obj["error_code"] as? JsonPrimitive)?.intOrNull ?: status
            val description = obj.string("description") ?: "HTTP $status"
            val retryAfter = ((obj["parameters"] as? JsonObject)?.get("retry_after") as? JsonPrimitive)?.intOrNull
            return classify(code, description, retryAfter)
        }

        private fun classify(code: Int, description: String, retryAfter: Int?): Result<Nothing> = when {
            code == 429 -> Result.RetryAfter(retryAfter ?: 1)
            code == 401 || code == 404 -> Result.Unauthorized(description)
            code == 403 -> Result.Forbidden(description)
            code >= 500 -> Result.NetworkError(description)
            else -> Result.BadRequest(description)
        }

        private fun privateChatOf(update: JsonElement): Chat? {
            val message = (update as? JsonObject)?.get("message") as? JsonObject ?: return null
            val chat = message["chat"] as? JsonObject ?: return null
            if (chat.string("type") != "private") return null
            val id = (chat["id"] as? JsonPrimitive)?.longOrNull ?: return null
            val name = listOfNotNull(chat.string("first_name"), chat.string("last_name")).joinToString(" ")
            val username = chat.string("username")?.let { "@$it" }
            val label = listOfNotNull(name.ifBlank { null }, username).joinToString(" ")
            return Chat(id = id, label = label.ifBlank { id.toString() })
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}

private inline fun <T, R> TelegramClient.Result<T>.map(transform: (T) -> R): TelegramClient.Result<R> =
    when (this) {
        is TelegramClient.Result.Ok -> TelegramClient.Result.Ok(transform(value))
        is TelegramClient.Result.RetryAfter -> this
        is TelegramClient.Result.Unauthorized -> this
        is TelegramClient.Result.Forbidden -> this
        is TelegramClient.Result.BadRequest -> this
        is TelegramClient.Result.NetworkError -> this
    }
