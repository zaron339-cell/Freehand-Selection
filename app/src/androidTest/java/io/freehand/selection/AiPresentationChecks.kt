package io.freehand.selection

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.LeadingMarginSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import io.freehand.selection.ai.AiProfile
import io.freehand.selection.ai.AiProfileStore
import io.freehand.selection.ai.AiWire
import io.freehand.selection.ai.AiTurn
import io.freehand.selection.ai.AiPromptStore
import io.freehand.selection.ai.AiPrompts
import io.freehand.selection.ai.AiImage
import io.freehand.selection.ai.AiProtocol
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.min
import org.json.JSONObject

/** Exercises visible conversation behavior through real Views and a local HTTP response. */
internal class AiPresentationChecks(private val instrument: Instrumentation) {
    private val failures = mutableListOf<String>()
    private var checks = 0
    private fun expect(condition: Boolean, label: String) {
        checks++
        if (!condition) failures.add(label)
    }

    fun run(): Int {
        expect(AiWire.SYSTEM == "你是一位学习助手，请根据用户提供的内容和问题提供帮助。", "default prompt still prescribes an output structure or subject")
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
        val prefix = "presentation-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(activity) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = activity.getSharedPreferences("$prefix-$name", mode)
        }
        instrument.runOnMainSync {
            val target = TextView(context)
            val repeated = AiTurn("assistant", "**重复重点**", assistantName = "智谱")
            ConversationRenderer(context).show(target, listOf(repeated, repeated), null, false, "智谱", "", false)
            expect(isBoldAt(target.text, "重复重点") && isBoldAt(target.text, "重复重点", last = true),
                "identical answers lost one message's bold spans")
            val list = AiTurn("assistant", "- 第一项\n- 第二项", assistantName = "智谱")
            val renderer = ConversationRenderer(context)
            renderer.show(target, listOf(list), null, false, "智谱", "", false)
            expect(blockStartsAtParagraph(target.text, "第一项"), "completed leading list lost its first marker after the author label")
            renderer.show(target, emptyList(), "hidden", true, "智谱", "- 第一项\n- 第二项", false)
            expect(blockStartsAtParagraph(target.text, "第一项"), "streamed leading list lost its first marker after the author label")
        }
        val system = "按用户需要处理任何领域的内容，自行选择表达方式。"
        context.getSharedPreferences("ai-prompts", Context.MODE_PRIVATE).edit()
            .putString("system", system).putString("solve", "只翻译选区内容。")
            .putString("explain", "结合历史背景讲解选区。自选形式。").commit()
        var answer: AiConversationView? = null
        try {
            Fixture().use { server ->
                val store = AiProfileStore(context)
                val first = AiProfile(id = "presentation", name = "用户配置 A", baseUrl = "http://127.0.0.1:${server.port}/v1",
                    model = "glm-4.6v-flash", apiKey = "fixture-key", allowHttp = true, timeoutSeconds = 15)
                store.save(first)
                instrument.runOnMainSync {
                    expect(descendants(activity.window.decorView).filterIsInstance<Button>().any { it.text == "提示词设置" }, "home has no custom prompt entry")
                    expect(descendants(activity.window.decorView).filterIsInstance<Button>().any { it.text == "历史对话" }, "home has no conversation history entry")
                    val bitmap = image()
                    val panel = AiConversationView(context, bitmap, "AUTO-PROMPT-DO-NOT-DISPLAY", persistHistory = false) {}
                    bitmap.recycle()
                    answer = panel
                    activity.setContentView(panel)
                }
                awaitUi { texts(answer!!).contains("重要观点") }
                instrument.runOnMainSync {
                    val text = conversation(answer!!)
                    expect(descendants(answer!!).any { it.contentDescription == "调整窗口宽高" }, "answer window has no resize grip")
                    val plain = text.text.toString()
                    expect(!plain.contains("AUTO-PROMPT-DO-NOT-DISPLAY"), "automatic initial prompt is visible during streaming")
                    expect(plain.contains("智谱：") && !plain.contains("AI："), "streaming reply has no actual provider label")
                    expect(!plain.contains("### 背景") && !plain.contains("**重要观点**"), "Markdown syntax is displayed literally")
                    expect(isBoldAt(text.text, "重要观点"), "important phrase has no native bold span")
                }
                server.finishFirst.countDown()
                awaitUi { texts(answer!!).contains("回答完成") }
                val request = server.requests.first()
                expect(request.getJSONArray("messages").getJSONObject(0).getString("content") == system, "custom system prompt did not reach the API")
                expect(request.toString().contains("AUTO-PROMPT-DO-NOT-DISPLAY"), "hidden first question was lost from network context")
                instrument.runOnMainSync {
                    expect(conversation(answer!!).text.contains("**代码**"), "literal Markdown inside code was damaged")
                }
                store.save(first.copy(id = "second", name = "用户配置 B", model = "deepseek-flash"))
                instrument.runOnMainSync {
                    descendants(answer!!).filterIsInstance<EditText>().first().setText("真正的手动追问")
                    button(answer!!, "发送").performClick()
                }
                awaitUi { texts(answer!!).contains("回答完成") && server.requests.size == 2 && conversation(answer!!).text.contains("第二次回答") }
                instrument.runOnMainSync {
                    val text = conversation(answer!!).text.toString()
                    expect(text.contains("你：真正的手动追问"), "manual follow-up disappeared")
                    expect(!text.contains("AUTO-PROMPT-DO-NOT-DISPLAY"), "automatic prompt reappeared after follow-up")
                    expect(text.contains("智谱：") && text.contains("DeepSeek：") && !text.contains("AI："), "provider switch relabeled old replies or used a generic author")
                    answer!!.release()
                    answer = null
                }
                instrument.runOnMainSync {
                    val restored = ConversationRecord(UUID.randomUUID().toString(), 123L, "AUTO-PROMPT-DO-NOT-DISPLAY",
                        listOf(AiTurn("user", "AUTO-PROMPT-DO-NOT-DISPLAY", hidden = true), AiTurn("assistant", "旧回答", assistantName = "智谱")),
                        "second", "中断的问题", false, "DeepSeek", "尚未完成的回答片段", true, "草稿内容", null)
                    val bitmap = image()
                    answer = AiConversationView(context, bitmap, restored.initialQuestion, restored, persistHistory = false) {}
                    bitmap.recycle()
                    activity.setContentView(answer)
                }
                instrument.waitForIdleSync()
                instrument.runOnMainSync {
                    expect(server.requests.size == 2, "restoring history automatically sent a request")
                    expect(texts(answer!!).contains("尚未完成的回答片段") && texts(answer!!).contains("草稿内容"), "restored pending answer or draft disappeared")
                    expect(button(answer!!, "重试").isEnabled, "interrupted restored turn cannot be retried")
                    descendants(answer!!).filterIsInstance<EditText>().first().setText("新的追问")
                    button(answer!!, "发送").performClick()
                }
                awaitUi { server.requests.size == 3 && texts(answer!!).contains("回答完成") }
                instrument.runOnMainSync {
                    expect(texts(answer!!).contains("中断的问题") && texts(answer!!).contains("尚未完成的回答片段"), "new follow-up erased the interrupted turn")
                    expect(!texts(answer!!).contains("AUTO-PROMPT-DO-NOT-DISPLAY"), "restoring revealed the automatic prompt")
                    answer!!.release(); answer = null
                }
                val resumedRequest = server.requests.last().toString()
                expect(resumedRequest.contains("中断的问题") && resumedRequest.contains("尚未完成的回答片段") && resumedRequest.contains("新的追问"),
                    "resumed follow-up omitted interrupted context")
                var captured = ""
                var overlay: SelectionOverlay? = null
                instrument.runOnMainSync {
                    overlay = SelectionOverlay(context, image(), {}, { _, _ -> }, { _, question -> captured = question })
                    activity.setContentView(overlay)
                }
                instrument.waitForIdleSync()
                instrument.runOnMainSync {
                    val root = overlay!!
                    button(root, "矩形框选").performClick()
                    val canvas = descendants(root).filterIsInstance<SelectionCanvas>().first()
                    expect(canvas.width == root.width && canvas.height == root.height, "selection screenshot is shrunk between toolbars")
                    val scale = min(canvas.width / 200f, canvas.height / 200f)
                    val left = (canvas.width - 200 * scale) / 2f
                    val top = (canvas.height - 200 * scale) / 2f
                    fun touch(action: Int, offset: Float) {
                        val event = MotionEvent.obtain(0, 0, action, left + offset * scale, top + offset * scale, 0)
                        canvas.onTouchEvent(event); event.recycle()
                    }
                    touch(MotionEvent.ACTION_DOWN, 20f); touch(MotionEvent.ACTION_UP, 160f)
                    button(root, "预览选区").performClick()
                    button(root, "AI 解题").performClick()
                    expect(captured == "只翻译选区内容。", "solve action ignores user-defined prompt")
                    button(root, "讲知识点").performClick()
                    expect(captured == "结合历史背景讲解选区。自选形式。", "explain action ignores user-defined prompt")
                    root.release()
                }
                val prompts = AiPromptStore(context)
                prompts.save(AiPrompts("", "", ""))
                val empty = prompts.load()
                expect(empty.system.isEmpty() && empty.solve == AiPrompts.DEFAULT_SOLVE && empty.explain == AiPrompts.DEFAULT_EXPLAIN,
                    "blank system/action prompt behavior is incorrect")
                for (protocol in AiProtocol.entries) {
                    val body = AiWire.body(first.copy(protocol = protocol), emptyList(), "问题", AiImage("fixture"), "")
                    val omitted = when (protocol) {
                        AiProtocol.CHAT -> body.getJSONArray("messages").getJSONObject(0).getString("role") == "user"
                        AiProtocol.GEMINI -> !body.has("systemInstruction")
                        AiProtocol.ANTHROPIC -> !body.has("system")
                    }
                    expect(omitted, "$protocol sent a system field despite an empty custom prompt")
                    val custom = AiWire.body(first.copy(protocol = protocol), emptyList(), "问题", AiImage("fixture"), "CUSTOM-SYSTEM")
                    expect(custom.toString().contains("CUSTOM-SYSTEM"), "$protocol omitted the custom system prompt")
                }
                instrument.runOnMainSync {
                    var saved = false
                    val promptView = AiPromptSettingsView(context) { saved = true }
                    activity.setContentView(promptView)
                    button(promptView, "恢复默认").performClick()
                    val editors = descendants(promptView).filterIsInstance<EditText>()
                    expect(editors.map { it.text.toString() } == listOf(AiPrompts.DEFAULT_SYSTEM, AiPrompts.DEFAULT_SOLVE, AiPrompts.DEFAULT_EXPLAIN),
                        "restore defaults did not update all prompt fields")
                    button(promptView, "保存").performClick()
                    expect(saved && prompts.load() == AiPrompts(), "prompt settings did not persist restored defaults")
                }
            }
        } finally {
            instrument.runOnMainSync { answer?.release(); activity.finish() }
            for (name in listOf("ai-profiles", "ai-prompts", "selection")) activity.deleteSharedPreferences("$prefix-$name")
        }
        check(failures.isEmpty()) { failures.joinToString("\n", prefix = "Presentation regressions:\n") }
        return checks
    }

    private fun image() = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun texts(root: View) = descendants(root).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun conversation(root: View) = descendants(root).filterIsInstance<TextView>().first { it.text.contains("重要观点") }
    private fun button(root: View, text: String) = descendants(root).filterIsInstance<Button>().first { it.text == text }
    private fun isBoldAt(value: CharSequence, word: String, last: Boolean = false): Boolean {
        val start = if (last) value.lastIndexOf(word) else value.indexOf(word)
        if (value !is Spanned || start < 0) return false
        val paint = TextPaint().apply { typeface = Typeface.DEFAULT }
        value.getSpans(start, start + word.length, MetricAffectingSpan::class.java).forEach { it.updateMeasureState(paint) }
        return paint.isFakeBoldText || paint.typeface?.isBold == true
    }
    private fun blockStartsAtParagraph(value: CharSequence, word: String): Boolean {
        if (value !is Spanned) return false
        val start = value.indexOf(word)
        return value.getSpans(start, start + word.length, LeadingMarginSpan::class.java).any { span ->
            val boundary = value.getSpanStart(span)
            boundary == 0 || (boundary > 0 && value[boundary - 1] == '\n')
        }
    }
    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var ready = false
            instrument.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(30)
        }
        error("Presentation UI did not reach the expected state")
    }

    private class Fixture : Closeable {
        private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val requests = CopyOnWriteArrayList<JSONObject>()
        val finishFirst = CountDownLatch(1)
        init { thread(isDaemon = true, name = "presentation-fixture") {
            try {
                server.soTimeout = 15_000
                repeat(3) { index -> server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) { val n = input.read(); check(n >= 0); headers.append(n.toChar()) }
                    val size = headers.toString().lineSequence().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    val data = ByteArray(size)
                    var position = 0
                    while (position < size) { val n = input.read(data, position, size - position); check(n > 0); position += n }
                    requests.add(JSONObject(String(data, Charsets.UTF_8)))
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                    fun delta(text: String) {
                        val event = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text))))
                        output.write("data: $event\n\n".toByteArray(Charsets.UTF_8)); output.flush()
                    }
                    if (index == 0) {
                        delta("### 背景\n\n这里有**重要观点**与*斜体*。\n\n")
                        finishFirst.await(12, TimeUnit.SECONDS)
                        delta("- 第一项\n\n代码：`**代码**`。")
                    } else delta("第二次回答：**仍然加粗**。")
                    output.write("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n".toByteArray()); output.flush()
                } }
            } catch (_: Exception) { finishFirst.countDown() }
        } }
        override fun close() { finishFirst.countDown(); server.close() }
    }
}
