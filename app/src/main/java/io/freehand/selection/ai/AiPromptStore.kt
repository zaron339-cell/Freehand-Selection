package io.freehand.selection.ai

import android.content.Context

internal data class AiPrompts(
    val system: String = DEFAULT_SYSTEM,
    val solve: String = DEFAULT_SOLVE,
    val explain: String = DEFAULT_EXPLAIN,
) {
    companion object {
        const val DEFAULT_SYSTEM = "你是一位学习助手，请根据用户提供的内容和问题提供帮助。"
        const val DEFAULT_SOLVE = "请解答选区中的问题。"
        const val DEFAULT_EXPLAIN = "请讲解选区中的内容。"
    }
}

/** Prompt preferences are independent of model profiles and apply to subsequent requests. */
internal class AiPromptStore(context: Context) {
    private val preferences = context.getSharedPreferences("ai-prompts", Context.MODE_PRIVATE)

    fun load() = AiPrompts(
        system = preferences.getString("system", AiPrompts.DEFAULT_SYSTEM).orEmpty(),
        solve = preferences.getString("solve", null)?.takeIf { it.isNotBlank() } ?: AiPrompts.DEFAULT_SOLVE,
        explain = preferences.getString("explain", null)?.takeIf { it.isNotBlank() } ?: AiPrompts.DEFAULT_EXPLAIN,
    )

    fun save(prompts: AiPrompts) {
        require(listOf(prompts.system, prompts.solve, prompts.explain).all { it.length <= 8000 }) { "每项提示词最多 8000 字符" }
        check(preferences.edit().putString("system", prompts.system.trim()).putString("solve", prompts.solve.trim())
            .putString("explain", prompts.explain.trim()).commit()) { "提示词保存失败，请重试" }
    }
}
