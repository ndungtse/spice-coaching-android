package com.medtroniclabs.microcoaching.ui.learn

/**
 * UI model for a single learning card inside a module (backed by one element
 * of [ModuleEntity.cardsJson]).
 *
 * Both Bangla and English fields are stored so the composable can select the
 * correct language without re-parsing. The caller (e.g. [LessonPlayerScreen])
 * reads [bodyBn] or [bodyEn] depending on the SDK language setting, then
 * splits on `\n` to produce the numbered item list.
 */
data class LessonCard(
    val titleBn: String,
    val titleEn: String? = null,
    val bodyBn: String,
    val bodyEn: String? = null,
    val cardFamilyId: String? = null,
)
