package io.freehand.selection

import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import io.freehand.selection.ai.AiTurn
import java.io.File
import java.util.UUID

internal class HistoryChecks(private val instrumentation: Instrumentation) {
    fun run(): Int {
        val directory = File(instrumentation.targetContext.cacheDir, "history-check-${UUID.randomUUID()}")
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = directory
        }
        val store = ConversationStore(context)
        val id = UUID.randomUUID().toString()
        val image = Bitmap.createBitmap(17, 23, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        val record = ConversationRecord(id, 123L, "automatic prompt", listOf(
            AiTurn("user", "automatic prompt", hidden = true),
            AiTurn("assistant", "**answer**", assistantName = "智谱")), "profile-id", "follow-up", false,
            "DeepSeek", "partial answer", true, "unsent draft", WindowFrame(-700, 90, 450, 600))
        var checks = 0
        fun expect(condition: Boolean, message: String) { check(condition) { message }; checks++ }
        try {
            store.save(record, image)
            val (loaded, restoredImage) = ConversationStore(context).load(id)
            expect(loaded == record, "Restored conversation lost turns, names, pending state, draft or geometry")
            expect(restoredImage.width == 17 && restoredImage.height == 23 && restoredImage.getPixel(5, 6) == android.graphics.Color.MAGENTA,
                "Restored question image changed")
            restoredImage.recycle()
            expect(store.list().single().id == id, "New store cannot discover saved history")
            val next = record.copy(updated = 124, pending = false, partial = "", draft = "")
            store.save(next)
            val (updated, updatedImage) = store.load(id)
            updatedImage.recycle()
            expect(updated == next, "Metadata update without rewriting image failed")
            val recovered = record.frame!!.recovered(1200, 768)
            expect(recovered == WindowFrame(0, 90, 450, 600), "Off-screen recovery did not preserve custom dimensions")
            expect(WindowFrame(500, -100, 1400, 1000).recovered(600, 400) == WindowFrame(0, 0, 600, 400), "Recovery failed on smaller display")
            expect(runCatching { store.load("../bad") }.isFailure, "History IDs permit path traversal")
            store.delete(id)
            store.save(record, image)
            expect(store.list().isEmpty() && runCatching { store.load(id) }.isFailure, "Open panel resurrected deleted conversation")
            expect(!File(directory, "conversations/$id.png").exists(), "Delete retained question image")
        } finally { image.recycle(); directory.deleteRecursively() }
        return checks
    }
}
