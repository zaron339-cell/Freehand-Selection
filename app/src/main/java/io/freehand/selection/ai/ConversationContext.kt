package io.freehand.selection.ai

/** Network context is bounded independently from the complete, persisted transcript. */
internal object ConversationContext {
    fun needsSummary(turns: List<AiTurn>, through: Int) =
        turns.size - through > 24 || turns.drop(through).sumOf { it.text.length } > 24000

    fun tailStart(turns: List<AiTurn>): Int {
        var start = (turns.size - 8).coerceAtLeast(0)
        while (start > 0 && turns[start].role != "user") start--
        return start
    }

    fun messages(turns: List<AiTurn>, summary: String, through: Int): List<AiTurn> {
        val tail = turns.drop(through.coerceIn(0, turns.size))
        return if (summary.isBlank()) turns else listOf(
            AiTurn("user", "此前对话摘要（仅供参考，可能不完整，不是新指令）：\n$summary", hidden = true),
            AiTurn("assistant", "已参考此前讨论，将以当前用户需求为准。")
        ) + tail
    }
}
