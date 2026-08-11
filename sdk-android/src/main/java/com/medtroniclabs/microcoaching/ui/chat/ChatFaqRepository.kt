package com.medtroniclabs.microcoaching.ui.chat

import android.util.Log
import com.medtroniclabs.microcoaching.ai.translation.OnDeviceTranslator
import com.medtroniclabs.microcoaching.data.db.dao.ChatFaqDao
import com.medtroniclabs.microcoaching.data.localized.LocalizedText
import com.medtroniclabs.microcoaching.data.localized.toJsonString

/**
 * Turns synced `chat_faq` rows into [SuggestedQuestion]s and backfills their
 * English translation on-device. Shared by [com.medtroniclabs.microcoaching.sync.InboundSyncWorker]
 * (translate after each sync) and [ChatViewModel] (load suggestions + re-attempt
 * translation on chat open).
 *
 * The question is a serialized [LocalizedText] blob; Bangla is guaranteed by the
 * backend, English is filled here when the ML Kit pack is available. When the
 * pack isn't ready [OnDeviceTranslator.translateBnToEnResult] returns a
 * passthrough (`translated=false`) — we leave the row's English empty and try
 * again next time (background sync or the next chat open).
 */
internal class ChatFaqRepository(
    private val dao: ChatFaqDao,
) {

    /** True once at least one FAQ has been synced (drives use-cache-vs-defaults). */
    suspend fun hasCached(): Boolean = dao.count() > 0

    /** True when any cached FAQ still lacks an English question. */
    suspend fun hasPendingTranslation(): Boolean =
        dao.getAllOnce().any { LocalizedText.decode(it.questionJson).en.isNullOrBlank() }

    /**
     * Top-[limit] FAQs by rank, mapped to [SuggestedQuestion]. English may be
     * empty until translated — [SuggestionRow] falls back to the Bangla side, so
     * an English-mode chip shows Bangla until the backfill completes.
     */
    suspend fun loadSuggestions(limit: Int = DEFAULT_LIMIT): List<SuggestedQuestion> =
        dao.getAllOnce()
            .take(limit)
            .map { row ->
                val q = LocalizedText.decode(row.questionJson)
                SuggestedQuestion(
                    question = q.en.orEmpty(),
                    banglaQuestion = q.bn.orEmpty(),
                )
            }
            .filter { it.question.isNotBlank() || it.banglaQuestion.isNotBlank() }

    /**
     * Translate every cached FAQ that still lacks English (bn → en), writing the
     * result back in place. Ensures the pack is present first (downloads if
     * needed). Returns how many rows were updated. Safe to call repeatedly —
     * already-translated rows are skipped, and a passthrough leaves the row
     * untouched for the next attempt.
     */
    suspend fun translatePending(translator: OnDeviceTranslator): Int {
        val pending = dao.getAllOnce().mapNotNull { row ->
            val q = LocalizedText.decode(row.questionJson)
            val bn = q.bn?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!q.en.isNullOrBlank()) return@mapNotNull null
            row to bn
        }
        if (pending.isEmpty()) return 0

        translator.ensureModelReady()
        var updated = 0
        for ((row, bn) in pending) {
            val result = translator.translateBnToEnResult(bn)
            if (result.translated && result.text.isNotBlank()) {
                dao.updateQuestionJson(row.faqId, LocalizedText.fromBnEn(bn, result.text).toJsonString())
                updated++
            }
        }
        Log.i(TAG, "Chat FAQ translation: ${pending.size} pending, $updated translated bn→en")
        return updated
    }

    companion object {
        private const val TAG = "ChatFaqRepository"
        private const val DEFAULT_LIMIT = 8
    }
}
