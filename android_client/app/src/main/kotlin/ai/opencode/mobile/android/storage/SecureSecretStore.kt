package ai.opencode.mobile.android.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureSecretStore(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "opencode_secure_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        // Fail-safe fallback in case encrypted prefs are unavailable on vendor ROMs.
        context.getSharedPreferences("opencode_secure_secrets_fallback", Context.MODE_PRIVATE)
    }

    fun get(key: String): String = prefs.getString(key, "").orEmpty()

    fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

