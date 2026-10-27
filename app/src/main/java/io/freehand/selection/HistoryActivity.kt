package io.freehand.selection

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class HistoryActivity : Activity() {
    private lateinit var column: LinearLayout
    private var pendingId: String? = null
    private var pageSize = 30
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingId = savedInstanceState?.getString("pendingId")
    }

    override fun onResume() {
        super.onResume()
        val pending = pendingId
        if (pending != null && Settings.canDrawOverlays(this)) { pendingId = null; resume(pending) }
        render()
    }

    private fun render() {
        val token = ++generation
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(paper)
        }
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        column.addSpaced(label("历史对话", 26f, true))
        column.addSpaced(label("题图和对话保存在本机。关闭回答窗后，可以在这里继续追问。", 14f))
        column.addSpaced(action("返回") { finish() })
        val loading = label("正在读取…", 14f)
        column.addSpaced(loading)
        ConversationStore.async({ ConversationStore(this).list() }) { result ->
            if (isDestroyed || token != generation) return@async
            column.removeView(loading)
            result.fold(onSuccess = { records ->
                if (records.isEmpty()) column.addSpaced(label("还没有对话，圈选内容并提问后会自动保存。", 16f))
                for (record in records.take(pageSize)) {
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = rounded(android.graphics.Color.WHITE, dp(16).toFloat())
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }
                    card.addSpaced(label(record.title, 17f, true))
                    card.addSpaced(label("${DateFormat.getDateTimeInstance().format(Date(record.updated))} · ${record.turns.count { it.role == "assistant" }} 条回答" + if (record.pending) " · 未完成" else "", 12f))
                    val row = row()
                    row.addWeighted(action("继续对话", true) { resume(record.id) })
                    row.addView(action("删除") {
                        AlertDialog.Builder(this).setMessage("删除这条对话及题图？")
                            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                                ConversationStore.async({ ConversationStore(this).delete(record.id) }) {
                                    it.fold(onSuccess = { render() }, onFailure = { error -> toast("删除失败：${error.localizedMessage}") })
                                }
                            }.show()
                    })
                    card.addView(row)
                    column.addSpaced(card)
                }
                if (records.size > pageSize) column.addSpaced(action("显示更多") { pageSize += 30; render() })
            }, onFailure = { column.addSpaced(label("无法读取历史：${it.localizedMessage}", 15f)) })
        }
    }

    private fun resume(id: String) {
        if (!Settings.canDrawOverlays(this)) {
            pendingId = id
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { toast("请在系统设置中允许显示悬浮窗") }
            return
        }
        try {
            startForegroundService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_RESTORE)
                .putExtra("conversationId", id))
            moveTaskToBack(true)
        } catch (e: Exception) { toast("无法打开对话：${e.localizedMessage}") }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingId", pendingId)
        super.onSaveInstanceState(outState)
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
