package io.freehand.selection

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

internal class FloatingBallView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val symbol = Path().apply {
        moveTo(16f, 31f)
        cubicTo(9f, 28f, 11f, 16f, 20f, 13f)
        cubicTo(30f, 8f, 39f, 16f, 35f, 25f)
        cubicTo(32f, 33f, 21f, 35f, 16f, 29f)
        cubicTo(12f, 24f, 19f, 21f, 23f, 27f)
        lineTo(29f, 35f)
    }

    init {
        contentDescription = "圈选助手，点按圈选或找回回答，拖动移动位置，长按打开历史对话和快捷菜单"
        isClickable = true
        isFocusable = true
        elevation = context.dp(6).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(width / 48f, height / 48f)
        paint.style = Paint.Style.FILL
        paint.color = green
        canvas.drawCircle(24f, 24f, 23f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.WHITE
        canvas.drawPath(symbol, paint)
        canvas.restore()
    }

    override fun performClick(): Boolean = super.performClick()
}
