package io.freehand.selection

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import io.freehand.selection.ai.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Real network and UI checks for delayed extraction and failed/resumed compaction. */
internal class MemoryFlowChecks(private val instrument: Instrumentation) {
    fun run(): Int {
        var checks = 0
        fun expect(ok: Boolean, message: String) { check(ok) { message }; checks++ }
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
        val prefix = "memory-flow-${UUID.randomUUID()}"
        val directory = File(activity.cacheDir, prefix)
        val context = object : ContextWrapper(activity) {
            override fun getSharedPreferences(name: String?, mode: Int) = activity.getSharedPreferences("$prefix-$name", mode)
            override fun getFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
        var panel: AiConversationView? = null
        try {
            val gate = CountDownLatch(1)
            Fixture(2) { index, _ ->
                if (index == 0) gate.await(8, TimeUnit.SECONDS)
                val memory = if (index == 0) JSONObject().put("category", "preference").put("content", "偏好简短回答")
                    .put("scope", "").put("explicit", true).put("evidence", JSONArray().put(0))
                else JSONObject().put("category", "recent").put("content", "正在学习定语从句")
                    .put("scope", "定语从句").put("explicit", true).put("evidence", JSONArray().put(2))
                200 to JSONObject().put("memories", JSONArray().put(memory)).toString()
            }.use { server ->
                val profile = profile(server.port)
                val first = listOf(AiTurn("user", "以后回答请简短"), AiTurn("assistant", "好的"))
                instrument.runOnMainSync { MemoryCoordinator.learn(context, "drain", first, profile) }
                await { server.requests.size == 1 }
                instrument.runOnMainSync { MemoryCoordinator.learn(context, "drain", first + listOf(
                    AiTurn("user", "我正在学定语从句"), AiTurn("assistant", "定语从句修饰名词")), profile, closing = true) }
                gate.countDown()
                await { UserMemoryStore(context).snapshot().entries.size == 2 }
                expect(server.requests.size == 2, "closing while extraction is pending lost the final turn")
                expect(server.requests.none { it.toString().contains("image_url") }, "background extraction uploaded a screenshot")
                expect(UserMemoryStore(context).select("请回答").contains("偏好简短"), "cross-conversation generic preference not available")
            }
            UserMemoryStore(context).configure(enabled = false, learning = false)
            Fixture(1) { _, _ -> 200 to "首问已处理" }.use { server ->
                AiProfileStore(context).save(profile(server.port))
                instrument.runOnMainSync {
                    val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
                    panel = AiConversationView(context, image, "请翻译这句话", persistHistory = false, hiddenInitial = false) {}
                    image.recycle(); activity.setContentView(panel)
                }
                awaitUi { text(panel!!).contains("首问已处理") }
                instrument.runOnMainSync { expect(text(panel!!).contains("你：请翻译这句话"), "explicit screenshot request was hidden") }
                expect(server.requests.single().toString().contains("请翻译这句话") && server.requests.single().toString().contains("image_url"), "explicit screenshot request did not accompany the selected image")
                instrument.runOnMainSync { panel!!.release(); panel = null }
            }
            Fixture(3) { index, _ -> if (index == 0) 500 to "retry fixture" else 200 to if (index == 1) "摘要：此前讨论基本概念" else "正常的新回答" }.use { server ->
                AiProfileStore(context).save(profile(server.port))
                val old = List(26) { AiTurn(if (it % 2 == 0) "user" else "assistant", "old-turn-$it") }
                val record = ConversationRecord(UUID.randomUUID().toString(), 1, "original", old, "memory-flow", "", false, "测试", "", false, "", null)
                instrument.runOnMainSync {
                    val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
                    ConversationStore(context).save(record, image)
                    panel = AiConversationView(context, image, "original", record) {}
                    image.recycle(); activity.setContentView(panel)
                    views(panel!!).filterIsInstance<EditText>().first().setText("解释原因")
                    button(panel!!, "发送").performClick()
                }
                awaitUi { text(panel!!).contains("对话整理失败") }
                instrument.runOnMainSync {
                    expect(button(panel!!, "重试").isEnabled, "failed summary cannot be retried")
                    button(panel!!, "重试").performClick()
                }
                awaitUi { text(panel!!).contains("正常的新回答") && text(panel!!).contains("回答完成") }
                expect(server.requests.size == 3, "summary retry duplicated or omitted requests")
                val network = server.requests.last().toString()
                expect(network.contains("此前讨论基本概念") && network.contains("old-turn-25") && !network.contains("old-turn-0\""), "compacted network context omitted summary/recent turns or sent old full transcript")
                instrument.runOnMainSync { expect(text(panel!!).contains("old-turn-0"), "compaction erased visible old history") }
                await { runCatching { ConversationStore(context).load(record.id).let { (saved, bitmap) -> bitmap.recycle(); saved.summaryThrough == 18 && saved.turns.size == 28 } }.getOrDefault(false) }
                expect(UserMemoryStore(context).snapshot().cursors[record.id] == 28, "restored legacy history did not establish extraction baseline")
                instrument.runOnMainSync { panel!!.release(); panel = null }
            }
        } finally {
            instrument.runOnMainSync { panel?.release(); activity.finish() }
            directory.deleteRecursively()
            listOf("user-memory", "ai-profiles", "ai-prompts").forEach { activity.deleteSharedPreferences("$prefix-$it") }
        }
        return checks
    }
    private fun profile(port: Int) = AiProfile(id = "memory-flow", name = "测试", model = "local-fixture", baseUrl = "http://127.0.0.1:$port/v1", stream = false, allowHttp = true, timeoutSeconds = 15)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun button(view: View, name: String) = views(view).filterIsInstance<Button>().first { it.text == name }
    private fun text(view: View) = views(view).filterIsInstance<TextView>().joinToString("\n") { it.text }
    private fun awaitUi(test: () -> Boolean) = await { var ok = false; instrument.runOnMainSync { ok = test() }; ok }
    private fun await(test: () -> Boolean) { val deadline = System.currentTimeMillis() + 15000; while (System.currentTimeMillis() < deadline) { if (test()) return; Thread.sleep(30) }; error("Memory flow did not reach expected state") }
    private class Fixture(count: Int, respond: (Int, JSONObject) -> Pair<Int, String>) : Closeable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val requests = CopyOnWriteArrayList<JSONObject>()
        init { thread(isDaemon = true) { runCatching {
            server.soTimeout = 20000
            repeat(count) { index -> server.accept().use { socket ->
                val input = socket.getInputStream(); val headers = StringBuilder()
                while (!headers.endsWith("\r\n\r\n")) { val byte = input.read(); check(byte >= 0); headers.append(byte.toChar()) }
                val size = headers.lineSequence().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                val bytes = ByteArray(size); var cursor = 0
                while (cursor < size) { val read = input.read(bytes, cursor, size - cursor); check(read > 0); cursor += read }
                val request = JSONObject(String(bytes, Charsets.UTF_8)); requests.add(request)
                val (code, content) = respond(index, request)
                val body = if (code == 200) JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", content)).put("finish_reason", "stop"))).toString() else content
                val response = body.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply { write("HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush() }
            } }
        } } }
        override fun close() = server.close()
    }
}
