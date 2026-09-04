package com.medtroniclabs.microcoaching.util

/**
 * Single registry of every `SharedPreferences` file name used by the SDK. The prefixes are
 * inconsistent (`micro_coaching_*`, `microcoaching_*`, `mc_coaching_*`) because the names
 * are load-bearing for existing installs; keeping them in one place is what surfaces the
 * collision below.
 *
 * ⚠️ [ONBOARDING] and [REMINDER] currently resolve to the **same physical file**. They are kept
 * identical here to preserve existing installs' stored state — splitting them requires a data
 * migration (copy the reminder keys into a new file on first launch) and is therefore out of
 * scope for a behaviour-preserving refactor. Do NOT change one without migrating.
 */
internal object PrefsNames {
    const val CHW = "micro_coaching_chw_prefs"
    const val SYNC = "micro_coaching_sync"
    const val CHAT_SUGGESTIONS = "microcoaching_chat_suggestions"

    /** Chat-scoped user preferences (e.g. the manual on-device/online mode toggle). */
    const val CHAT = "microcoaching_chat"

    /** Shared intentionally by ModelManager and ModelDownloadWorker to coordinate download state. */
    const val MODEL = "microcoaching_model_prefs"
    const val STT = "microcoaching_stt_prefs"
    const val EMBED = "microcoaching_embed_prefs"

    // ⚠️ Collision — see class doc. Same value on purpose until a migration lands.
    const val ONBOARDING = "mc_coaching_prefs"
    const val REMINDER = "mc_coaching_prefs"
}
