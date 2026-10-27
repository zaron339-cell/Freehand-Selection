package io.freehand.selection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import io.freehand.selection.ai.AiCall
import io.freehand.selection.ai.AiClient
import io.freehand.selection.ai.AiProfile
import io.freehand.selection.ai.AiProfileStore
import io.freehand.selection.ai.AiProtocol
import java.util.UUID

internal class AiSettingsView(context: Context, private val onClose: () -> Unit) : ScrollView(context) {
    private val store = AiProfileStore(context)
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(20), context.dp(24), context.dp(20), context.dp(24))
    }
    private var current = store.active()
    private var available = store.profiles()
    private var loading = false
    private val saved = Spinner(context)
    private val name = field("例如：我的免费视觉模型")
    private val protocol = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, AiProtocol.entries.map { it.label })
    }
    private val url = field("https://open.bigmodel.cn/api/paas/v4", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
    private val model = field("从服务商复制模型 ID，例如 glm-4.6v-flash")
    private val key = field("粘贴自己的 API Key；本地免密服务可留空").apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod = PasswordTransformationMethod.getInstance()
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    private val timeout = field("120", InputType.TYPE_CLASS_NUMBER)
    private val keyHeader = field("留空按协议默认；例如 api-key")
    private val stream = CheckBox(context).apply { text = "流式显示回答（接口不支持时可关闭）" }
    private val exact = CheckBox(context).apply { text = "地址是完整请求端点，不自动补路径" }
    private val http = CheckBox(context).apply { text = "允许 HTTP：图片与密钥将明文传输" }
    private val extras = field("可选，例如 {\"temperature\":0.5}").apply {
        isSingleLine = false
        minLines = 2
        maxLines = 5
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    private val status = context.label("选择预设或填写自己的接口。保存后才会用于选区问答。", 14f)
    private val test = context.action("测试图片连接") { testConnection() }
    private var call: AiCall? = null
    private var disposed = false
    private var testGeneration = 0

    init {
        setBackgroundColor(paper)
        isFillViewport = true
        addView(column)
        column.addSpaced(context.label("AI 模型设置", 26f, true))
        column.addSpaced(context.label("智谱 GLM-4.6V-Flash 为官方免费视觉模型，需要自己的 Key，仍受平台限流。DeepSeek 为按量计费；Gemini 免费层以账户、地区和配额为准。", 14f))
        val presets = context.row()
        presets.addWeighted(context.action("智谱免费") { loadPreset("zhipu-free") })
        presets.addWeighted(context.action("DeepSeek") { loadPreset("deepseek") })
        presets.addWeighted(context.action("Gemini") { loadPreset("gemini") })
        column.addSpaced(presets)
        val links = context.label("申请 Key：智谱 bigmodel.cn · DeepSeek platform.deepseek.com · Gemini aistudio.google.com", 13f)
        if (context is android.app.Activity) android.text.util.Linkify.addLinks(links, android.text.util.Linkify.WEB_URLS)
        else links.setTextIsSelectable(true)
        column.addSpaced(links)
        addField("已保存的配置（切换前请保存修改）", saved)
        val manage = context.row()
        manage.addWeighted(context.action("新建配置") { load(AiProfile(id = UUID.randomUUID().toString())) })
        manage.addWeighted(context.action("删除此配置") {
            runCatching { store.delete(current.id) }.onSuccess { refresh(store.active().id); status.text = "配置已删除" }
                .onFailure { status.text = it.localizedMessage }
        })
        column.addSpaced(manage)
        addField("配置名称", name)
        addField("接口格式", protocol)
        addField("API 地址 / Base URL", url)
        column.addSpaced(exact)
        addField("模型 ID（必须支持图片输入）", model)
        addField("API Key（使用设备密钥加密后保存在本机）", key)
        column.addSpaced(CheckBox(context).apply {
            text = "显示密钥"
            setOnCheckedChangeListener { _, checked -> key.transformationMethod = if (checked) null else PasswordTransformationMethod.getInstance() }
        })
        column.addSpaced(stream)
        addField("密钥请求头名称（高级，可选）", keyHeader)
        addField("请求总超时 / 秒（15–300）", timeout)
        column.addSpaced(http)
        addField("附加请求参数 / JSON（可选）", extras)
        column.addSpaced(status)
        column.addSpaced(test)
        val actions = context.row()
        actions.addWeighted(context.action("返回") { close() })
        actions.addWeighted(context.action("保存并使用", true) {
            runCatching { readFields().also { store.save(it) } }.onSuccess {
                context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
                close()
            }.onFailure { status.text = it.localizedMessage ?: "保存失败" }
        })
        column.addSpaced(actions)
        column.addSpaced(context.label("只会把确认后的选区与本次对话发给当前配置的服务地址。不会自动改用其他服务商。免费模型也需要由你在服务商处申请 Key。", 13f))
        saved.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!loading && position in available.indices && available[position].id != current.id) load(available[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        refresh(current.id)
        setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    private fun field(hintText: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) = EditText(context).apply {
        hint = hintText
        textSize = 15f
        setTextColor(ink)
        inputType = type
        isSingleLine = true
        setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
    }

    private fun addField(title: String, view: View) {
        column.addSpaced(context.label(title, 14f, true))
        column.addSpaced(view)
    }

    private fun refresh(id: String) {
        loading = true
        available = store.profiles()
        saved.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, available.map { it.name })
        val index = available.indexOfFirst { it.id == id }.coerceAtLeast(0)
        saved.setSelection(index)
        load(available[index])
        saved.post { loading = false }
    }

    private fun loadPreset(id: String) {
        val profile = store.profiles().firstOrNull { it.id == id } ?: AiProfile.presets().first { it.id == id }
        load(profile)
    }

    private fun load(profile: AiProfile) {
        testGeneration++
        call?.cancel()
        call = null
        current = profile
        loading = true
        if (available.none { it.id == profile.id }) {
            available = available + profile
            saved.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, available.map { it.name })
        }
        saved.setSelection(available.indexOfFirst { it.id == profile.id })
        saved.post { loading = false }
        name.setText(profile.name)
        protocol.setSelection(profile.protocol.ordinal)
        url.setText(profile.baseUrl)
        model.setText(profile.model)
        key.setText(profile.apiKey)
        timeout.setText(profile.timeoutSeconds.toString())
        keyHeader.setText(profile.keyHeader)
        stream.isChecked = profile.stream
        exact.isChecked = profile.exactEndpoint
        http.isChecked = profile.allowHttp
        extras.setText(profile.extraJson)
        status.text = if (profile.keyUnavailable) "设备密钥不可用，请重新填写这份配置的 API Key" else "正在编辑：${profile.name}"
        test.isEnabled = true
    }

    private fun readFields() = AiProfile(id = current.id, name = name.text.toString().trim(),
        protocol = AiProtocol.entries[protocol.selectedItemPosition], baseUrl = url.text.toString().trim(),
        model = model.text.toString().trim(), apiKey = key.text.toString().trim(), stream = stream.isChecked,
        keyHeader = keyHeader.text.toString().trim(),
        timeoutSeconds = timeout.text.toString().toIntOrNull() ?: 0, exactEndpoint = exact.isChecked,
        allowHttp = http.isChecked, extraJson = extras.text.toString().trim()).also { it.validate() }

    private fun testConnection() {
        val profile = runCatching { readFields() }.getOrElse { status.text = it.localizedMessage; return }
        val token = ++testGeneration
        test.isEnabled = false
        status.text = "正在发送纯色测试图片，不包含屏幕内容…"
        val image = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            call = AiClient.ask(profile, emptyList(), "这张测试图片主要是什么颜色？只用一句话回答。", image,
                onStage = { if (!disposed && token == testGeneration) status.text = it }, onText = {},
                onComplete = { answer, _ -> if (!disposed && token == testGeneration) { test.isEnabled = true; status.text = "接口已返回：${answer.take(200)}\n如需使用，请点保存并使用。" } },
                onError = { if (!disposed && token == testGeneration) { test.isEnabled = true; status.text = it } })
        } catch (error: Exception) {
            test.isEnabled = true
            status.text = error.localizedMessage ?: "无法开始连接测试"
        } finally { image.recycle() }
    }

    private fun close() { dispose(); onClose() }
    fun dispose() { disposed = true; call?.cancel(); call = null }
    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }
}
