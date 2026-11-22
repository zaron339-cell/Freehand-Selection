package io.freehand.selection

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.window.OnBackInvokedDispatcher
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.freehand.selection.ai.AiProfileStore

class MainActivity : Activity() {
    private var practice: SelectionOverlay? = null
    private var requesting = false
    private var practiceAnswer: AiConversationView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderHome()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (practiceAnswer != null) closePracticeAnswer() else if (practice != null) closePractice() else finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (practice == null) renderHome()
    }

    private fun renderHome() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(paper)
            isFillViewport = true
            clipToPadding = false
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        scroll.addView(column)
        column.addSpaced(label("FREEHAND / 圈选 AI", 12f, true).apply { setTextColor(green) })
        column.addSpaced(label("圈出问题，\n留住思路。", 34f, true))
        column.addSpaced(label("在任何允许截屏的应用里，\n用手指或笔，选择你想了解的内容。", 17f))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, dp(24).toFloat())
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        card.addSpaced(label(if (CaptureService.isRunning) "学习会话已开启" else "从一次圈选开始", 21f, true))
        card.addSpaced(label("自由圈选 · 矩形框选\n手指可用，手写笔可选", 16f))
        card.addSpaced(action(if (CaptureService.isRunning) "回到当前应用使用悬浮球" else "开启悬浮球", true) {
            if (CaptureService.isRunning) moveTaskToBack(true) else beginSession()
        })
        card.addSpaced(action("先在练习页试一试") { showPractice() })
        val selectedModel = AiProfileStore(this).active()
        card.addSpaced(action("AI 模型设置 · ${selectedModel.name}") {
            startActivity(Intent(this, AiSettingsActivity::class.java))
        })
        card.addSpaced(action("历史对话") { startActivity(Intent(this, HistoryActivity::class.java)) })
        card.addSpaced(action("用户记忆") { startActivity(Intent(this, MemorySettingsActivity::class.java)) })
        card.addSpaced(action("提示词设置") {
            startActivity(Intent(this, AiPromptSettingsActivity::class.java))
        })
        if (CaptureService.isServiceActive) card.addSpaced(action("结束学习会话") {
            stopService(Intent(this, CaptureService::class.java))
            window.decorView.postDelayed({ if (!isDestroyed) renderHome() }, 150)
        })
        column.addSpaced(card)
        column.addSpaced(label("怎么使用", 18f, true))
        column.addSpaced(label("1  在模型设置中填写自己的 API Key\n2  开启悬浮球，切到 PDF 或学习应用\n3  用笔或手指选区，可在下方补充需求\n4  点确认直接发送，支持继续追问", 15f))
        column.addSpaced(label("当前版本", 18f, true))
        column.addSpaced(label("点击确认后，将选区、需求和相关记忆发给当前模型接口。记忆与历史保存在本机；自动提炼会额外发送必要对话文本，可在用户记忆中关闭。需要自己的 API Key。", 14f))
        column.addSpaced(label("系统会显示屏幕共享提示。锁屏、系统停止共享或应用被结束后，请重新开启会话。部分应用会禁止截屏。", 14f))
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
    }

    private fun beginSession() {
        if (requesting) return
        requesting = true
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQUEST_OVERLAY)
            } catch (_: Exception) {
                requesting = false
                toast("请在系统设置中允许本应用显示悬浮窗")
            }
        } else requestNotificationThenCapture()
    }

    private fun requestNotificationThenCapture() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
        } else requestCapture()
    }

    private fun requestCapture() {
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val request = if (Build.VERSION.SDK_INT >= 34)
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else manager.createScreenCaptureIntent()
            startActivityForResult(request, REQUEST_CAPTURE)
        } catch (error: Exception) {
            requesting = false
            toast("无法请求截图授权：${error.localizedMessage}")
        }
    }

    @Deprecated("Uses framework Activity results to keep this prototype dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) requestNotificationThenCapture()
                else { requesting = false; toast("开启悬浮球需要悬浮窗权限") }
            }
            REQUEST_CAPTURE -> {
                requesting = false
                if (resultCode == RESULT_OK && data != null) {
                    try {
                        startForegroundService(Intent(this, CaptureService::class.java).apply {
                            action = CaptureService.ACTION_START
                            putExtra(CaptureService.EXTRA_RESULT, resultCode)
                            putExtra(CaptureService.EXTRA_CONSENT, data)
                        })
                        moveTaskToBack(true)
                    } catch (error: Exception) { toast("会话启动失败：${error.localizedMessage}") }
                } else toast("未开启屏幕共享，可以稍后重试")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Denying notifications does not deny projection. The foreground service remains visible in system UI.
        if (requestCode == REQUEST_NOTIFICATION) requestCapture()
    }

    private fun showPractice() {
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val overlay = SelectionOverlay(this, practiceImage(),
            onClose = { closePractice() },
            onAsk = { bitmap, question, hidden ->
                val answer = AiConversationView(this, bitmap, question, hiddenInitial = hidden) { closePracticeAnswer() }
                practiceAnswer = answer
                setContentView(answer)
            })
        practice = overlay
        setContentView(overlay)
    }

    private fun closePractice() {
        practiceAnswer?.release()
        practiceAnswer = null
        practice?.release()
        practice = null
        renderHome()
    }

    private fun closePracticeAnswer() {
        practiceAnswer?.release()
        practiceAnswer = null
        practice?.let { setContentView(it) } ?: renderHome()
    }

    @Deprecated("Legacy navigation on Android 8-12")
    override fun onBackPressed() {
        if (practiceAnswer != null) closePracticeAnswer() else if (practice != null) closePractice() else super.onBackPressed()
    }

    private fun practiceImage(): Bitmap {
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 34f }
        fun text(value: String, x: Float, y: Float, size: Float = 34f) {
            paint.textSize = size
            paint.style = Paint.Style.FILL
            canvas.drawText(value, x, y, paint)
        }
        text("选区练习 / 用手指也可以", 64f, 100f, 46f)
        text("试着圈住左边的问题，避开右侧的干扰内容。", 64f, 170f, 30f)
        paint.color = green
        text("01 / 数学", 64f, 300f)
        paint.color = ink
        text("已知直角三角形两条直角边", 64f, 375f)
        text("分别为 3 和 4，求斜边长度。", 64f, 435f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        canvas.drawPath(android.graphics.Path().apply {
            moveTo(130f, 780f); lineTo(130f, 520f); lineTo(470f, 780f); close()
        }, paint)
        text("3", 85f, 650f)
        text("4", 285f, 830f)
        text("?", 325f, 620f)
        paint.color = Color.rgb(240, 242, 237)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(760f, 250f, 1140f, 880f, 20f, 20f, paint)
        paint.color = ink
        text("另一道题", 800f, 320f)
        text("这部分不需要选中", 800f, 395f, 28f)
        text("x + 2 = 7", 800f, 480f)
        text("求 x 的值。", 800f, 550f)
        paint.color = green
        text("02 / 知识点", 64f, 1050f)
        paint.color = ink
        text("为什么直角三角形满足 a² + b² = c²？", 64f, 1130f)
        text("你可以把文字和上面的图一起圈进来。", 64f, 1200f)
        text("自由圈选按真实路径裁切，圈外填白。", 64f, 1390f, 30f)
        text("切换矩形后，也可以从任意方向拖动。", 64f, 1450f, 30f)
        return bitmap
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        practiceAnswer?.release()
        practiceAnswer = null
        practice?.release()
        practice = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_OVERLAY = 10
        private const val REQUEST_NOTIFICATION = 11
        private const val REQUEST_CAPTURE = 12
    }
}
