package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable mirror of the backend's ranked chat FAQs (`GET /sync/chat-faqs`) —
 * the frequently-asked chat questions surfaced as suggestion chips. Synced by
 * [com.medtroniclabs.microcoaching.sync.SyncApi.pullChatFaqs] and consumed by
 * [com.medtroniclabs.microcoaching.ui.chat.ChatFaqRepository]; the static
 * [com.medtroniclabs.microcoaching.ui.chat.ChatSuggestionDefaults] are the
 * fallback when this table is empty.
 *
 * [questionJson] is a serialized
 * [com.medtroniclabs.microcoaching.data.localized.LocalizedText] (`{bn, en?}`) —
 * one blob rather than split columns, matching the backend's `question` object
 * and the app-wide `*_json` localization convention. Bangla is guaranteed by the
 * backend; English is filled in place by on-device translation when the pack is
 * available (see `ChatFaqRepository.translatePending`).
 *
 * @param faqId stable backend UUID (`id`).
 * @param questionJson serialized `LocalizedText` for the question.
 * @param rank server-supplied display order (lower = higher priority).
 * @param occurrenceCount how often the question has been asked (backend metric).
 * @param lastSeenAt backend ISO-8601 timestamp of the last occurrence, or null.
 * @param lastSynced wall-clock ms of the sync that wrote this row.
 */
@Entity(tableName = "chat_faq")
data class ChatFaqEntity(
    @PrimaryKey
    @ColumnInfo(name = "faq_id")
    val faqId: String,

    @ColumnInfo(name = "question_json")
    val questionJson: String,

    @ColumnInfo(name = "rank")
    val rank: Int = 0,

    @ColumnInfo(name = "occurrence_count")
    val occurrenceCount: Int = 0,

    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: String? = null,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long? = null,
)
