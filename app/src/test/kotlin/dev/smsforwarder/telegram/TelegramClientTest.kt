package dev.smsforwarder.telegram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelegramClientTest {

    private val token = "123456789:AAH-test_token"
    private lateinit var server: MockWebServer
    private lateinit var client: TelegramClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TelegramClient(baseUrl = server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun reply(status: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    @Test
    fun sendMessage_postsPlainTextWithoutLinkPreview() {
        reply(200, """{"ok":true,"result":{"message_id":7}}""")

        val result = client.sendMessage(token, 42L, "SMS from +1555\nhello")

        assertEquals(TelegramClient.Result.Ok(Unit), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/bot$token/sendMessage", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(42L, body["chat_id"]!!.jsonPrimitive.long)
        assertEquals("SMS from +1555\nhello", body["text"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("parse_mode"))
        assertTrue(body["link_preview_options"]!!.jsonObject["is_disabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun errors_areClassified() {
        reply(401, """{"ok":false,"error_code":401,"description":"Unauthorized"}""")
        reply(403, """{"ok":false,"error_code":403,"description":"Forbidden: bot was blocked by the user"}""")
        reply(400, """{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}""")
        reply(429, """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":7}}""")
        reply(502, "<html>Bad Gateway</html>")
        reply(404, """{"ok":false,"error_code":404,"description":"Not Found"}""")

        assertEquals(TelegramClient.Result.Unauthorized("Unauthorized"), client.sendMessage(token, 1, "x"))
        assertEquals(
            TelegramClient.Result.Forbidden("Forbidden: bot was blocked by the user"),
            client.sendMessage(token, 1, "x"),
        )
        assertEquals(TelegramClient.Result.BadRequest("Bad Request: chat not found"), client.sendMessage(token, 1, "x"))
        assertEquals(TelegramClient.Result.RetryAfter(7), client.sendMessage(token, 1, "x"))
        assertEquals(TelegramClient.Result.NetworkError("HTTP 502"), client.sendMessage(token, 1, "x"))
        assertEquals(TelegramClient.Result.Unauthorized("Not Found"), client.getMe(token))
    }

    @Test
    fun unreachableServer_isNetworkError_withoutLeakingToken() {
        server.shutdown()

        val result = client.sendMessage(token, 1, "x")

        assertTrue(result is TelegramClient.Result.NetworkError)
        assertFalse((result as TelegramClient.Result.NetworkError).description.contains(token))
    }

    @Test
    fun malformedToken_isRejectedWithoutARequest() {
        val result = client.sendMessage("123:abc/../getUpdates?x=", 1, "x")

        assertTrue(result is TelegramClient.Result.Unauthorized)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun getMe_returnsBotUsername() {
        reply(200, """{"ok":true,"result":{"id":1,"is_bot":true,"first_name":"Fwd","username":"my_fwd_bot"}}""")

        assertEquals(TelegramClient.Result.Ok(TelegramClient.Bot("my_fwd_bot")), client.getMe(token))
        assertEquals("/bot$token/getMe", server.takeRequest().path)
    }

    @Test
    fun findLatestPrivateChat_picksNewestPrivateChat() {
        reply(
            200,
            """
            {"ok":true,"result":[
              {"update_id":1,"message":{"message_id":1,"chat":{"id":111,"type":"private","first_name":"Old"},"text":"/start"}},
              {"update_id":2,"message":{"message_id":2,"chat":{"id":4503599627370495,"type":"private","first_name":"Ann","last_name":"Lee","username":"ann"},"text":"/start"}},
              {"update_id":3,"message":{"message_id":3,"chat":{"id":-100123,"type":"group","title":"Some group"},"text":"hi"}},
              {"update_id":4,"edited_message":{"message_id":2,"chat":{"id":999,"type":"private","first_name":"Edit"}}}
            ]}
            """.trimIndent(),
        )

        val result = client.findLatestPrivateChat(token)

        assertEquals(TelegramClient.Result.Ok(TelegramClient.Chat(4503599627370495L, "Ann Lee @ann")), result)
    }

    @Test
    fun findLatestPrivateChat_withoutMessages_returnsNull() {
        reply(200, """{"ok":true,"result":[]}""")

        val result = client.findLatestPrivateChat(token)

        assertTrue(result is TelegramClient.Result.Ok)
        assertNull((result as TelegramClient.Result.Ok).value)
    }
}
