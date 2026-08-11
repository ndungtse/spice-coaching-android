package com.medtroniclabs.microcoaching.ui.chat

import android.content.Context
import com.medtroniclabs.microcoaching.util.PrefsNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences-backed store for the chat's manual **on-device vs online**
 * mode preference.
 *
 * The chat defaults to the on-device AI even when the device is connected —
 * CHWs may prefer local inference (data cost, latency, trust). Setting this to
 * `true` opts into the backend RAG pipeline whenever connectivity is available;
 * connectivity itself is still gated separately (see [ChatViewModel.sendMessage]).
 *
 * Follows the plain-`SharedPreferences` idiom used across the SDK (see
 * [com.medtroniclabs.microcoaching.ui.onboarding.OnboardingPrefs]), with an
 * added [preferOnline] `StateFlow` so Compose reacts to changes immediately.
 */
internal class ChatModePrefs(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PrefsNames.CHAT, Context.MODE_PRIVATE)

    private val _preferOnline = MutableStateFlow(prefs.getBoolean(KEY_PREFER_ONLINE, DEFAULT))

    /** Reactive mirror of the stored preference. Default is on-device (`false`). */
    val preferOnline: StateFlow<Boolean> = _preferOnline.asStateFlow()

    fun setPreferOnline(value: Boolean) {
        prefs.edit().putBoolean(KEY_PREFER_ONLINE, value).apply()
        _preferOnline.value = value
    }

    private companion object {
        // `mc_` prefix per the SDK-wide convention to avoid collisions with the host's prefs.
        const val KEY_PREFER_ONLINE = "mc_chat_prefer_online_v1"
        const val DEFAULT = false
    }
}
