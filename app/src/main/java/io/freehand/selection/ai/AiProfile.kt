package io.freehand.selection.ai

import java.net.URI
import java.util.UUID
import org.json.JSONObject

internal enum class AiProtocol(val label: String) {
    CHAT("Chat Completions 兼容（智谱 / DeepSeek 等）"),
    GEMINI("Gemini 原生"),
    ANTHROPIC("Anthropic Messages")
}

internal data class AiProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "自定义模型",
    val protocol: AiProtocol = AiProtocol.CHAT,
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val keyHeader: String = "",
    val stream: Boolean = true,
    val timeoutSeconds: Int = 120,
    val exactEndpoint: Boolean = false,
    val allowHttp: Boolean = false,
    val extraJson: String = "",
    val keyUnavailable: Boolean = false,
) {
    val host: String get() = runCatching { URI(baseUrl).host }.getOrNull().orEmpty()
    fun assistantName(): String {
        val selected = model.lowercase()
        return when {
            "deepseek" in selected -> "DeepSeek"
            selected.startsWith("glm") || "/glm" in selected -> "智谱"
            "gemini" in selected -> "Gemini"
            "claude" in selected -> "Claude"
            "qwen" in selected -> "通义千问"
            selected.startsWith("gpt") || selected.matches(Regex("o[1-9]([-.].*)?")) -> "GPT"
            host == "api.deepseek.com" -> "DeepSeek"
            host == "open.bigmodel.cn" -> "智谱"
            host == "generativelanguage.googleapis.com" -> "Gemini"
            host == "api.anthropic.com" -> "Claude"
            else -> name.ifBlank { model }
        }
    }
    override fun toString() = "AiProfile(name=$name, protocol=$protocol, model=$model, key=<redacted>)"

    fun validate() {
        require(name.isNotBlank()) { "请填写配置名称" }
        require(model.isNotBlank() && model.length <= 200 && '\n' !in model && '\r' !in model) { "请填写有效的模型名称" }
        val uri = runCatching { URI(baseUrl.trim()) }.getOrElse { throw IllegalArgumentException("API 地址格式不正确") }
        require(uri.host != null && uri.userInfo == null && uri.fragment == null) { "API 地址需要包含主机，不能带用户名或 # 片段" }
        require(uri.scheme == "https" || (uri.scheme == "http" && allowHttp)) { "请使用 HTTPS；本地 HTTP 服务需勾选明文连接" }
        require(timeoutSeconds in 15..300) { "超时请填写 15–300 秒" }
        require('\n' !in apiKey && '\r' !in apiKey) { "API Key 中不能包含换行" }
        require(keyHeader.isBlank() || keyHeader.matches(Regex("[A-Za-z0-9-]+"))) { "密钥请求头名称只能包含字母、数字和横线" }
        require(keyHeader.lowercase() !in setOf("host", "content-length", "content-type", "connection", "transfer-encoding")) { "密钥请求头不能覆盖基础传输头" }
        val extras = if (extraJson.isBlank()) JSONObject() else runCatching { JSONObject(extraJson) }
            .getOrElse { throw IllegalArgumentException("附加参数需要是有效的 JSON 对象") }
        val reserved = setOf("model", "messages", "contents", "system", "system_instruction", "systemInstruction", "stream")
        require(extras.keys().asSequence().none { it in reserved }) { "附加参数不能覆盖模型、消息、系统提示或流式开关" }
    }

    fun endpoint(): String {
        validate()
        val uri = URI(baseUrl.trim())
        var path = uri.path.orEmpty().trimEnd('/')
        if (!exactEndpoint) {
            path = when (protocol) {
                AiProtocol.CHAT -> if (path.endsWith("/chat/completions")) path else "$path/chat/completions"
                AiProtocol.ANTHROPIC -> if (path.endsWith("/messages")) path else "$path/messages"
                AiProtocol.GEMINI -> "$path/models/${model.removePrefix("models/")}:${if (stream) "streamGenerateContent" else "generateContent"}"
            }
        }
        val query = if (protocol == AiProtocol.GEMINI && stream && !uri.query.orEmpty().split('&').any { it.startsWith("alt=") }) {
            listOfNotNull(uri.query?.takeIf { it.isNotBlank() }, "alt=sse").joinToString("&")
        } else uri.query
        return URI(uri.scheme, uri.authority, path, query, null).toASCIIString()
    }

    companion object {
        fun presets() = listOf(
            AiProfile(id = "zhipu-free", name = "智谱 · 免费视觉", baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                model = "glm-4.6v-flash", extraJson = "{\"thinking\":{\"type\":\"disabled\"}}"),
            AiProfile(id = "deepseek", name = "DeepSeek · 按量计费", baseUrl = "https://api.deepseek.com",
                model = "deepseek-flash", extraJson = "{\"thinking\":{\"type\":\"disabled\"}}"),
            AiProfile(id = "gemini", name = "Gemini · 可用免费层", protocol = AiProtocol.GEMINI,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta", model = "gemini-2.5-flash"),
        )
    }
}
