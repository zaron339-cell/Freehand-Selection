package io.freehand.selection

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedDispatcher

class AiPromptSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(AiPromptSettingsView(this) { finish() })
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finish() }
    }
}
