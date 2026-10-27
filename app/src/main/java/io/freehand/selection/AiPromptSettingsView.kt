package io.freehand.selection

import android.content.Context
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import io.freehand.selection.ai.AiPrompts
import io.freehand.selection.ai.AiPromptStore

internal class AiPromptSettingsView(context: Context, onClose: () -> Unit) : ScrollView(context) {
    private val store = AiPromptStore(context)
    private val system = editor()
    private val solve = editor()
    private val explain = editor()
    private val status = context.label("保存后用于后续请求。自动首问只发送给模型，不显示在聊天里。", 14f)

    init {
        setBackgroundColor(paper)
        isFillViewport = true
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), context.dp(24), context.dp(20), context.dp(24))
        }
        addView(column)
        column.addSpaced(context.label("提示词设置", 25f, true))
        column.addSpaced(context.label("以下设置由全部模型共用。可自由定义任务、语气和输出方式，也可以保留简短默认值，让模型自行组织回答。", 14f))
        column.addSpaced(context.label("通用提示词（所有模型）", 16f, true))
        column.addSpaced(system)
        column.addSpaced(context.label("通用提示词留空时，不发送系统提示词。", 13f))
        column.addSpaced(context.label("AI 解题按钮提示词", 16f, true))
        column.addSpaced(solve)
        column.addSpaced(context.label("讲知识点按钮提示词", 16f, true))
        column.addSpaced(explain)
        column.addSpaced(context.label("按钮提示词留空时使用对应的简短默认值。手动追问由你直接输入。", 13f))
        column.addSpaced(status)
        val actions = context.row()
        actions.addWeighted(context.action("返回") { onClose() })
        actions.addWeighted(context.action("恢复默认") {
            fill(AiPrompts())
            status.text = "已填入默认提示词，点击保存后生效"
        })
        actions.addWeighted(context.action("保存", true) {
            runCatching { store.save(AiPrompts(system.text.toString(), solve.text.toString(), explain.text.toString())) }
                .onSuccess {
                    context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
                    onClose()
                }.onFailure { status.text = it.localizedMessage ?: "保存失败" }
        })
        column.addSpaced(actions)
        fill(store.load())
        setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(edges.left, edges.top, edges.right, edges.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    private fun editor() = EditText(context).apply {
        textSize = 15f
        setTextColor(ink)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 3
        maxLines = 6
        gravity = android.view.Gravity.TOP
        filters = arrayOf(InputFilter.LengthFilter(8000))
        setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
    }

    private fun fill(prompts: AiPrompts) {
        system.setText(prompts.system)
        solve.setText(prompts.solve)
        explain.setText(prompts.explain)
    }
}
