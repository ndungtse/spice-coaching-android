package com.medtroniclabs.microcoaching.ui

import android.content.Context
import android.content.res.Configuration
import com.medtroniclabs.microcoaching.Language
import java.util.Locale

/**
 * Wraps a [Context] with the locale that matches the SDK-configured [Language].
 *
 * Usage — call at every Compose root before [MicroCoachingTheme]:
 * ```kotlin
 * val langCtx = SdkLocaleHelper.wrap(this, MicroCoachingSDK.getInstance().config.language)
 * CompositionLocalProvider(LocalContext provides langCtx) {
 *     MicroCoachingTheme { … }
 * }
 * ```
 * All `stringResource()` calls inside that tree then resolve strings from the
 * correct `res/values-xx/strings.xml` file rather than the device locale.
 */
object SdkLocaleHelper {
    fun wrap(base: Context, language: Language): Context {
        val locale = when (language) {
            Language.BANGLA -> Locale("bn", "BD")
            Language.ENGLISH -> Locale("en", "US")
        }
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
