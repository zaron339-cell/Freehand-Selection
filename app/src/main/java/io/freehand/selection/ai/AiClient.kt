package io.freehand.selection.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

internal class AiCall(private val onCancel: () -> Unit) {
    fun cancel() = onCancel()
}

internal object AiClient {
    private val workers = Executors.newCachedThreadPool()
    private val clock = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun ask(
        profile: AiProfile,
        history: List<AiTurn>,
        question: String,
        image: Bitmap,
        onStage: (String) -> Unit,
        onText: (String) -> Unit,
        onComplete: (String, String) -> Unit,
        onError: (String) -> Unit,
        systemPrompt: String = AiWire.SYSTEM,
    ): AiCall {
        profile.validate()
        require(question.isNotBlank()) { "请先输入问题" }
        require(history.size <= 40) { "本次对话较长，请重新圈选后开始新对话" }
        val ended = AtomicBoolean(false)
        val canceled = AtomicBoolean(false)
        val link = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>()
        var deadline: ScheduledFuture<*>? = null
        // Bitmap ownership is independent of the overlay; closing it cannot race image compression.
        val ownedImage = image.copy(Bitmap.Config.ARGB_8888, false)
        fun stage(value: String) = main.post { if (!ended.get()) onStage(value) }
        fun fail(value: String) {
            if (ended.compareAndSet(false, true)) {
                deadline?.cancel(false)
                main.post { onError(redact(value, profile.apiKey)) }
            }
        }
        deadline = clock.schedule({
            canceled.set(true)
            fail("请求超过 ${profile.timeoutSeconds} 秒，已停止。可重试或切换模型；不会自动重复计费请求。")
            workers.execute { link.get()?.disconnect() }
        }, profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        workers.execute {
            var connection: HttpURLConnection? = null
            try {
                stage("正在准备选区图片…")
                val encoded = try { encode(ownedImage) } finally { ownedImage.recycle() }
                if (canceled.get()) return@execute
                val payload = AiWire.body(profile, history, question, encoded, systemPrompt).toString().toByteArray(Charsets.UTF_8)
                stage("正在连接 ${profile.host}…")
                connection = URL(profile.endpoint()).openConnection() as HttpURLConnection
                link.set(connection)
                if (canceled.get()) return@execute
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false // Never forward a key or selected image to a redirected host.
                connection.connectTimeout = 15_000
                connection.readTimeout = profile.timeoutSeconds * 1000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", if (profile.stream) "text/event-stream, application/json" else "application/json")
                if (profile.apiKey.isNotBlank() && profile.keyHeader.isNotBlank()) {
                    connection.setRequestProperty(profile.keyHeader, if (profile.keyHeader.equals("Authorization", true)) "Bearer ${profile.apiKey}" else profile.apiKey)
                } else if (profile.apiKey.isNotBlank()) when (profile.protocol) {
                    AiProtocol.CHAT -> connection.setRequestProperty("Authorization", "Bearer ${profile.apiKey}")
                    AiProtocol.GEMINI -> connection.setRequestProperty("x-goog-api-key", profile.apiKey)
                    AiProtocol.ANTHROPIC -> connection.setRequestProperty("x-api-key", profile.apiKey)
                }
                if (profile.protocol == AiProtocol.ANTHROPIC) connection.setRequestProperty("anthropic-version", "2023-06-01")
                connection.setFixedLengthStreamingMode(payload.size)
                stage("正在上传选区图片…")
                connection.outputStream.use { it.write(payload) }
                stage("等待模型回答…")
                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = connection.errorStream?.use { stream ->
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(1024)
                        while (bytes.size() < 4096) {
                            val read = stream.read(buffer, 0, minOf(buffer.size, 4096 - bytes.size()))
                            if (read < 0) break
                            bytes.write(buffer, 0, read)
                        }
                        String(bytes.toByteArray(), Charsets.UTF_8)
                    }.orEmpty()
                    throw IllegalStateException(httpError(code, redact(detail, profile.apiKey)))
                }
                val output = StringBuilder()
                var finished = false
                var reason = ""
                fun consume(data: String, streaming: Boolean) {
                    if (canceled.get()) return
                    val chunk = AiWire.parse(profile.protocol, data, streaming)
                    if (chunk.thinking && output.isEmpty()) stage("模型正在思考…")
                    if (chunk.text.isNotEmpty()) {
                        check(output.length + chunk.text.length <= 100_000) { "回答过长，已停止接收，请缩小问题范围" }
                        output.append(chunk.text)
                        main.post { if (!canceled.get()) onText(chunk.text) }
                    }
                    if (chunk.reason.isNotBlank()) reason = chunk.reason
                    finished = finished || chunk.finished
                }
                LimitedInput(connection.inputStream, 8 * 1024 * 1024).bufferedReader(Charsets.UTF_8).use { reader ->
                    val isSse = connection.contentType.orEmpty().contains("text/event-stream", ignoreCase = true)
                    if (isSse) {
                        val data = StringBuilder()
                        while (!canceled.get()) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) {
                                if (data.isNotEmpty()) { consume(data.toString(), true); data.setLength(0) }
                                if (finished) break
                            } else if (line.startsWith("data:")) {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substring(5).removePrefix(" "))
                                check(data.length <= 512_000) { "接口返回了过大的流式事件" }
                            }
                        }
                        if (!canceled.get() && data.isNotEmpty()) consume(data.toString(), true)
                    } else {
                        consume(reader.readText(), false)
                    }
                }
                if (canceled.get()) return@execute
                check(finished) { "连接提前结束，回答可能不完整。请重试；本次残缺回答不会加入追问上下文。" }
                check(output.isNotBlank()) { "接口未返回可显示的文字。请确认模型支持图片，或调整输出上限及思考参数。" }
                if (ended.compareAndSet(false, true)) {
                    deadline?.cancel(false)
                    val answer = output.toString()
                    main.post { onComplete(answer, reason) }
                }
            } catch (error: Exception) {
                if (!canceled.get()) fail(when (error) {
                    is SocketTimeoutException -> "连接或读取超时，请检查网络或切换模型后重试"
                    is javax.net.ssl.SSLException -> "TLS 连接失败，请检查 API 地址、设备时间和证书"
                    is java.net.UnknownHostException -> "无法解析服务地址，请检查网络和 API 地址"
                    else -> error.localizedMessage ?: "请求失败，请检查模型配置"
                })
            } finally {
                if (!ownedImage.isRecycled) ownedImage.recycle()
                connection?.disconnect()
                link.set(null)
            }
        }
        return AiCall {
            canceled.set(true)
            if (ended.compareAndSet(false, true)) {
                deadline?.cancel(false)
                main.post { onError("已取消，本次未完成的回答不会加入追问上下文") }
            }
            workers.execute { link.get()?.disconnect() }
        }
    }

    private fun encode(source: Bitmap): AiImage {
        val scale = minOf(1f, 2048f / max(source.width, source.height))
        val bitmap = Bitmap.createBitmap((source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, null, android.graphics.Rect(0, 0, bitmap.width, bitmap.height), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val bytes = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes)) { "无法编码选区图片" }
            return AiImage(Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
        } finally { bitmap.recycle() }
    }

    private fun httpError(code: Int, detail: String): String {
        val hint = when (code) {
            400, 422 -> "请求格式或模型不匹配，请确认接口协议、模型是否支持图片及附加参数"
            401, 403 -> "密钥无效或账户/模型权限不足，请检查 API Key 和账户状态"
            402 -> "服务商额度或余额不足，请检查账户或切换免费模型"
            404 -> "接口或模型不存在，请检查地址和模型名"
            408, 504 -> "服务商响应超时，请稍后重试"
            413 -> "图片或上下文过大，请缩小选区或开始新对话"
            429 -> "触发限流或免费额度已用完，请稍后重试或切换配置"
            in 300..399 -> "接口发生重定向，为避免密钥发往其他地址已停止，请填写最终 API 地址"
            in 500..599 -> "服务商暂时不可用，请稍后重试"
            else -> "接口请求失败"
        }
        val message = runCatching { org.json.JSONObject(detail).optJSONObject("error")?.optString("message") }.getOrNull()
        return "HTTP $code · $hint" + if (!message.isNullOrBlank()) "\n${message.take(350)}" else ""
    }

    private fun redact(value: String, key: String) = (if (key.isBlank()) value else value.replace(key, "[密钥已隐藏]"))
        .replace(Regex("data:image/[^;]+;base64,[A-Za-z0-9+/=]+"), "[图片数据已隐藏]")

    private class LimitedInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) { count++; check(count <= limit) { "接口响应过大" } } }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also {
            if (it > 0) { count += it; check(count <= limit) { "接口响应过大" } }
        }
    }
}
