package com.medtroniclabs.microcoaching.ui.onboarding

import com.medtroniclabs.microcoaching.Language

/**
 * Content for a single onboarding slide.
 *
 * [illustrationEmoji] is used in Phase 0.5 as a placeholder illustration.
 * Phase 3 will replace it with a proper drawable resource.
 */
data class OnboardingSlide(
    val title: String,
    val body: String,
    val illustrationEmoji: String,
)

/** Hardcoded onboarding slides for Phase 0.5. Replaced by CMS content in Phase 3. */
object OnboardingSlideData {

    const val SLIDE_COUNT = 3

    fun slidesFor(language: Language): List<OnboardingSlide> = when (language) {
        Language.BANGLA -> banglaSlides
        Language.ENGLISH -> englishSlides
    }

    private val englishSlides = listOf(
        OnboardingSlide(
            title = "Learn at your own pace",
            body = "Short, focused lessons designed for busy Community Health Workers. " +
                "Complete a module in under 10 minutes — anytime, anywhere.",
            illustrationEmoji = "📚",
        ),
        OnboardingSlide(
            title = "Get better with every patient",
            body = "Apply what you learn directly to your patients' care plans. " +
                "Evidence-based guidance for NCD management in Bangladesh.",
            illustrationEmoji = "🩺",
        ),
        OnboardingSlide(
            title = "Track your progress",
            body = "Earn badges as you complete modules. Your supervisor can see your " +
                "achievements and support your growth.",
            illustrationEmoji = "🏅",
        ),
    )

    private val banglaSlides = listOf(
        OnboardingSlide(
            title = "আপনার গতিতে শিখুন",
            body = "ব্যস্ত কমিউনিটি স্বাস্থ্যকর্মীদের জন্য সংক্ষিপ্ত, কেন্দ্রীভূত পাঠ। " +
                "১০ মিনিটের মধ্যে একটি মডিউল শেষ করুন — যেকোনো সময়, যেকোনো জায়গায়।",
            illustrationEmoji = "📚",
        ),
        OnboardingSlide(
            title = "প্রতিটি রোগীর সাথে আরও ভালো হন",
            body = "আপনার শেখা সরাসরি রোগীর যত্ন পরিকল্পনায় প্রয়োগ করুন। " +
                "বাংলাদেশে NCD ব্যবস্থাপনার জন্য প্রমাণ-ভিত্তিক নির্দেশনা।",
            illustrationEmoji = "🩺",
        ),
        OnboardingSlide(
            title = "আপনার অগ্রগতি ট্র্যাক করুন",
            body = "মডিউল সম্পন্ন করে ব্যাজ অর্জন করুন। আপনার সুপারভাইজার আপনার অর্জন " +
                "দেখতে পারবেন এবং আপনার বিকাশকে সমর্থন করতে পারবেন।",
            illustrationEmoji = "🏅",
        ),
    )
}
