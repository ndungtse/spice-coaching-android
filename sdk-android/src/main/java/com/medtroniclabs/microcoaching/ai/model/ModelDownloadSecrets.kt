package com.medtroniclabs.microcoaching.ai.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal object ModelDownloadSecrets {
    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private const val TAG = "ModelDownloadSecrets"
    private const val PREFS_NAME = "microcoaching_model_secrets"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_HF_TOKEN = "hf_token"

    fun persist(context: Context, authToken: String, hfToken: String) {
        prefs(context).edit()
            .putString(KEY_AUTH_TOKEN, authToken)
            .putString(KEY_HF_TOKEN, hfToken)
            .apply()
    }

    fun authToken(context: Context): String = prefs(context).getString(KEY_AUTH_TOKEN, "").orEmpty()

    fun hfToken(context: Context): String = prefs(context).getString(KEY_HF_TOKEN, "").orEmpty()

    private fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            val built = runCatching {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrElse { e ->
                Log.w(TAG, "Encrypted prefs unavailable for model secrets: ${e.message}")
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            cachedPrefs = built
            return built
        }
    }
}
