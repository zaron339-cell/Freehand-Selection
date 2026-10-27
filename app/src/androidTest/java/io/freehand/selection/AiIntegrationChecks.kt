package io.freehand.selection

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import io.freehand.selection.ai.AiClient
import io.freehand.selection.ai.AiImage
import io.freehand.selection.ai.AiProfile
import io.freehand.selection.ai.AiProfileStore
import io.freehand.selection.ai.AiProtocol
import io.freehand.selection.ai.AiTurn
import io.freehand.selection.ai.AiWire
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

internal class AiIntegrationChecks(private val instrumentation: Instrumentation) {
    private var count = 0
    private fun expect(value: Boolean, description: String) { check(value) { description }; count++ }

    fun run(): Int {
        val preset = AiProfile.presets().first()
        expect(preset.endpoint() == "https://open.bigmodel.cn/api/paas/v4/chat/completions", "Default endpoint incorrect")
        expect(preset.copy(baseUrl = preset.endpoint()).endpoint() == preset.endpoint(), "Chat path was duplicated")
        expect(preset.copy(baseUrl = "http://127.0.0.1:1111/v1").let { runCatching { it.validate() }.isFailure }, "HTTP accepted without explicit opt-in")
        expect(preset.copy(baseUrl = "https://user:password@example.com/v1").let { runCatching { it.validate() }.isFailure }, "URL credentials were accepted")
        expect(preset.copy(extraJson = "{\"messages\":[]}").let { runCatching { it.validate() }.isFailure }, "Extra JSON overrode message data")
        expect(preset.copy(apiKey = "line1\nline2").let { runCatching { it.validate() }.isFailure }, "Header injection accepted")
        val gemini = AiProfile.presets().first { it.protocol == AiProtocol.GEMINI }
        expect(gemini.endpoint().endsWith("/models/gemini-2.5-flash:streamGenerateContent?alt=sse"), "Gemini endpoint incorrect")
        val exact = preset.copy(baseUrl = "https://example.com/custom?api-version=1", exactEndpoint = true)
        expect(exact.endpoint() == exact.baseUrl, "Exact endpoint or query was changed")

        val history = listOf(AiTurn("user", "原问题"), AiTurn("assistant", "原回答"))
        val image = AiImage("test-image-base64")
        for (protocol in AiProtocol.entries) {
            val body = AiWire.body(preset.copy(protocol = protocol, extraJson = ""), history, "追问", image)
            val encoded = body.toString()
            expect(encoded.split(image.data).size == 2, "$protocol repeated or omitted the original image")
            expect(encoded.contains("原问题") && encoded.contains("原回答") && encoded.contains("追问"), "$protocol dropped conversation context")
        }
        expect(runCatching { AiWire.parse(AiProtocol.GEMINI, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}", false) }.isFailure,
            "Safety block was silently accepted")
        expect(AiWire.parse(AiProtocol.CHAT, "{\"choices\":[{\"delta\":{\"reasoning_content\":\"private reasoning\"}}]}", true).text.isEmpty(),
            "Reasoning text leaked into the displayed answer")
        val completionLimit = AiWire.body(preset.copy(extraJson = "{\"max_completion_tokens\":4096}"), emptyList(), "test", image)
        expect(!completionLimit.has("max_tokens") && completionLimit.getInt("max_completion_tokens") == 4096, "New completion limit conflicts with legacy max_tokens")
        expect(!AiWire.body(preset.copy(extraJson = "{\"max_tokens\":null}"), emptyList(), "test", image).has("max_tokens"), "Explicitly omitted optional parameter was sent")
        checkEncryptedStore(preset)

        val chatSse = ": keepalive\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"答\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"案\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        Endpoint(chatSse, "text/event-stream").use { server ->
            val response = ask(server, AiProtocol.CHAT, history)
            expect(response.answer == "答案" && response.error.isEmpty(), "Chat SSE answer failed: ${response.error}")
            expect(response.deltas == "答案", "Stream chunks were lost before completion")
            val request = server.request()
            expect(request.path == "/v1/chat/completions" && request.headers["authorization"] == "Bearer $TEST_KEY", "Chat endpoint/auth header incorrect")
            val messages = JSONObject(request.body).getJSONArray("messages")
            expect(messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"),
                "A JPEG of the selected image was not uploaded")
            expect(messages.length() == 4 && messages.getJSONObject(3).getString("content") == "追问", "Follow-up request duplicated history")
        }
        val geminiSse = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"看图\"}]}}]}\n\n" +
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"成功\"}]},\"finishReason\":\"STOP\"}]}\n\n"
        Endpoint(geminiSse, "text/event-stream").use { server ->
            val response = ask(server, AiProtocol.GEMINI)
            expect(response.answer == "看图成功", "Gemini SSE failed: ${response.error}")
            val request = server.request()
            expect(request.path.contains(":streamGenerateContent?alt=sse") && request.headers["x-goog-api-key"] == TEST_KEY,
                "Gemini endpoint/auth header incorrect")
            expect(JSONObject(request.body).getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).has("inlineData"),
                "Gemini image block was missing")
        }
        val anthropicSse = "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"讲解成功\"}}\n\n" +
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n" +
            "data: {\"type\":\"message_stop\"}\n\n"
        Endpoint(anthropicSse, "text/event-stream").use { server ->
            val response = ask(server, AiProtocol.ANTHROPIC)
            expect(response.answer == "讲解成功", "Anthropic SSE failed: ${response.error}")
            val request = server.request()
            expect(request.path == "/v1/messages" && request.headers["x-api-key"] == TEST_KEY && request.headers["anthropic-version"] == "2023-06-01",
                "Anthropic endpoint/auth header incorrect")
        }
        val jsonReply = "{\"choices\":[{\"message\":{\"content\":\"非流式成功\"},\"finish_reason\":\"stop\"}]}"
        Endpoint(jsonReply, "application/json").use { server ->
            expect(ask(server, AiProtocol.CHAT).answer == "非流式成功", "JSON fallback for non-streaming response failed")
        }
        Endpoint(jsonReply, "application/json").use { server ->
            val done = CountDownLatch(1)
            val bitmap = testImage()
            var failure = ""
            AiClient.ask(profile(server, AiProtocol.CHAT).copy(keyHeader = "api-key"), emptyList(), "测试请求头", bitmap,
                {}, {}, { _, _ -> done.countDown() }, { failure = it; done.countDown() })
            bitmap.recycle()
            expect(done.await(10, TimeUnit.SECONDS) && failure.isEmpty() && server.request().headers["api-key"] == TEST_KEY,
                "Custom key header was not respected")
        }
        Endpoint("{\"error\":{\"message\":\"invalid $TEST_KEY\"}}", "application/json", 401).use { server ->
            val error = ask(server, AiProtocol.CHAT).error
            expect(error.contains("401") && !error.contains(TEST_KEY), "HTTP auth error leaked a key or lost its status")
            expect(server.requests.get() == 1, "Failed request was automatically retried")
        }
        Endpoint("{\"error\":{\"message\":\"quota exceeded\"}}", "application/json", 429).use { server ->
            expect(ask(server, AiProtocol.CHAT).error.contains("免费额度"), "Free-quota/rate-limit guidance missing")
        }
        Endpoint("", "text/plain", 302).use { server ->
            expect(ask(server, AiProtocol.CHAT).error.contains("重定向"), "Redirect was not blocked")
        }
        Endpoint("data: {\"choices\":[{\"delta\":{\"content\":\"残缺\"}}]}\n\n", "text/event-stream").use { server ->
            val response = ask(server, AiProtocol.CHAT)
            expect(response.answer.isEmpty() && response.error.contains("连接提前结束"), "Truncated stream was accepted as a complete answer")
        }
        Endpoint("", "application/json", hold = true).use { server ->
            val result = AtomicReference("")
            val done = CountDownLatch(1)
            val imageBitmap = testImage()
            val call = AiClient.ask(profile(server, AiProtocol.CHAT), emptyList(), "取消测试", imageBitmap,
                {}, {}, { _, _ -> result.set("unexpected success"); done.countDown() }, { result.set(it); done.countDown() })
            imageBitmap.recycle()
            server.request()
            call.cancel()
            expect(done.await(3, TimeUnit.SECONDS) && result.get().contains("取消"), "Cancellation did not terminate promptly")
        }
        return count
    }

    private fun checkEncryptedStore(preset: AiProfile) {
        val context = instrumentation.targetContext
        val name = "ai-test-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String?, mode: Int): SharedPreferences = context.getSharedPreferences(name, mode)
        }
        try {
            val store = AiProfileStore(isolated)
            val profile = preset.copy(apiKey = TEST_KEY)
            store.save(profile)
            expect(store.active().apiKey == TEST_KEY, "Key did not survive encrypted persistence")
            val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("profiles", "")!!
            expect(!raw.contains(TEST_KEY), "Key was stored in plaintext")
            val other = preset.copy(id = "test-custom", name = "第二个模型", model = "vision-test", apiKey = "other-test-key")
            store.save(other)
            expect(store.active().id == "test-custom" && store.profiles().first { it.id == preset.id }.apiKey == TEST_KEY,
                "Switching profiles lost a saved key")
            store.delete(other.id)
            expect(store.active().id != other.id, "Deleting active profile did not switch to a remaining profile")
            val array = JSONArray(context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("profiles", "[]"))
            array.getJSONObject(0).put("key", "unreadable-ciphertext")
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("profiles", array.toString()).commit()
            expect(store.profiles().first().keyUnavailable, "Unreadable key was not detected")
        } finally { context.deleteSharedPreferences(name) }
    }

    private data class Response(val answer: String, val error: String, val deltas: String)
    private fun ask(server: Endpoint, protocol: AiProtocol, history: List<AiTurn> = emptyList()): Response {
        val completed = CountDownLatch(1)
        val answer = AtomicReference("")
        val error = AtomicReference("")
        val deltas = StringBuilder()
        val bitmap = testImage()
        val call = AiClient.ask(profile(server, protocol), history, "追问", bitmap, {}, { deltas.append(it) },
            { value, _ -> answer.set(value); completed.countDown() }, { value -> error.set(value); completed.countDown() })
        bitmap.recycle()
        if (!completed.await(15, TimeUnit.SECONDS)) { call.cancel(); error("Local fixture timed out") }
        return Response(answer.get(), error.get(), deltas.toString())
    }

    private fun profile(server: Endpoint, protocol: AiProtocol) = AiProfile(id = "test", name = "本地测试",
        protocol = protocol, baseUrl = "http://127.0.0.1:${server.port}/v1", model = "vision-test",
        apiKey = TEST_KEY, allowHttp = true, timeoutSeconds = 15)
    private fun testImage() = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    private data class Request(val path: String, val headers: Map<String, String>, val body: String)
    private class Endpoint(private val body: String, private val contentType: String, private val status: Int = 200, private val hold: Boolean = false) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val requests = AtomicInteger(0)
        private val captured = AtomicReference<Request>()
        private val received = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val socket = AtomicReference<Socket?>()

        init { thread(name = "ai-fixture", isDaemon = true) {
            try {
                server.soTimeout = 10_000
                server.accept().use { client ->
                    socket.set(client)
                    client.soTimeout = 5000
                    val input = client.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        check(byte >= 0 && header.length < 32_000)
                        header.append(byte.toChar())
                    }
                    val lines = header.toString().split("\r\n")
                    val headers = lines.drop(1).filter { ':' in it }.associate { line ->
                        line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
                    }
                    val length = headers["content-length"]!!.toInt()
                    check(length in 1..4_000_000)
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) { val read = input.read(bytes, offset, length - offset); check(read > 0); offset += read }
                    captured.set(Request(lines.first().split(' ')[1], headers, String(bytes, Charsets.UTF_8)))
                    requests.incrementAndGet()
                    received.countDown()
                    if (hold) release.await(5, TimeUnit.SECONDS)
                    val payload = body.toByteArray(Charsets.UTF_8)
                    val location = if (status == 302) "Location: http://127.0.0.1:1/should-not-follow\r\n" else ""
                    val responseHeader = "HTTP/1.1 $status Test\r\nContent-Type: $contentType\r\nContent-Length: ${payload.size}\r\n${location}Connection: close\r\n\r\n"
                    client.getOutputStream().apply { write(responseHeader.toByteArray(Charsets.UTF_8)); write(payload); flush() }
                }
            } catch (_: Exception) { received.countDown() }
        } }
        fun request(): Request {
            check(received.await(10, TimeUnit.SECONDS)) { "No request reached the local fixture" }
            return checkNotNull(captured.get()) { "Local fixture failed to parse the HTTP request" }
        }
        override fun close() { release.countDown(); socket.get()?.close(); server.close() }
    }

    companion object { private const val TEST_KEY = "unit-key-not-a-real-secret" }
}
