package io.freehand.selection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs

internal class CaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windows: WindowManager
    private var session: CaptureSession? = null
    private var ball: FloatingBallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var ballPanel: BallPanel? = null
    private var selection: SelectionOverlay? = null
    private var answer: AiConversationView? = null
    private var answerParams: WindowManager.LayoutParams? = null
    private var capturing = false
    private var destroyed = false
    private var captureGeneration = 0
    private var restoreGeneration = 0

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WindowManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "圈选学习会话", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_RESTORE) {
            closeBallPanel()
            if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
            if (!isServiceActive) {
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(NOTIFICATION, notification())
                isServiceActive = true
            }
            val id = intent.getStringExtra("conversationId") ?: return START_NOT_STICKY
            val token = ++restoreGeneration
            if (answer?.conversationId == id) {
                recoverAnswer(); showBall()
                return START_NOT_STICKY
            }
            captureGeneration++
            capturing = false
            closeSelection()
            ConversationStore.async({ ConversationStore(this).load(id) }) { result ->
                result.fold(onSuccess = { (record, image) ->
                    try { if (!destroyed && token == restoreGeneration) {
                        closeAnswer(); closeSelection(); showAnswer(image, record.initialQuestion, record)
                    } } finally { image.recycle() }
                }, onFailure = { if (!destroyed) toast("无法恢复对话：${it.localizedMessage}") })
            }
            showBall()
            return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        val consent = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_CONSENT) as? Intent)
        if (consent == null || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                else startForeground(NOTIFICATION, notification)
            val created = CaptureSession(this, intent.getIntExtra(EXTRA_RESULT, -1), consent,
                onStopped = { toast("截图会话已结束，请从主页重新开启"); stopSelf() },
                onResized = { cancelSelectionForResize() })
            session = created
            val size = screenSize()
            created.start(size.first, size.second)
            isRunning = true
            isServiceActive = true
            showBall()
        } catch (error: Exception) {
            toast("无法启动截图：${error.localizedMessage}")
            stopSelf()
        }
        // Never restore a consumed projection token after process death.
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val home = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_selection)
            .setContentTitle("圈选助手已开启")
            .setContentText("点悬浮球选择内容 · 手指和笔均可")
            .setContentIntent(home)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "结束会话", stop).build())
            .build()
    }

    private fun showBall() {
        if (destroyed || !isServiceActive) return
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        ball?.let {
            it.visibility = View.VISIBLE
            // Keep the recovery control above even a screen-sized, partially parked panel.
            if (answer != null && it.isAttachedToWindow) {
                try { windows.removeView(it); windows.addView(it, ballParams) }
                catch (_: Exception) { stopSelf() }
            }
            return
        }
        val size = dp(56)
        val bounds = screenSize()
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (bounds.first - size - dp(8)).coerceAtLeast(0)
            y = bounds.second / 3
        }
        val view = FloatingBallView(this)
        view.setOnClickListener {
            if (ballPanel != null) { closeBallPanel(); return@setOnClickListener }
            restoreGeneration++
            if (answer != null) recoverAnswer()
            else if (session != null) startCapture()
            else startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        view.setOnLongClickListener {
            showBallMenu()
            true
        }
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        var dragging = false
        var longPressed = false
        val longPress = Runnable { if (!dragging) { longPressed = true; view.performLongClick() } }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    originX = params.x; originY = params.y
                    dragging = false; longPressed = false
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (abs(dx) > slop || abs(dy) > slop) { dragging = true; handler.removeCallbacks(longPress) }
                    if (dragging) {
                        val screen = screenSize()
                        params.x = (originX + dx.toInt()).coerceIn(0, (screen.first - size).coerceAtLeast(0))
                        params.y = (originY + dy.toInt()).coerceIn(0, (screen.second - size - dp(32)).coerceAtLeast(0))
                        updateBall(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (dragging) {
                        val screen = screenSize()
                        params.x = if (params.x + size / 2 < screen.first / 2) dp(4) else (screen.first - size - dp(4)).coerceAtLeast(0)
                        updateBall(view, params)
                    } else if (!longPressed) view.performClick()
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            true
        }
        try {
            windows.addView(view, params)
            ball = view
            ballParams = params
        } catch (error: Exception) {
            toast("悬浮窗不可用：${error.localizedMessage}")
            stopSelf()
        }
    }

    private fun updateBall(view: View, params: WindowManager.LayoutParams) {
        try { windows.updateViewLayout(view, params) }
        catch (_: Exception) { stopSelf() }
    }

    private fun showBallMenu() {
        val menu = BallMenuView(this, session != null, answer != null,
            onHistory = { showBallHistory() },
            onCapture = { closeBallPanel(); closeAnswer(); startCapture() },
            onRecover = { closeBallPanel(); recoverAnswer() },
            onClose = { closeBallPanel() })
        showBallPanel(menu, dp(240), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun showBallHistory() {
        rememberFrame() // Flush the current draft before the serial queue reads history.
        val history = BallHistoryView(this,
            onResume = { id ->
                closeBallPanel()
                startService(Intent(this, CaptureService::class.java).setAction(ACTION_RESTORE).putExtra("conversationId", id))
            }, onClose = { closeBallPanel() })
        showBallPanel(history, dp(460), minOf(dp(560), (screenSize().second - dp(80)).coerceAtLeast(1)))
    }

    private fun showBallPanel(panel: BallPanel, requestedWidth: Int, requestedHeight: Int) {
        closeBallPanel()
        if (destroyed) { (panel as? BallHistoryView)?.dispose(); return }
        val screen = screenSize()
        val width = minOf(requestedWidth, (screen.first - dp(24)).coerceAtLeast(1))
        val params = WindowManager.LayoutParams(width, requestedHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = ((ballParams?.x ?: screen.first) - width - dp(8)).coerceIn(0, (screen.first - width).coerceAtLeast(0))
            val expectedHeight = if (requestedHeight > 0) requestedHeight else dp(300)
            y = (ballParams?.y ?: dp(24)).coerceIn(0, (screen.second - expectedHeight - dp(32)).coerceAtLeast(0))
        }
        try { windows.addView(panel, params); ballPanel = panel; panel.requestFocus() }
        catch (e: Exception) { (panel as? BallHistoryView)?.dispose(); toast("无法打开快捷面板：${e.localizedMessage}") }
    }

    private fun closeBallPanel() {
        ballPanel?.let {
            (it as? BallHistoryView)?.dispose()
            if (it.isAttachedToWindow) windows.removeView(it)
        }
        ballPanel = null
    }

    private fun startCapture() {
        restoreGeneration++
        closeBallPanel()
        if (capturing || selection != null || answer != null || destroyed) return
        val active = session ?: return
        capturing = true
        val generation = ++captureGeneration
        val hiddenAt = SystemClock.uptimeMillis()
        ball?.visibility = View.INVISIBLE
        // Let the compositor remove our bubble; then select a frame produced after hiding it.
        handler.postDelayed({
            if (destroyed || generation != captureGeneration) return@postDelayed
            active.capture(hiddenAt) { result ->
                capturing = false
                result.fold(onSuccess = { bitmap ->
                    if (destroyed || generation != captureGeneration) bitmap.recycle()
                    else {
                        try {
                            val overlay = SelectionOverlay(this, bitmap,
                                onClose = { closeSelection(); showBall() },
                                onShare = { image, completed ->
                                    ImageSharing.share(this, image) { error ->
                                        completed(error)
                                        // A system overlay otherwise covers the newly opened share sheet.
                                        if (error == null && !destroyed && generation == captureGeneration) {
                                            closeSelection(); showBall()
                                        }
                                    }
                                },
                                onAsk = { image, question -> showAnswer(image, question) })
                            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                                gravity = Gravity.TOP or Gravity.LEFT
                                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
                            }
                            windows.addView(overlay, params)
                            selection = overlay
                            overlay.requestFocus()
                        } catch (error: Exception) {
                            bitmap.recycle()
                            toast("无法显示选区：${error.localizedMessage}")
                            showBall()
                        }
                    }
                }, onFailure = { error ->
                    if (!destroyed && generation == captureGeneration) { toast(error.localizedMessage ?: "截图失败"); showBall() }
                })
            }
        }, 180)
    }

    private fun closeSelection() {
        selection?.let { overlay ->
            if (overlay.isAttachedToWindow) windows.removeView(overlay)
            overlay.release()
        }
        selection = null
    }

    private fun showAnswer(image: android.graphics.Bitmap, question: String, restored: ConversationRecord? = null) {
        val panel = AiConversationView(this, image, question, restored) { closeAnswer(); showBall() }
        val size = screenSize()
        val params = WindowManager.LayoutParams(minOf(dp(520), size.first - dp(24)),
            minOf(dp(680), size.second - dp(96)), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (size.first - width - dp(12)).coerceAtLeast(0)
            y = dp(24)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        restored?.frame?.recovered(size.first, size.second - dp(32))?.let {
            params.x = it.x; params.y = it.y; params.width = it.width; params.height = it.height
        }
        var initialX = 0f
        var initialY = 0f
        var originX = 0
        var originY = 0
        panel.dragHandle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.rawX; initialY = event.rawY
                    originX = params.x; originY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = originX + event.rawX.toInt() - initialX.toInt()
                    params.y = originY + event.rawY.toInt() - initialY.toInt()
                    if (panel.isAttachedToWindow) windows.updateViewLayout(panel, params)
                }
                MotionEvent.ACTION_UP -> { rememberFrame(); view.performClick() }
            }
            true
        }
        fun resize(handle: View, horizontal: Boolean, vertical: Boolean) {
            var startX = 0f; var startY = 0f; var width = 0; var height = 0
            handle.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; width = params.width; height = params.height }
                    MotionEvent.ACTION_MOVE -> {
                        val screen = screenSize()
                        if (horizontal) params.width = (width + (event.rawX - startX).toInt()).coerceIn(minOf(dp(300), screen.first), screen.first)
                        if (vertical) params.height = (height + (event.rawY - startY).toInt()).coerceIn(minOf(dp(360), screen.second), screen.second)
                        if (panel.isAttachedToWindow) windows.updateViewLayout(panel, params)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { rememberFrame(); view.performClick() }
                }
                true
            }
        }
        resize(panel.resizeGrip, true, true)
        resize(panel.rightGrip, true, false)
        resize(panel.bottomGrip, false, true)
        try {
            windows.addView(panel, params)
            answer = panel
            answerParams = params
            closeSelection()
            showBall()
            rememberFrame()
            panel.requestFocus()
        } catch (error: Exception) {
            panel.release()
            toast("无法显示 AI 面板：${error.localizedMessage}")
        }
    }

    private fun rememberFrame() {
        answerParams?.let { answer?.updateFrame(WindowFrame(it.x, it.y, it.width, it.height)) }
    }

    private fun recoverAnswer() {
        val params = answerParams ?: return
        val screen = screenSize()
        val frame = WindowFrame(params.x, params.y, params.width, params.height).recovered(screen.first, (screen.second - dp(32)).coerceAtLeast(1))
        params.x = frame.x; params.y = frame.y; params.width = frame.width; params.height = frame.height
        answer?.let { if (it.isAttachedToWindow) windows.updateViewLayout(it, params) }
        rememberFrame()
    }

    private fun closeAnswer() {
        rememberFrame()
        answer?.let { panel ->
            if (panel.isAttachedToWindow) windows.removeView(panel)
            panel.release()
        }
        answer = null
        answerParams = null
    }

    private fun cancelSelectionForResize() {
        captureGeneration++
        capturing = false
        if (selection != null) { closeSelection(); toast("屏幕尺寸已改变，请重新圈选") }
        showBall()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        closeBallPanel()
        cancelSelectionForResize()
        if (Build.VERSION.SDK_INT < 34) {
            val size = screenSize()
            session?.resize(size.first, size.second)
        }
        ballParams?.let { params ->
            val screen = screenSize()
            params.x = params.x.coerceIn(0, (screen.first - dp(56)).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (screen.second - dp(88)).coerceAtLeast(0))
            ball?.let { updateBall(it, params) }
        }
        answerParams?.let { params ->
            val screen = screenSize()
            params.width = minOf(params.width, screen.first)
            params.height = minOf(params.height, (screen.second - dp(32)).coerceAtLeast(1))
            rememberFrame()
            answer?.let { if (it.isAttachedToWindow) windows.updateViewLayout(it, params) }
        }
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windows.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = DisplayMetrics()
        getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        isRunning = false
        isServiceActive = false
        captureGeneration++
        handler.removeCallbacksAndMessages(null)
        closeBallPanel()
        closeAnswer()
        closeSelection()
        ball?.let { if (it.isAttachedToWindow) windows.removeView(it) }
        ball = null
        session?.close()
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "io.freehand.selection.START"
        const val ACTION_RESTORE = "io.freehand.selection.RESTORE"
        const val ACTION_STOP = "io.freehand.selection.STOP"
        const val EXTRA_CONSENT = "consent"
        const val EXTRA_RESULT = "result"
        private const val CHANNEL = "learning-session"
        private const val NOTIFICATION = 1
        @Volatile var isServiceActive = false
            private set
        @Volatile var isRunning = false
            private set
    }
}
