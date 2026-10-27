package io.freehand.selection

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.nio.ByteBuffer

/** One consent token, one VirtualDisplay. Resizing reuses the same display. Main-thread confined. */
internal class CaptureSession(
    private val context: Context,
    resultCode: Int,
    consent: Intent,
    private val onStopped: () -> Unit,
    private val onResized: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val projection: MediaProjection = checkNotNull(context.getSystemService(MediaProjectionManager::class.java)
        .getMediaProjection(resultCode, consent)) { "系统未提供有效的截图会话" }
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latestImage: Image? = null
    private var latestArrival = 0L
    private var pending: ((Result<Bitmap>) -> Unit)? = null
    private var earliestArrival = 0L
    private var closed = false
    private var width = 0
    private var height = 0
    private val timeout = Runnable { finish(Result.failure(IllegalStateException("截图等待超时，请重新开启会话"))) }
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            close(stopProjection = false)
            onStopped()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (!closed && width > 0 && height > 0 && (width != this@CaptureSession.width || height != this@CaptureSession.height)) {
                resize(width, height)
                onResized()
            }
        }
    }

    init { projection.registerCallback(callback, handler) }

    fun start(width: Int, height: Int) {
        this.width = width
        this.height = height
        val target = createReader(width, height)
        reader = target
        display = projection.createVirtualDisplay("Freehand selection", width, height,
            context.resources.configuration.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, target.surface, null, handler)
    }

    private fun createReader(width: Int, height: Int) = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
        setOnImageAvailableListener({ source ->
            if (closed || source !== reader) return@setOnImageAvailableListener
            try {
                // Holding at most one Image avoids converting every captured frame into a Bitmap.
                latestImage?.close()
                latestImage = null
                latestImage = source.acquireLatestImage()
                latestArrival = SystemClock.uptimeMillis()
                deliverIfReady()
            } catch (error: Exception) {
                finish(Result.failure(error))
            }
        }, handler)
    }

    fun capture(notBefore: Long, callback: (Result<Bitmap>) -> Unit) {
        if (closed) { callback(Result.failure(IllegalStateException("截图会话已结束"))); return }
        if (pending != null) { callback(Result.failure(IllegalStateException("已有截图正在进行"))); return }
        earliestArrival = notBefore
        pending = callback
        handler.postDelayed(timeout, 5000)
        deliverIfReady()
    }

    private fun deliverIfReady() {
        if (pending == null || latestArrival < earliestArrival) return
        val image = latestImage ?: return
        latestImage = null
        val result = try {
            Result.success(imageToBitmap(image))
        } catch (error: Exception) {
            Result.failure(error)
        } finally { image.close() }
        finish(result)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val paddedWidth = plane.rowStride / plane.pixelStride
        check(plane.pixelStride == 4 && paddedWidth >= image.width) { "设备返回了不支持的截图像素格式" }
        // Some devices omit final-row padding from the buffer. Copy row by row into a tight buffer.
        val tight = ByteBuffer.allocateDirect(image.width * image.height * 4)
        val input = plane.buffer.duplicate()
        for (row in 0 until image.height) {
            input.limit(input.capacity())
            input.position(row * plane.rowStride)
            input.limit(row * plane.rowStride + image.width * 4)
            tight.put(input)
        }
        tight.rewind()
        return Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(tight) }
    }

    fun resize(newWidth: Int, newHeight: Int) {
        if (closed || newWidth <= 0 || newHeight <= 0 || (newWidth == width && newHeight == height)) return
        finish(Result.failure(IllegalStateException("屏幕尺寸已改变，请重新圈选")))
        latestImage?.close()
        latestImage = null
        val oldReader = reader
        val next = createReader(newWidth, newHeight)
        reader = next
        width = newWidth
        height = newHeight
        display?.resize(width, height, context.resources.configuration.densityDpi)
        display?.surface = next.surface
        oldReader?.close()
        latestArrival = 0L
    }

    private fun finish(result: Result<Bitmap>) {
        handler.removeCallbacks(timeout)
        val completed = pending
        pending = null
        completed?.invoke(result)
    }

    fun close(stopProjection: Boolean = true) {
        if (closed) return
        closed = true
        finish(Result.failure(IllegalStateException("截图会话已结束")))
        latestImage?.close()
        latestImage = null
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection.unregisterCallback(callback)
        if (stopProjection) projection.stop()
    }
}
