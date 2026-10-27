package io.freehand.selection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import io.freehand.selection.geometry.ImageMapping
import io.freehand.selection.geometry.Point
import io.freehand.selection.geometry.SelectionMode
import io.freehand.selection.geometry.SelectionRegion
import kotlin.math.hypot

/** A normal finger, passive stylus, active stylus and mouse all use the same geometry. */
internal class SelectionCanvas(context: Context, private val screenshot: Bitmap) : View(context) {
    var onSelectionChanged: (Boolean) -> Unit = {}
    var mode = SelectionMode.FREEHAND
        set(value) { field = value; clearSelection() }
    var region: SelectionRegion? = null
        private set
    private val samples = mutableListOf<Point>()
    private var pointerId = -1
    private var pointerTool = MotionEvent.TOOL_TYPE_UNKNOWN
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val drawingPath = Path()
    private val veil = Path()
    private val mapping get() = ImageMapping(screenshot.width, screenshot.height, width, height)

    init {
        setBackgroundColor(Color.rgb(23, 31, 29))
        contentDescription = "选区画布，用手指或笔圈出内容，也可以切换矩形框选"
        isFocusable = true
    }

    fun clearSelection() {
        samples.clear()
        pointerId = -1
        region = null
        onSelectionChanged(false)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = mapping
        if (m.scale <= 0f) return
        canvas.save()
        canvas.translate(m.left, m.top)
        canvas.scale(m.scale, m.scale)
        brush.style = Paint.Style.FILL
        brush.color = Color.WHITE
        canvas.drawBitmap(screenshot, 0f, 0f, brush)
        val hasSelection = updateDrawingPath()
        veil.reset()
        veil.fillType = Path.FillType.EVEN_ODD
        veil.addRect(0f, 0f, screenshot.width.toFloat(), screenshot.height.toFloat(), Path.Direction.CW)
        if (hasSelection) veil.addPath(drawingPath)
        brush.color = Color.argb(135, 0, 0, 0)
        canvas.drawPath(veil, brush)
        if (hasSelection) {
            brush.style = Paint.Style.STROKE
            brush.strokeWidth = context.dp(3) / m.scale
            brush.strokeJoin = Paint.Join.ROUND
            brush.strokeCap = Paint.Cap.ROUND
            brush.color = Color.rgb(136, 245, 201)
            canvas.drawPath(drawingPath, brush)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(event, event.actionIndex)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A pen can take over an accidental finger gesture; extra fingers never add vertices.
                val index = event.actionIndex
                if (event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS &&
                    pointerTool != MotionEvent.TOOL_TYPE_STYLUS) start(event, index)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) {
                    for (history in 0 until event.historySize) {
                        append(event.getHistoricalX(index, history), event.getHistoricalY(index, history))
                    }
                    append(event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    if (Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0) {
                        clearSelection()
                    } else {
                        append(event.getX(event.actionIndex), event.getY(event.actionIndex), force = true)
                        finishSelection()
                        performClick()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> clearSelection()
        }
        return true
    }

    private fun start(event: MotionEvent, index: Int) {
        val point = mapping.toImage(event.getX(index), event.getY(index), clamp = false) ?: return
        clearSelection()
        pointerId = event.getPointerId(index)
        pointerTool = event.getToolType(index)
        samples.add(point)
    }

    private fun append(x: Float, y: Float, force: Boolean = false) {
        if (pointerId < 0) return
        val point = mapping.toImage(x, y) ?: return
        val last = samples.lastOrNull()
        if (force || last == null || hypot(point.x - last.x, point.y - last.y) * mapping.scale >= 1.5f) {
            if (mode == SelectionMode.RECTANGLE && samples.size > 1) samples[1] = point else samples.add(point)
            invalidate()
        }
    }

    private fun finishSelection() {
        pointerId = -1
        val minimumSize = context.dp(8) / mapping.scale
        region = if (mode == SelectionMode.RECTANGLE && samples.size >= 2) {
            SelectionRegion.rectangle(samples.first(), samples.last(), minimumSize)
        } else if (mode == SelectionMode.FREEHAND) SelectionRegion.freehand(samples, minimumSize) else null
        samples.clear()
        onSelectionChanged(region != null)
        invalidate()
    }

    private fun updateDrawingPath(): Boolean {
        drawingPath.reset()
        drawingPath.fillType = Path.FillType.EVEN_ODD
        val points = region?.points ?: samples
        if (points.size < 2) return false
        if (region == null && mode == SelectionMode.RECTANGLE) {
            val a = points.first()
            val b = points.last()
            drawingPath.addRect(kotlin.math.min(a.x, b.x), kotlin.math.min(a.y, b.y),
                kotlin.math.max(a.x, b.x), kotlin.math.max(a.y, b.y), Path.Direction.CW)
        } else {
            drawingPath.moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) drawingPath.lineTo(points[i].x, points[i].y)
            drawingPath.close()
        }
        return true
    }

    fun crop(): Bitmap? {
        val selected = region ?: return null
        val bounds = selected.pixelBounds(screenshot.width, screenshot.height)
        if (bounds.width <= 0 || bounds.height <= 0) return null
        return Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888).also { result ->
            val canvas = Canvas(result)
            canvas.drawColor(Color.WHITE)
            canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
            canvas.clipPath(pathFor(selected))
            canvas.drawBitmap(screenshot, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    override fun performClick(): Boolean = super.performClick()

    private fun pathFor(selected: SelectionRegion) = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        moveTo(selected.points.first().x, selected.points.first().y)
        selected.points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
}
