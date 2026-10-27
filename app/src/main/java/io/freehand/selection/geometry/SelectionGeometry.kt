package io.freehand.selection.geometry

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class Point(val x: Float, val y: Float)
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}
data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}
enum class SelectionMode { FREEHAND, RECTANGLE }

/** Coordinates are always in the captured image, never in device dp. */
data class SelectionRegion(val mode: SelectionMode, val points: List<Point>) {
    val bounds: Bounds
        get() = Bounds(points.minOf { it.x }, points.minOf { it.y },
            points.maxOf { it.x }, points.maxOf { it.y })

    fun pixelBounds(imageWidth: Int, imageHeight: Int): PixelBounds {
        val b = bounds
        return PixelBounds(floor(b.left).toInt().coerceIn(0, imageWidth),
            floor(b.top).toInt().coerceIn(0, imageHeight),
            ceil(b.right).toInt().coerceIn(0, imageWidth),
            ceil(b.bottom).toInt().coerceIn(0, imageHeight))
    }

    // Even-odd semantics match Android Path.FillType.EVEN_ODD, including concave paths.
    fun contains(p: Point): Boolean {
        var inside = false
        var j = points.lastIndex
        for (i in points.indices) {
            val a = points[i]
            val b = points[j]
            if ((a.y > p.y) != (b.y > p.y) &&
                p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    companion object {
        fun rectangle(a: Point, b: Point, minimumSize: Float = 1f): SelectionRegion? {
            if (!a.finite() || !b.finite()) return null
            val left = min(a.x, b.x)
            val top = min(a.y, b.y)
            val right = max(a.x, b.x)
            val bottom = max(a.y, b.y)
            if (right - left < minimumSize || bottom - top < minimumSize) return null
            return SelectionRegion(SelectionMode.RECTANGLE,
                listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom)))
        }

        fun freehand(points: List<Point>, minimumSize: Float = 1f): SelectionRegion? {
            if (points.size < 3 || points.any { !it.finite() }) return null
            val region = SelectionRegion(SelectionMode.FREEHAND, points.toList())
            val bounds = region.bounds
            if (bounds.width < minimumSize || bounds.height < minimumSize) return null
            // Reject lines and accidental taps. Signed area is intentionally not used:
            // it incorrectly cancels the two valid lobes of a figure-eight gesture.
            val origin = points.first()
            val nonCollinear = points.zipWithNext().any { (a, b) ->
                abs((a.x - origin.x) * (b.y - origin.y) -
                    (a.y - origin.y) * (b.x - origin.x)) > minimumSize * minimumSize / 2f
            }
            return region.takeIf { nonCollinear }
        }
    }
}

private fun Point.finite() = x.isFinite() && y.isFinite()

/** FIT_CENTER mapping also handles OEMs that return a letterboxed app capture. */
class ImageMapping(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int) {
    val scale = min(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
    val left = (viewWidth - imageWidth * scale) / 2f
    val top = (viewHeight - imageHeight * scale) / 2f
    private val width = imageWidth.toFloat()
    private val height = imageHeight.toFloat()

    fun toImage(x: Float, y: Float, clamp: Boolean = true): Point? {
        if (!scale.isFinite() || scale <= 0f || !x.isFinite() || !y.isFinite()) return null
        val px = (x - left) / scale
        val py = (y - top) / scale
        if (!clamp && (px < 0 || py < 0 || px > width || py > height)) return null
        return Point(px.coerceIn(0f, width), py.coerceIn(0f, height))
    }
}
