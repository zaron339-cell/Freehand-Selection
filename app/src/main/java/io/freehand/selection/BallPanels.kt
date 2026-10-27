package io.freehand.selection

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.DateFormat
import java.util.Date

/** Small foreground overlays; they never launch an Activity or require a new capture. */
internal open class BallPanel(context: Context, private val close: () -> Unit) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        isFocusableInTouchMode = true
        setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        background = rounded(paper, context.dp(18).toFloat())
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) close()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { close(); return true }
        return super.onTouchEvent(event)
    }
}

internal class BallMenuView(
    context: Context, canCapture: Boolean, hasAnswer: Boolean,
    onHistory: () -> Unit, onCapture: () -> Unit, onRecover: () -> Unit, onClose: () -> Unit,
) : BallPanel(context, onClose) {
    init {
        addSpaced(context.label("快捷操作", 17f, true))
        addSpaced(context.action("历史对话", true, onHistory))
        if (canCapture) addSpaced(context.action("开始圈选", onClick = onCapture))
        if (hasAnswer) addSpaced(context.action("找回当前回答", onClick = onRecover))
        addView(context.action("收起", onClick = onClose))
    }
}

internal class BallHistoryView(
    context: Context, private val onResume: (String) -> Unit, onClose: () -> Unit,
) : BallPanel(context, onClose) {
    private val entries = LinearLayout(context).apply { orientation = VERTICAL }
    private var disposed = false
    private var records = emptyList<ConversationRecord>()
    private var visibleCount = 30
    init {
        val header = context.row()
        header.addWeighted(context.label("历史对话", 21f, true))
        header.addView(context.action("关闭", onClick = onClose))
        addView(header)
        addSpaced(context.label("选择一条记录，继续追问", 13f))
        addView(ScrollView(context).apply { addView(entries) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        entries.addSpaced(context.label("正在读取…", 14f))
        ConversationStore.async({ ConversationStore(context).list() }) { result ->
            if (!disposed) result.fold(onSuccess = { records = it; render() }, onFailure = {
                entries.removeAllViews()
                entries.addSpaced(context.label("读取失败：${it.localizedMessage}", 14f))
            })
        }
    }
    private fun render() {
        entries.removeAllViews()
        if (records.isEmpty()) entries.addSpaced(context.label("还没有对话，圈选并提问后会自动保存。", 15f))
        for (record in records.take(visibleCount)) {
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
                background = rounded(android.graphics.Color.WHITE, context.dp(12).toFloat())
            }
            card.addSpaced(context.label(record.title, 16f, true))
            card.addSpaced(context.label(DateFormat.getDateTimeInstance().format(Date(record.updated)) + if (record.pending) " · 未完成" else "", 12f))
            card.addView(context.action("继续对话", true) { onResume(record.id) })
            entries.addSpaced(card)
        }
        if (records.size > visibleCount) entries.addSpaced(context.action("显示更多") { visibleCount += 30; render() })
    }
    fun dispose() { disposed = true }
}
