package io.freehand.selection.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class MemoryEntry(val id: String, val category: String, val content: String, val scope: String,
    val explicit: Boolean, val manual: Boolean, val updated: Long, val sources: List<String>) {
    fun json() = JSONObject().put("id", id).put("category", category).put("content", content).put("scope", scope)
        .put("explicit", explicit).put("manual", manual).put("updated", updated).put("sources", JSONArray(sources))
}
internal data class MemorySnapshot(val revision: Long, val enabled: Boolean, val learning: Boolean,
    val entries: List<MemoryEntry>, val cursors: Map<String, Int>, val tentative: List<MemoryEntry> = emptyList())

/** Shared lock protects read-modify-write across views, background responses and history deletion. */
internal class UserMemoryStore(context: Context) {
    // Keep ContextWrapper preference namespaces in instrumentation, too.
    private val storage = context.getSharedPreferences("user-memory", Context.MODE_PRIVATE)
    private fun read() = runCatching { JSONObject(storage.getString("state", "{}")!!) }.getOrElse { JSONObject() }
    private fun write(root: JSONObject) { check(storage.edit().putString("state", root.toString()).commit()) { "记忆保存失败" } }
    private fun entries(root: JSONObject, key: String = "entries"): List<MemoryEntry> = root.optJSONArray(key)?.let { a ->
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { j ->
            MemoryEntry(j.getString("id"), j.getString("category"), j.getString("content"), j.optString("scope"),
                j.optBoolean("explicit"), j.optBoolean("manual"), j.optLong("updated"),
                j.optJSONArray("sources")?.let { s -> (0 until s.length()).map { s.getString(it) } }.orEmpty())
        } }.getOrNull() }
    }.orEmpty()
    private fun putEntries(root: JSONObject, list: List<MemoryEntry>) { root.put("entries", JSONArray().apply { list.forEach { put(it.json()) } }) }
    private fun bump(root: JSONObject) {
        root.put("revision", root.optLong("revision") + 1)
        val seen = root.optJSONObject("seen") ?: return
        val cursors = root.optJSONObject("cursors") ?: JSONObject()
        seen.keys().forEach { cursors.put(it, maxOf(cursors.optInt(it), seen.optInt(it))) }
        root.put("cursors", cursors)
    }
    fun observe(conversation: String, count: Int) = synchronized(lock) {
        val root = read(); val seen = root.optJSONObject("seen") ?: JSONObject()
        seen.put(conversation, maxOf(count, seen.optInt(conversation))); root.put("seen", seen); write(root)
    }
    fun baseline(conversation: String, count: Int) = synchronized(lock) {
        val root = read(); val cursors = root.optJSONObject("cursors") ?: JSONObject()
        if (!cursors.has(conversation)) {
            cursors.put(conversation, count); root.put("cursors", cursors); write(root)
        }
    }
    fun snapshot(): MemorySnapshot = synchronized(lock) {
        val root = read()
        val cursors = root.optJSONObject("cursors") ?: JSONObject()
        MemorySnapshot(root.optLong("revision"), root.optBoolean("enabled", true), root.optBoolean("learning", true),
            entries(root), cursors.keys().asSequence().associateWith { cursors.optInt(it) }, entries(root, "tentative"))
    }
    fun configure(enabled: Boolean, learning: Boolean) = synchronized(lock) {
        val root = read(); bump(root); root.put("enabled", enabled).put("learning", learning); write(root)
    }
    fun clear() = synchronized(lock) { val root = read(); bump(root); putEntries(root, emptyList()); root.remove("tentative"); write(root) }
    fun delete(id: String) = synchronized(lock) {
        val root = read(); bump(root); putEntries(root, entries(root).filterNot { it.id == id }); root.remove("tentative"); write(root)
    }
    fun edit(id: String, content: String) = synchronized(lock) {
        require(content.isNotBlank() && content.length <= 160) { "记忆需为 1–160 字符" }
        val root = read(); bump(root)
        putEntries(root, entries(root).map { if (it.id == id) it.copy(content = content.trim(), explicit = true, manual = true,
            updated = System.currentTimeMillis()) else it }); write(root)
    }
    fun removeSource(conversation: String) = synchronized(lock) {
        val root = read(); bump(root)
        val gone = root.optJSONObject("deleted") ?: JSONObject(); gone.put(conversation, true); root.put("deleted", gone)
        putEntries(root, entries(root).map { it.copy(sources = it.sources.filterNot { s -> s.startsWith("$conversation:") }) }
            .filter { it.manual || (it.sources.isNotEmpty() && (it.explicit || it.category == "encountered" || it.sources.size >= 2)) })
        root.put("tentative", JSONArray().apply { entries(root, "tentative").filterNot { it.sources.any { s -> s.startsWith("$conversation:") } }.forEach { put(it.json()) } })
        write(root)
    }
    fun skip(conversation: String, count: Int) = synchronized(lock) {
        val root = read(); val cursors = root.optJSONObject("cursors") ?: JSONObject()
        cursors.put(conversation, maxOf(count, cursors.optInt(conversation))); root.put("cursors", cursors); write(root)
    }
    fun apply(revision: Long, conversation: String, turns: List<AiTurn>, through: Int, candidates: JSONArray): Boolean = synchronized(lock) {
        val root = read()
        if (root.optLong("revision") != revision || !root.optBoolean("learning", true) || root.optJSONObject("deleted")?.optBoolean(conversation) == true) return@synchronized false
        val cursors = root.optJSONObject("cursors") ?: JSONObject()
        val from = cursors.optInt(conversation)
        val list = entries(root).toMutableList()
        val tentative = entries(root, "tentative").toMutableList()
        for (i in 0 until minOf(20, candidates.length())) {
            val item = candidates.optJSONObject(i) ?: continue
            val category = item.optString("category")
            if (category !in setOf("preference", "habit", "recent", "encountered", "mastered")) continue
            val text = item.optString("content").trim()
            if (text.isBlank() || text.length > 160) continue
            val evidence = item.optJSONArray("evidence") ?: continue
            val positions = (0 until evidence.length()).map { evidence.optInt(it, -1) }.distinct()
                .filter { it in from until minOf(through, turns.size) && !turns[it].hidden &&
                    (turns[it].role == "user" || category == "encountered") }
            if (positions.isEmpty()) continue
            val explicit = item.optBoolean("explicit") && positions.any { turns[it].role == "user" && durable.containsMatchIn(turns[it].text) }
            if (category == "mastered" && positions.none { mastery.containsMatchIn(turns[it].text) }) continue
            val scope = item.optString("scope").take(60)
            val id = item.optString("id")
            val index = list.indexOfFirst { it.id == id || (it.category == category && it.content == text && it.scope == scope) }
            if (index >= 0 && list[index].manual) continue
            val pendingIndex = tentative.indexOfFirst { it.id == id || (it.category == category && it.content == text && it.scope == scope) }
            val old = list.getOrNull(index) ?: tentative.getOrNull(pendingIndex)
            val entry = MemoryEntry(old?.id ?: UUID.randomUUID().toString(), category, text, scope, explicit || old?.explicit == true,
                false, System.currentTimeMillis(), (old?.sources.orEmpty() + positions.map { "$conversation:$it" }).distinct().takeLast(20))
            if (pendingIndex >= 0) tentative.removeAt(pendingIndex)
            if (category != "encountered" && !entry.explicit && entry.sources.size < 2) tentative.add(entry)
            else if (index >= 0) list[index] = entry else list.add(entry)
        }
        putEntries(root, list.sortedWith(compareByDescending<MemoryEntry> { it.manual }.thenByDescending { it.updated }).take(40))
        root.put("tentative", JSONArray().apply { tentative.sortedByDescending { it.updated }.take(40).forEach { put(it.json()) } })
        cursors.put(conversation, through); root.put("cursors", cursors); write(root); true
    }
    fun select(question: String): String {
        val snapshot = snapshot()
        if (!snapshot.enabled) return ""
        val lower = question.lowercase()
        val chosen = snapshot.entries.map { e ->
            val terms = e.scope.lowercase().split(Regex("[ ,，、;；/]+" )).filter { it.length >= 2 }
            e to (if (terms.any { it in lower }) 4 else if (e.category in setOf("preference", "habit") && e.scope.isBlank()) 2 else 0)
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<MemoryEntry, Int>> { it.second }.thenByDescending { it.first.updated })
        val lines = chosen.take(12).map { pair ->
            val e = pair.first
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(e.updated))
            "[${e.category}; ${e.scope}; 更新于$date] ${e.content}"
        }
        return lines.joinToString("\n").take(1600)
    }
    companion object {
        private val lock = Any()
        private val durable = Regex("以后|今后|记住|我喜欢|我习惯|我偏好|我正在|我最近|我在学|我学过|我已经|一直|通常|总是|I prefer|I am learning|I'm learning|I have learned|always|remember", RegexOption.IGNORE_CASE)
        private val mastery = Regex("我(已经)?(掌握|学会)|I (have )?mastered", RegexOption.IGNORE_CASE)
    }
}
