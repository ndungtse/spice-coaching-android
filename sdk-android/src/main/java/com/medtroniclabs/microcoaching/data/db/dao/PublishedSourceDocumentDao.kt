package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.medtroniclabs.microcoaching.data.db.entity.PublishedSourceDocumentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/replace access for the source-document catalogue. The list is
 * server-authoritative and refreshed wholesale each sync, so the only mutating
 * operation is the atomic [replaceAll].
 *
 * The table holds both module-linked and CHW-assigned documents. [getById] spans
 * everything, because a chat citation can point at any document; the Knowledge
 * grid uses [observeAssigned], which is narrower.
 */
@Dao
interface PublishedSourceDocumentDao {

    /**
     * Reactive Knowledge-grid source: the documents assigned to this CHW, newest
     * assignment first.
     *
     * Only video is excluded — it is the same catalogue row the Training sub-tab
     * plays, so listing it here too would show one assignment in two places. Audio
     * belongs here (the Knowledge preview streams it via ExoPlayer, same as video).
     */
    @Query(
        """
        SELECT * FROM published_source_document
        WHERE assigned_at IS NOT NULL
          AND LOWER(COALESCE(source_type, '')) <> 'video'
        ORDER BY assigned_at DESC, rank ASC
        """,
    )
    fun observeAssigned(): Flow<List<PublishedSourceDocumentEntity>>

    /** How many documents the grid would show — drives the cold-start sync nudge. */
    @Query(
        """
        SELECT COUNT(*) FROM published_source_document
        WHERE assigned_at IS NOT NULL
          AND LOWER(COALESCE(source_type, '')) <> 'video'
        """,
    )
    suspend fun countAssigned(): Int

    /** The latest presigned URL for one document (used at download time). */
    @Query("SELECT * FROM published_source_document WHERE source_document_id = :id")
    suspend fun getById(id: String): PublishedSourceDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<PublishedSourceDocumentEntity>)

    @Query("DELETE FROM published_source_document")
    suspend fun deleteAll()

    /**
     * Atomically swap the whole catalogue for [rows] — the published list is a
     * full server snapshot, so stale rows (documents unpublished server-side)
     * must not linger. Skips the wipe when [rows] is empty so a transient blank
     * response can't clear a populated grid.
     */
    @Transaction
    suspend fun replaceAll(rows: List<PublishedSourceDocumentEntity>) {
        if (rows.isEmpty()) return
        deleteAll()
        upsertAll(rows)
    }
}
