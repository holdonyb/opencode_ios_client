package ai.opencode.mobile.android.storage

import android.content.Context

class LocalSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("opencode_settings", Context.MODE_PRIVATE)

    fun getString(key: String, default: String = ""): String = prefs.getString(key, default).orEmpty()

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

