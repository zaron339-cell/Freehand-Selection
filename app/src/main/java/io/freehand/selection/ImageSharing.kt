package io.freehand.selection

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

internal object ImageSharing {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun share(context: Context, bitmap: Bitmap, completed: (String?) -> Unit) {
        // Own this copy so closing the preview while writing cannot recycle the writer's bitmap.
        val snapshot = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        worker.execute {
            val result = runCatching {
                val directory = File(context.cacheDir, "selections").apply { mkdirs() }
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }
                    ?.forEach { it.delete() }
                val file = File(directory, "selection-${UUID.randomUUID()}.png")
                file.outputStream().use { output -> check(snapshot.compress(Bitmap.CompressFormat.PNG, 100, output)) }
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }
            snapshot.recycle()
            main.post {
                result.fold(onSuccess = { uri ->
                    try {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri("选区图片", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "分享选区图片").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                        completed(null)
                    } catch (error: Exception) {
                        completed("无法打开分享面板：${error.localizedMessage}")
                    }
                }, onFailure = { completed("图片保存失败：${it.localizedMessage}") })
            }
        }
    }
}
