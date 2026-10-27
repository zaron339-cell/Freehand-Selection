package io.freehand.selection

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.window.OnBackInvokedDispatcher

class AiSettingsActivity : Activity() {
    private var settings: AiSettingsView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AiSettingsView(this) { finish() }
        setContentView(settings)
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finish() }
    }
    override fun onDestroy() { settings?.dispose(); super.onDestroy() }
}
