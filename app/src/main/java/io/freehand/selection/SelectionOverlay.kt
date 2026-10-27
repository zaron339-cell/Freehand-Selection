package io.freehand.selection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import io.freehand.selection.geometry.SelectionMode
import io.freehand.selection.ai.AiProfileStore
import io.freehand.selection.ai.AiPromptStore

internal class SelectionOverlay(
    context: Context,
    private val screenshot: Bitmap,
    private val onClose: () -> Unit,
    private val onShare: (Bitmap, (String?) -> Unit) -> Unit,
    private val onAsk: (Bitmap, String) -> Unit,
) : FrameLayout(context) {
    private val preferences = context.getSharedPreferences("selection", Context.MODE_PRIVATE)
    private val canvas = SelectionCanvas(context, screenshot)
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(paper)
        visibility = View.GONE
        contentDescription = "实际选区预览，圈外内容已填白"
    }
    private val status = context.label("用笔或手指圈选内容，松手自动闭合", 14f)
    private val modes = context.row()
    private val freehand = context.action("自由圈选") { chooseMode(SelectionMode.FREEHAND) }
    private val rectangle = context.action("矩形框选") { chooseMode(SelectionMode.RECTANGLE) }
    private val preview = context.action("预览选区", true) { showPreview() }
    private val share: Button = context.action("分享图片", true) { sharePreview() }
    private val aiActions = context.row().apply {
        addWeighted(context.action("AI 解题", true) { ask(AiPromptStore(context).load().solve) })
        addWeighted(context.action("讲知识点", true) { ask(AiPromptStore(context).load().explain) })
    }
    private val modelButton = context.action("模型与接口") { openSettings() }
    private val promptButton = context.action("提示词") { openPrompts() }
    private val modelRow = context.row().apply {
        addWeighted(modelButton)
        addView(promptButton)
    }
    private val topPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
        background = rounded(paper, context.dp(20).toFloat())
        elevation = context.dp(8).toFloat()
    }
    private val bottomPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
        background = rounded(paper, context.dp(20).toFloat())
        elevation = context.dp(8).toFloat()
    }
    private var previewBitmap: Bitmap? = null
    private var released = false
    private var settings: AiSettingsView? = null
    private var promptSettings: AiPromptSettingsView? = null

    init {
        isFocusableInTouchMode = true
        setBackgroundColor(Color.rgb(23, 31, 29))
        addView(canvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        topPanel.addView(context.label("圈选 · Freehand", 16f, true))
        modes.addWeighted(freehand)
        modes.addWeighted(rectangle)
        modes.addWeighted(context.action("重画") { reset() })
        topPanel.addView(modes, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(36)))
        addView(topPanel, LayoutParams(context.dp(340), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(context.dp(12), context.dp(12), context.dp(12), 0)
        })
        bottomPanel.addView(status)
        bottomPanel.addView(modelRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(36)))
        bottomPanel.addView(aiActions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(36)))
        val actions = context.row()
        actions.addWeighted(context.action("关闭") { onClose() })
        actions.addWeighted(preview)
        actions.addWeighted(share)
        bottomPanel.addView(actions)
        addView(bottomPanel, LayoutParams(context.dp(460), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(context.dp(12), 0, context.dp(12), context.dp(12))
        })
        compact(topPanel)
        compact(bottomPanel)
        draggable(topPanel.getChildAt(0), topPanel)
        draggable(status, bottomPanel)
        share.visibility = View.GONE
        aiActions.visibility = View.GONE
        modelRow.visibility = View.GONE
        canvas.onSelectionChanged = { valid ->
            preview.isEnabled = valid
            preview.alpha = if (valid) 1f else .45f
            status.text = if (valid) "选区已就绪，可预览或重新画" else "用笔或手指选择，松手后可预览"
        }
        val saved = preferences.getString("mode", SelectionMode.FREEHAND.name)
        chooseMode(SelectionMode.entries.firstOrNull { it.name == saved } ?: SelectionMode.FREEHAND)
        setOnApplyWindowInsetsListener { _, insets ->
            val insetTop: Int
            val insetBottom: Int
            val insetLeft: Int
            val insetRight: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                insetTop = bars.top; insetBottom = bars.bottom; insetLeft = bars.left; insetRight = bars.right
            } else {
                @Suppress("DEPRECATION")
                run {
                    insetTop = insets.systemWindowInsetTop; insetBottom = insets.systemWindowInsetBottom
                    insetLeft = insets.systemWindowInsetLeft; insetRight = insets.systemWindowInsetRight
                }
            }
            (topPanel.layoutParams as LayoutParams).apply {
                topMargin = context.dp(12) + insetTop
                leftMargin = context.dp(12) + insetLeft
                rightMargin = context.dp(12) + insetRight
                topPanel.layoutParams = this
            }
            (bottomPanel.layoutParams as LayoutParams).apply {
                bottomMargin = context.dp(12) + insetBottom
                leftMargin = context.dp(12) + insetLeft
                rightMargin = context.dp(12) + insetRight
                bottomPanel.layoutParams = this
            }
            insets
        }
    }

    private fun compact(view: View) {
        if (view is Button) {
            view.minHeight = 0; view.minimumHeight = 0
            view.textSize = 13f
            view.setPadding(context.dp(5), 0, context.dp(5), 0)
            view.layoutParams?.let { it.height = context.dp(36); view.layoutParams = it }
        }
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) compact(view.getChildAt(i))
    }

    private fun draggable(handle: View, panel: View) {
        handle.contentDescription = "拖动工具条"
        var dx = 0f; var dy = 0f
        handle.setOnTouchListener { _, event ->
            if (image.visibility == View.VISIBLE) return@setOnTouchListener false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { dx = event.rawX - panel.x; dy = event.rawY - panel.y }
                android.view.MotionEvent.ACTION_MOVE -> {
                    panel.translationX = (event.rawX - dx).coerceIn(0f, (width - panel.width).coerceAtLeast(0).toFloat()) - panel.left
                    panel.translationY = (event.rawY - dy).coerceIn(0f, (height - panel.height).coerceAtLeast(0).toFloat()) - panel.top
                }
            }
            true
        }
    }

    private fun chooseMode(mode: SelectionMode) {
        reset()
        canvas.mode = mode
        preferences.edit().putString("mode", mode.name).apply()
        freehand.alpha = if (mode == SelectionMode.FREEHAND) 1f else .55f
        rectangle.alpha = if (mode == SelectionMode.RECTANGLE) 1f else .55f
        freehand.isSelected = mode == SelectionMode.FREEHAND
        rectangle.isSelected = mode == SelectionMode.RECTANGLE
    }

    private fun sharePreview() {
        previewBitmap?.let { bitmap ->
            share.isEnabled = false
            status.text = "正在准备图片…"
            onShare(bitmap) { error ->
                if (!released) {
                    share.isEnabled = true
                    status.text = error ?: "图片已准备好，可在分享面板中选择应用"
                }
            }
        }
    }

    private fun refreshModel() {
        val profile = AiProfileStore(context).active()
        modelButton.text = "${profile.name} · 设置 / 切换"
        previewBitmap?.let { status.text = "${it.width} × ${it.height} · 提问后仅发送此选区至 ${profile.host}" }
    }

    private fun ask(question: String) {
        val bitmap = previewBitmap ?: return
        val profile = AiProfileStore(context).active()
        if (profile.keyUnavailable || (profile.apiKey.isBlank() && profile.id in setOf("zhipu-free", "deepseek", "gemini"))) {
            openSettings()
            return
        }
        val error = runCatching { profile.validate() }.exceptionOrNull()
        if (error != null) { status.text = error.localizedMessage; return }
        onAsk(bitmap, question)
    }

    private fun openSettings() {
        if (settings != null || promptSettings != null || released) return
        val view = AiSettingsView(context) {
            settings?.let { removeView(it) }
            settings = null
            refreshModel()
        }
        settings = view
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        view.requestFocus()
    }

    private fun openPrompts() {
        if (settings != null || promptSettings != null || released) return
        val view = AiPromptSettingsView(context) {
            promptSettings?.let { removeView(it) }
            promptSettings = null
        }
        promptSettings = view
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        view.requestFocus()
    }

    private fun reset() {
        image.setImageDrawable(null)
        previewBitmap?.recycle()
        previewBitmap = null
        canvas.visibility = View.VISIBLE
        image.visibility = View.GONE
        preview.visibility = View.VISIBLE
        compact(topPanel)
        compact(bottomPanel)
        draggable(topPanel.getChildAt(0), topPanel)
        draggable(status, bottomPanel)
        share.visibility = View.GONE
        aiActions.visibility = View.GONE
        modelRow.visibility = View.GONE
        canvas.clearSelection()
    }

    private fun showPreview() {
        val result = canvas.crop() ?: return
        // Preview has a dedicated space between controls; return moved selection bars to their docks.
        for (panel in listOf(topPanel, bottomPanel)) { panel.translationX = 0f; panel.translationY = 0f }
        previewBitmap = result
        image.setImageBitmap(result)
        canvas.visibility = View.GONE
        image.visibility = View.VISIBLE
        // Keep the entire cropped image visible between controls, rather than behind them.
        (image.layoutParams as LayoutParams).apply {
            topMargin = topPanel.bottom + context.dp(12)
            bottomMargin = height - bottomPanel.top + context.dp(12)
            image.layoutParams = this
        }
        preview.visibility = View.GONE
        share.visibility = View.VISIBLE
        aiActions.visibility = View.VISIBLE
        modelRow.visibility = View.VISIBLE
        refreshModel()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Only the cropped preview fits between controls; selection uses the full screenshot.
        for (panel in listOf(topPanel, bottomPanel)) {
            val lp = panel.layoutParams as LayoutParams
            val maximum = (width - paddingLeft - paddingRight - lp.leftMargin - lp.rightMargin).coerceAtLeast(1)
            val desired = minOf(context.dp(if (panel === topPanel) 340 else 460), maximum)
            if (lp.width != desired) { lp.width = desired; panel.layoutParams = lp }
        }
        val upper = this.topPanel.bottom + context.dp(8)
        val lower = height - this.bottomPanel.top + context.dp(8)
        for (view in listOf(image)) {
            val params = view.layoutParams as LayoutParams
            if (params.topMargin != upper || params.bottomMargin != lower) {
                params.topMargin = upper
                params.bottomMargin = lower
                view.layoutParams = params
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (promptSettings != null) {
                    promptSettings?.let { removeView(it) }
                    promptSettings = null
                } else if (settings != null) {
                    settings?.let { removeView(it) }
                    settings = null
                    refreshModel()
                } else onClose()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun release() {
        if (released) return
        released = true
        settings?.dispose()
        settings = null
        promptSettings = null
        image.setImageDrawable(null)
        previewBitmap?.recycle()
        previewBitmap = null
        screenshot.recycle()
    }
}
