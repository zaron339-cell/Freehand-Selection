package io.freehand.selection

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.TextView
import android.widget.Toast
import io.freehand.selection.ai.AiTurn
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import org.commonmark.node.StrongEmphasis

/** Formatting affects display only; prompts and raw answers remain intact in API history. */
internal class ConversationRenderer(context: Context) {
    private val markdown = Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                builder.appendFactory(StrongEmphasis::class.java) { _, _ -> ForegroundColorSpan(Color.BLACK) }
            }
            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver { view, link ->
                    val uri = Uri.parse(link)
                    if (uri.scheme?.lowercase() in setOf("https", "http")) {
                        runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { Toast.makeText(view.context, "无法打开此链接", Toast.LENGTH_SHORT).show() }
                    } else Toast.makeText(view.context, "仅支持打开网页链接", Toast.LENGTH_SHORT).show()
                }
            }
        }).build()
    private val completed = mutableMapOf<Int, Pair<AiTurn, Spanned>>()

    fun show(target: TextView, history: List<AiTurn>, pendingQuestion: String?, hiddenQuestion: Boolean,
             assistantName: String, partial: String, incomplete: Boolean) {
        val content = SpannableStringBuilder()
        fun author(name: String) {
            val start = content.length
            content.append(name).append("：")
            content.setSpan(StyleSpan(Typeface.BOLD), start, content.length, SPAN_EXCLUSIVE_EXCLUSIVE)
            content.setSpan(ForegroundColorSpan(Color.BLACK), start, content.length, SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for ((index, turn) in history.withIndex()) {
            if (turn.hidden) continue
            author(if (turn.role == "user") "你" else turn.assistantName)
            if (turn.role == "assistant") {
                content.append('\n')
                // Android span instances cannot occupy two separate ranges in one TextView.
                // Cache by turn position, so identical replies still own distinct span objects.
                val rendered = completed[index]?.takeIf { it.first == turn }?.second
                    ?: markdown.toMarkdown(turn.text).also { completed[index] = turn to it }
                content.append(rendered)
            } else content.append(turn.text)
            content.append("\n\n")
        }
        if (pendingQuestion != null) {
            if (!hiddenQuestion) { author("你"); content.append(pendingQuestion).append("\n\n") }
            author(assistantName)
            content.append('\n')
            content.append(markdown.toMarkdown(partial))
            if (incomplete && partial.isNotEmpty()) content.append("\n（本次回答未完成）")
        }
        markdown.setParsedMarkdown(target, content)
    }
}
