package com.medtroniclabs.microcoaching.sdk

import com.medtroniclabs.microcoaching.ui.chat.ChatMessage

/**
 * Pull-pattern public interface for SPICE to query SDK-owned data on demand.
 *
 * Inject via Hilt in SPICE's AppModule:
 * ```kotlin
 * @Provides @Singleton
 * fun provideCoachingDataRepository(): CoachingDataRepository =
 *     MicroCoachingSDK.getInstance().dataRepository
 * ```
 *
 * Then inject into any SPICE ViewModel:
 * ```kotlin
 * @HiltViewModel
 * class MyViewModel @Inject constructor(
 *     private val coachingRepo: CoachingDataRepository,
 * ) : ViewModel()
 * ```
 *
 * Push-pattern alternative: register [MicroCoachingDataCallback] at init time.
 */
interface CoachingDataRepository {

    // ── Chat ──────────────────────────────────────────────────────────────────

    /** Returns all messages for a given chat session, ordered by time ascending. */
    suspend fun getChatHistory(sessionId: String): List<ChatMessage>

    /** Returns distinct session IDs, most recent first. */
    suspend fun getAllSessionIds(): List<String>

    // ── Coaching Events ───────────────────────────────────────────────────────

    /**
     * Returns coaching events that have not yet been synced to the backend.
     * Each entry is a Map whose keys match the coaching event schema.
     */
    suspend fun getPendingCoachingEvents(): List<Map<String, Any>>

    /**
     * Mark events as synced after SPICE (or the SDK) has forwarded them to the backend.
     * @param eventIds UUID strings matching [CoachingEventEntity.eventId].
     */
    suspend fun markEventsSynced(eventIds: List<String>)

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Export all SDK-owned data in a single snapshot.
     * Use this if SPICE wants to forward SDK data to its own backend
     * rather than using the SDK's built-in sync.
     */
    suspend fun exportAllData(): SdkDataExport
}

/** Full data snapshot for SPICE export. */
data class SdkDataExport(
    val chatMessages: List<ChatMessage>,
    val coachingEvents: List<Map<String, Any>>,
    val exportedAtMs: Long = System.currentTimeMillis(),
)
