package com.medtroniclabs.microcoaching.ai.model

import android.content.Context
import com.medtroniclabs.microcoaching.util.PrefsNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the user wants the on-device model used for offline answers. */
enum class LocalModelChoice {

    /**
     * Never asked, or asked and deferred. The offer may still be shown.
     *
     * Distinct from [DISABLED] because the two license opposite behaviour: one permits
     * offering the model, the other forbids it. Collapsed into a single boolean "off", a
     * decline is indistinguishable from a fresh install and the offer returns forever.
     */
    UNDECIDED,

    /** Opted in. The model is downloaded when possible and used once loadable. */
    ENABLED,

    /** Opted out. Never downloaded, never loaded, and never offered again unasked. */
    DISABLED,
}

/**
 * Stores whether the on-device model may be used, and how often its offer has been deferred.
 *
 * Shares the [PrefsNames.MODEL] file with [ModelManager]: consent and the file's lifecycle are
 * read together on nearly every path — an opt-out has to reach the download, and a download
 * must not start without consent — and one file keeps them from disagreeing across a restart.
 *
 * Follows the plain-`SharedPreferences` idiom used elsewhere in the SDK (see
 * [com.medtroniclabs.microcoaching.ui.chat.ChatModePrefs]), with a [choice] `StateFlow` so
 * Compose reacts immediately.
 */
class LocalModelPrefs(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PrefsNames.MODEL, Context.MODE_PRIVATE)

    private val _choice = MutableStateFlow(readChoice())

    /** Reactive mirror of the stored choice. Defaults to [LocalModelChoice.UNDECIDED]. */
    val choice: StateFlow<LocalModelChoice> = _choice.asStateFlow()

    private fun readChoice(): LocalModelChoice =
        when (prefs.getString(KEY_CHOICE, null)) {
            VALUE_ENABLED -> LocalModelChoice.ENABLED
            VALUE_DISABLED -> LocalModelChoice.DISABLED
            // Covers a fresh install and any value written by a future version, both of which
            // are safest treated as "not yet decided" rather than as consent.
            else -> LocalModelChoice.UNDECIDED
        }

    fun setChoice(value: LocalModelChoice) {
        val stored = when (value) {
            LocalModelChoice.ENABLED -> VALUE_ENABLED
            LocalModelChoice.DISABLED -> VALUE_DISABLED
            LocalModelChoice.UNDECIDED -> null
        }
        prefs.edit().apply {
            if (stored == null) remove(KEY_CHOICE) else putString(KEY_CHOICE, stored)
        }.apply()
        _choice.value = value
    }

    /** How many times the user has deferred the offer with "Not now". */
    val offerDismissCount: Int get() = prefs.getInt(KEY_OFFER_DISMISS_COUNT, 0)

    /**
     * Records a deferral. Leaves [choice] at [LocalModelChoice.UNDECIDED] — deferring is not
     * declining, so the offer stays permissible; [shouldOfferModel] is what decides whether
     * enough deferrals have accumulated to stop asking.
     */
    fun recordOfferDismissed() {
        prefs.edit()
            .putInt(KEY_OFFER_DISMISS_COUNT, offerDismissCount + 1)
            .apply()
    }

    /**
     * Whether the model offer may be shown right now.
     *
     * @param deviceEligible false on hardware that cannot host the model.
     * @param networkAvailable gates the offer on connectivity, because accepting it starts a
     *   download of hundreds of megabytes. Offered offline, the one action it invites is the
     *   one action that cannot happen.
     */
    fun shouldOfferModel(deviceEligible: Boolean, networkAvailable: Boolean): Boolean =
        shouldOfferLocalModel(
            deviceEligible = deviceEligible,
            networkAvailable = networkAvailable,
            choice = _choice.value,
            dismissCount = offerDismissCount,
        )

    internal companion object {
        // `mc_` prefix per the SDK-wide convention to avoid collisions with the host's prefs.
        const val KEY_CHOICE = "mc_local_model_choice_v1"
        const val KEY_OFFER_DISMISS_COUNT = "mc_local_model_offer_dismiss_count_v1"

        const val VALUE_ENABLED = "enabled"
        const val VALUE_DISABLED = "disabled"

        /**
         * Deferrals allowed before the offer stops appearing. "Not now" twice is a soft no,
         * and a card that returns indefinitely reads as one that cannot be dismissed. The
         * Answering sheet still offers the model to anyone who goes looking.
         */
        const val MAX_OFFER_DISMISSALS = 2
    }
}

/**
 * Whether the model offer may be shown, given everything it depends on.
 *
 * Split out from [LocalModelPrefs.shouldOfferModel] so the rule can be asserted without a
 * `Context`. Each clause suppresses a distinct way the offer would misbehave: on ineligible
 * hardware it advertises something unrunnable, offline it invites a download that cannot start,
 * after a decision it re-asks a settled question, and past the dismissal budget it becomes a
 * card that will not go away.
 */
internal fun shouldOfferLocalModel(
    deviceEligible: Boolean,
    networkAvailable: Boolean,
    choice: LocalModelChoice,
    dismissCount: Int,
): Boolean =
    deviceEligible &&
        networkAvailable &&
        choice == LocalModelChoice.UNDECIDED &&
        dismissCount < LocalModelPrefs.MAX_OFFER_DISMISSALS
