package io.freehand.selection

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import io.freehand.selection.geometry.Point
import io.freehand.selection.geometry.SelectionMode

/** Real Android raster/input checks; no mocked Canvas or third-party test runner. */
class SelectionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        var failure: Throwable? = null
        var count = 0
        runOnMainSync {
            try {
                val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                val view = SelectionCanvas(targetContext, bitmap)
                view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 400, 200)
                fun expect(condition: Boolean, message: String) { check(condition) { message }; count++ }
                fun send(action: Int, points: List<Point>, tools: List<Int> = List(points.size) { MotionEvent.TOOL_TYPE_FINGER }, flags: Int = 0) {
                    val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = tools[index] } }
                    val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
                        x = points[index].x + 100f; y = points[index].y; pressure = 1f; size = .1f
                    } }
                    val now = SystemClock.uptimeMillis()
                    val event = MotionEvent.obtain(now, now, action, points.size, properties, coordinates,
                        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
                    view.onTouchEvent(event)
                    event.recycle()
                }
                val path = listOf(Point(20f, 20f), Point(160f, 20f), Point(160f, 60f),
                    Point(60f, 60f), Point(60f, 160f), Point(20f, 160f))
                send(MotionEvent.ACTION_DOWN, listOf(path.first()))
                path.drop(1).forEach { send(MotionEvent.ACTION_MOVE, listOf(it)) }
                send(MotionEvent.ACTION_UP, listOf(path.last()))
                expect(view.region != null, "Finger lasso was not accepted")
                val crop = checkNotNull(view.crop())
                expect(crop.width == 140 && crop.height == 140, "Image-space crop dimensions are incorrect")
                expect(crop.getPixel(10, 100) == Color.RED, "Selected content was lost")
                expect(crop.getPixel(100, 100) == Color.WHITE, "Unselected neighbouring content leaked into the crop")
                expect(crop.getPixel(100, 10) == Color.RED, "Concave upper arm was lost")
                crop.recycle()

                view.mode = SelectionMode.RECTANGLE
                send(MotionEvent.ACTION_DOWN, listOf(Point(180f, 170f)))
                send(MotionEvent.ACTION_UP, listOf(Point(20f, 30f)))
                val rectangle = checkNotNull(view.crop())
                expect(rectangle.width == 160 && rectangle.height == 140, "Reverse rectangle drag failed")
                expect(rectangle.getPixel(100, 100) == Color.RED, "Rectangle unexpectedly masked content")
                rectangle.recycle()

                send(MotionEvent.ACTION_DOWN, listOf(Point(20f, 20f)))
                send(MotionEvent.ACTION_MOVE, listOf(Point(160f, 160f)))
                send(MotionEvent.ACTION_CANCEL, listOf(Point(160f, 160f)))
                expect(view.region == null && view.crop() == null, "Cancelled gesture left a usable selection")

                // An extra finger cannot move the primary pointer's rectangle endpoint.
                send(MotionEvent.ACTION_DOWN, listOf(Point(20f, 20f)))
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    listOf(Point(20f, 20f), Point(190f, 190f)))
                send(MotionEvent.ACTION_MOVE, listOf(Point(80f, 80f), Point(180f, 180f)))
                send(MotionEvent.ACTION_POINTER_UP, listOf(Point(80f, 80f), Point(180f, 180f)))
                expect(view.region?.bounds?.width == 60f, "A second finger corrupted the active gesture")

                // A real stylus can replace a finger/palm contact; no vendor SDK is involved.
                send(MotionEvent.ACTION_DOWN, listOf(Point(20f, 20f)))
                val tools = listOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_STYLUS)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    listOf(Point(20f, 20f), Point(100f, 100f)), tools)
                send(MotionEvent.ACTION_MOVE, listOf(Point(30f, 30f), Point(180f, 180f)), tools)
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    listOf(Point(30f, 30f), Point(180f, 180f)), tools)
                expect(view.region?.bounds?.left == 100f && view.region?.bounds?.width == 80f, "Stylus takeover failed")

                send(MotionEvent.ACTION_DOWN, listOf(Point(20f, 20f)))
                send(MotionEvent.ACTION_UP, listOf(Point(100f, 100f)), flags = MotionEvent.FLAG_CANCELED)
                expect(view.region == null, "Palm-rejected gesture was accepted")
                bitmap.recycle()
            } catch (error: Throwable) { failure = error }
        }
        if (failure == null) {
            try { count += AiIntegrationChecks(this).run() } catch (error: Throwable) { failure = error }
        }
        if (failure == null) {
            try { count += HistoryChecks(this).run(); count += AiPresentationChecks(this).run() } catch (error: Throwable) { failure = error }
        }
        val result = Bundle().apply {
            putString("stream", if (failure == null) "$count Android input/raster and AI checks passed.\n"
                else "FAILED: ${failure!!.stackTraceToString()}\n")
            putInt("checks", count)
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
