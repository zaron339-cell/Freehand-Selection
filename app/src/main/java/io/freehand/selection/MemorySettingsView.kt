package io.freehand.selection

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import io.freehand.selection.ai.UserMemoryStore

internal class MemorySettingsView(context: Context, private val onClose: () -> Unit) : ScrollView(context) {
    private val store = UserMemoryStore(context)
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(18), context.dp(22), context.dp(18), context.dp(22))
    }
    private val status = context.label("", 13f)
    init {
        setBackgroundColor(paper); isFillViewport = true; addView(column); render()
        setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
    }
    private fun change(work: () -> Unit) {
        runCatching(work).onSuccess { status.text = "已保存"; render() }
            .onFailure { status.text = it.localizedMessage ?: "保存失败" }
    }
    private fun render() {
        val state = store.snapshot()
        column.removeAllViews()
        column.addSpaced(context.label("用户记忆", 25f, true))
        column.addSpaced(context.label("记忆保存在本机，所有模型配置共用。提炼和回答会把必要文本发送给当前模型接口，后台提炼会额外使用 API 配额。", 14f))
        val use = CheckBox(context).apply { text = "回答时参考记忆"; isChecked = state.enabled }
        val learn = CheckBox(context).apply { text = "自动提炼新对话中的记忆"; isChecked = state.learning }
        use.setOnCheckedChangeListener { _, checked -> change { store.configure(checked, learn.isChecked) } }
        learn.setOnCheckedChangeListener { _, checked -> change { store.configure(use.isChecked, checked) } }
        column.addView(use); column.addView(learn)
        column.addSpaced(context.label("简短记录回复偏好、学习习惯及学习主题。单次需求不代表长期偏好，接触过也不代表已掌握。", 14f))
        column.addSpaced(status)
        val row = context.row()
        row.addWeighted(context.action("返回") { onClose() })
        row.addWeighted(context.action("清空记忆") { change { store.clear() } })
        column.addView(row)
        if (state.entries.isEmpty()) column.addSpaced(context.label("还没有记忆。继续使用后会逐步提炼；也可以在对话中明确说明你的习惯。", 15f))
        state.entries.forEach { entry ->
            val kind = when (entry.category) {
                "preference" -> "回复偏好"; "habit" -> "学习习惯"; "recent" -> "近期学习"
                "mastered" -> "自述已掌握"; else -> "曾接触"
            }
            column.addSpaced(context.label(kind + entry.scope.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), 15f, true))
            val editor = EditText(context).apply {
                textSize = 15f; setTextColor(ink); setText(entry.content); maxLines = 4
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                filters = arrayOf(InputFilter.LengthFilter(160))
            }
            column.addView(editor)
            val actions = context.row()
            actions.addWeighted(context.action("保存修改") { change { store.edit(entry.id, editor.text.toString()) } })
            actions.addWeighted(context.action("删除") { change { store.delete(entry.id) } })
            column.addView(actions)
        }
    }
}

class MemorySettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(MemorySettingsView(this) { finish() })
    }
}
