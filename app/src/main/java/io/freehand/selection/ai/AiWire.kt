package io.freehand.selection.ai

import org.json.JSONArray
import org.json.JSONObject

internal data class AiTurn(val role: String, val text: String, val hidden: Boolean = false, val assistantName: String = "")
internal data class AiImage(val data: String, val mimeType: String = "image/jpeg")
internal data class AiChunk(val text: String = "", val thinking: Boolean = false, val finished: Boolean = false, val reason: String = "")

internal object AiWire {
    const val SYSTEM = AiPrompts.DEFAULT_SYSTEM

    fun body(profile: AiProfile, history: List<AiTurn>, question: String, image: AiImage, systemPrompt: String = SYSTEM): JSONObject {
        val turns = history + AiTurn("user", question)
        val root = when (profile.protocol) {
            AiProtocol.CHAT -> JSONObject().put("model", profile.model).put("stream", profile.stream).put("max_tokens", 4096).apply {
                val messages = JSONArray()
                if (systemPrompt.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
                turns.forEachIndexed { index, turn ->
                    val content: Any = if (index == 0) JSONArray()
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${image.mimeType};base64,${image.data}")))
                        .put(JSONObject().put("type", "text").put("text", turn.text)) else turn.text
                    messages.put(JSONObject().put("role", turn.role).put("content", content))
                }
                put("messages", messages)
            }
            AiProtocol.GEMINI -> JSONObject().put("generationConfig", JSONObject().put("maxOutputTokens", 4096)).apply {
                    if (systemPrompt.isNotBlank()) put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                    val contents = JSONArray()
                    turns.forEachIndexed { index, turn ->
                        val parts = JSONArray()
                        if (index == 0) parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", image.mimeType).put("data", image.data)))
                        parts.put(JSONObject().put("text", turn.text))
                        contents.put(JSONObject().put("role", if (turn.role == "assistant") "model" else "user").put("parts", parts))
                    }
                    put("contents", contents)
                }
            AiProtocol.ANTHROPIC -> JSONObject().put("model", profile.model).put("max_tokens", 4096)
                .put("stream", profile.stream).apply {
                    if (systemPrompt.isNotBlank()) put("system", systemPrompt)
                    val messages = JSONArray()
                    turns.forEachIndexed { index, turn ->
                        val parts = JSONArray()
                        if (index == 0) parts.put(JSONObject().put("type", "image").put("source", JSONObject()
                            .put("type", "base64").put("media_type", image.mimeType).put("data", image.data)))
                        parts.put(JSONObject().put("type", "text").put("text", turn.text))
                        messages.put(JSONObject().put("role", turn.role).put("content", parts))
                    }
                    put("messages", messages)
                }
        }
        if (profile.extraJson.isNotBlank()) {
            val extras = JSONObject(profile.extraJson)
            if (profile.protocol == AiProtocol.CHAT && extras.has("max_completion_tokens") && !extras.has("max_tokens")) root.remove("max_tokens")
            for (key in extras.keys()) {
                if (extras.isNull(key)) root.remove(key) else root.put(key, extras.get(key))
            }
        }
        return root
    }

    fun parse(protocol: AiProtocol, data: String, streaming: Boolean): AiChunk {
        if (data.trim() == "[DONE]") return AiChunk(finished = true)
        val json = JSONObject(data)
        if (json.has("error")) {
            val error = json.optJSONObject("error")
            throw IllegalStateException(error?.optString("message") ?: "接口返回错误")
        }
        return when (protocol) {
            AiProtocol.CHAT -> {
                val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return AiChunk()
                val content = if (streaming) choice.optJSONObject("delta") else choice.optJSONObject("message")
                val reason = choice.optString("finish_reason").takeUnless { it == "null" }.orEmpty()
                val text = text(content?.opt("content"))
                if (reason == "content_filter") throw IllegalStateException("内容被服务商过滤，请调整选区或提问")
                AiChunk(text, !content?.optString("reasoning_content").isNullOrBlank(), !streaming || reason.isNotBlank(), reason)
            }
            AiProtocol.GEMINI -> {
                val feedback = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                if (feedback.isNotBlank()) throw IllegalStateException("服务商拒绝了本次请求：$feedback")
                val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: return AiChunk()
                val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                var thinking = false
                val output = StringBuilder()
                if (parts != null) for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.optBoolean("thought")) thinking = true else output.append(part.optString("text"))
                }
                val reason = candidate.optString("finishReason")
                if (reason in setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII")) {
                    throw IllegalStateException("回答被服务商拦截：$reason")
                }
                AiChunk(output.toString(), thinking, !streaming || reason.isNotBlank(), reason)
            }
            AiProtocol.ANTHROPIC -> when (json.optString("type")) {
                "content_block_delta" -> json.optJSONObject("delta").let { delta ->
                    AiChunk(if (delta?.optString("type") == "text_delta") delta.optString("text") else "",
                        thinking = delta?.optString("type") == "thinking_delta")
                }
                "message_delta" -> AiChunk(reason = json.optJSONObject("delta")?.optString("stop_reason").orEmpty())
                "message_stop" -> AiChunk(finished = true)
                "message" -> AiChunk(text(json.opt("content")), finished = true, reason = json.optString("stop_reason"))
                else -> AiChunk()
            }
        }
    }

    private fun text(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildString { for (i in 0 until value.length()) {
            val part = value.optJSONObject(i) ?: continue
            if (part.optString("type") == "text") append(part.optString("text"))
        } }
        else -> ""
    }
}
