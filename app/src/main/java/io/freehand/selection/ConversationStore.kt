package io.freehand.selection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import io.freehand.selection.ai.AiTurn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

internal data class WindowFrame(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun recovered(screenWidth: Int, screenHeight: Int): WindowFrame {
        val w = width.coerceIn(1, screenWidth.coerceAtLeast(1))
        val h = height.coerceIn(1, screenHeight.coerceAtLeast(1))
        return WindowFrame(x.coerceIn(0, screenWidth - w), y.coerceIn(0, screenHeight - h), w, h)
    }
}

internal data class ConversationRecord(
    val id: String, val updated: Long, val initialQuestion: String,
    val turns: List<AiTurn>, val profileId: String, val question: String,
    val hidden: Boolean, val assistantName: String, val partial: String,
    val pending: Boolean, val draft: String, val frame: WindowFrame?,
) {
    val title: String get() = (turns.firstOrNull { it.role == "assistant" }?.text
        ?: initialQuestion).replace(Regex("[#*`\\n\\r]+"), " ").take(70)

    fun json(): JSONObject = JSONObject().put("id", id).put("updated", updated)
        .put("initialQuestion", initialQuestion).put("profileId", profileId)
        .put("turns", JSONArray().apply { turns.forEach { put(JSONObject().put("role", it.role)
            .put("text", it.text).put("hidden", it.hidden).put("assistantName", it.assistantName)) } })
        .put("question", question).put("hidden", hidden).put("assistantName", assistantName)
        .put("partial", partial).put("pending", pending).put("draft", draft)
        .put("frame", frame?.let { JSONObject().put("x", it.x).put("y", it.y)
            .put("width", it.width).put("height", it.height) })

    companion object {
        fun parse(j: JSONObject): ConversationRecord {
            val turns = j.getJSONArray("turns")
            val frame = j.optJSONObject("frame")?.let { WindowFrame(it.getInt("x"), it.getInt("y"), it.getInt("width"), it.getInt("height")) }
            return ConversationRecord(j.getString("id"), j.getLong("updated"), j.getString("initialQuestion"),
                (0 until turns.length()).map { turns.getJSONObject(it).let { t -> AiTurn(t.getString("role"),
                    t.getString("text"), t.optBoolean("hidden"), t.optString("assistantName")) } },
                j.getString("profileId"), j.optString("question"), j.optBoolean("hidden"),
                j.optString("assistantName"), j.optString("partial"), j.optBoolean("pending"), j.optString("draft"), frame)
        }
    }
}

/** All disk operations run on one queue. Atomic metadata always points at an already committed image. */
internal class ConversationStore(context: Context) {
    private val directory = File(context.filesDir, "conversations")
    private fun file(id: String, extension: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        check(directory.isDirectory || directory.mkdirs()) { "无法创建历史目录" }
        return File(directory, "$id.$extension")
    }
    private fun atomic(file: File, write: (java.io.FileOutputStream) -> Unit) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { write(stream); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    fun save(record: ConversationRecord, image: Bitmap? = null) {
        if (file(record.id, "deleted").exists()) return
        if (image != null && !file(record.id, "png").exists())
            atomic(file(record.id, "png")) { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        check(file(record.id, "png").exists()) { "题图未保存" }
        atomic(file(record.id, "json")) { it.write(record.json().toString().toByteArray(Charsets.UTF_8)) }
    }
    fun list(): List<ConversationRecord> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" && !File(directory, "${it.nameWithoutExtension}.deleted").exists() }
        .mapNotNull { runCatching { ConversationRecord.parse(JSONObject(AtomicFile(it).readFully().toString(Charsets.UTF_8))) }.getOrNull() }
        .sortedByDescending { it.updated }
    fun load(id: String): Pair<ConversationRecord, Bitmap> {
        check(!file(id, "deleted").exists()) { "对话已删除" }
        val record = ConversationRecord.parse(JSONObject(AtomicFile(file(id, "json")).readFully().toString(Charsets.UTF_8)))
        require(record.id == id)
        val image = BitmapFactory.decodeFile(file(id, "png").path) ?: error("题图无法读取")
        return record to image
    }
    fun delete(id: String) {
        // Keep a tiny tombstone so a still-open panel cannot resurrect a deleted conversation.
        atomic(file(id, "deleted")) { it.write(1) }
        AtomicFile(file(id, "json")).delete()
        AtomicFile(file(id, "png")).delete()
    }
    companion object {
        private val queue = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())
        fun <T> async(work: () -> T, done: (Result<T>) -> Unit = {}) {
            queue.execute { val result = runCatching(work); main.post { done(result) } }
        }
    }
}
