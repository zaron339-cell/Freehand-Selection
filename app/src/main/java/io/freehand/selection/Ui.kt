package io.freehand.selection

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

internal val ink = Color.rgb(30, 45, 42)
internal val green = Color.rgb(37, 121, 104)
internal val paper = Color.rgb(244, 246, 242)
internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
internal fun rounded(color: Int, radius: Float = 20f) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}
internal fun Context.label(text: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(ink)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    setLineSpacing(dp(4).toFloat(), 1f)
}
internal fun Context.action(text: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    textSize = 14f
    minHeight = dp(48)
    minimumWidth = 0
    setPadding(dp(12), 0, dp(12), 0)
    setTextColor(if (primary) Color.WHITE else ink)
    background = rounded(if (primary) green else Color.rgb(229, 235, 228), dp(14).toFloat())
    setOnClickListener { onClick() }
}
internal fun LinearLayout.addSpaced(view: android.view.View, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
    addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply {
        bottomMargin = context.dp(12)
    })
}
internal fun Context.row() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}
internal fun LinearLayout.addWeighted(view: android.view.View) {
    addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = context.dp(3)
        marginEnd = context.dp(3)
    })
}
