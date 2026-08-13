package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable mirror of the source-document catalogue — **both** halves of it.
 *
 * The two halves answer different questions and are both needed:
 *  - documents linked to published modules, which is what a chat citation chip
 *    resolves against ([com.medtroniclabs.microcoaching.network.SourceDocumentUrlStore]);
 *  - documents assigned directly to this CHW, which is what the Knowledge grid
 *    lists. These carry a non-null [assignedAt]; module-linked rows do not.
 *
 * A document can be both, in which case [assignedAt] is kept — being assigned is
 * the more specific fact and is what decides whether it appears in the grid.
 *
 * The whole table is atomically replaced on each inbound sync
 * ([com.medtroniclabs.microcoaching.sync.pullSourceDocuments]), so presigned URLs
 * are refreshed every cycle before they lapse.
 *
 * Presigned URLs are short-lived. They are persisted so the list renders offline,
 * but a download or thumbnail fetch is only attempted while online — and the
 * durable [com.medtroniclabs.microcoaching.data.asset.AssetCache] keys on the
 * stable URL *path*, so an already-cached file still resolves after the signature
 * in [presignedUrl] / [thumbnailUrl] has rotated or expired.
 *
 * @param sourceDocumentId stable backend UUID — the asset-cache dedup key.
 * @param sourceType `pdf` | `pptx` | `docx` | `audio` | `video`. Audio and video
 *   are routed to the Training sub-tab instead, so they never reach this grid.
 * @param title display label.
 * @param originalFilename original upload name; drives the preview's
 *   extension-based routing and the "Downloading… <file>" snackbar.
 * @param assignedAt ISO-8601 assignment timestamp, or null for a row that is only
 *   module-linked. Doubles as the grid's filter and its ordering key.
 * @param presignedUrl latest presigned GET URL for the document, or null.
 * @param presignedExpiresAt absolute epoch-second expiry of [presignedUrl].
 * @param thumbnailUrl latest presigned thumbnail URL, or null when the backend
 *   didn't presign one; the card falls back to a placeholder.
 * @param thumbnailExpiresAt absolute epoch-second expiry of [thumbnailUrl].
 * @param rank server-supplied ordering.
 * @param lastSynced wall-clock ms of the sync that wrote this row.
 */
@Entity(tableName = "published_source_document")
data class PublishedSourceDocumentEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_document_id")
    val sourceDocumentId: String,

    @ColumnInfo(name = "source_type")
    val sourceType: String? = null,

    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "original_filename")
    val originalFilename: String? = null,

    @ColumnInfo(name = "description")
    val description: String? = null,

    /** Bucket-prefixed object path; lets an expired [presignedUrl] be re-signed. */
    @ColumnInfo(name = "storage_path")
    val storagePath: String? = null,

    @ColumnInfo(name = "thumbnail_storage_path")
    val thumbnailStoragePath: String? = null,

    @ColumnInfo(name = "assigned_at")
    val assignedAt: String? = null,

    @ColumnInfo(name = "presigned_url")
    val presignedUrl: String? = null,

    @ColumnInfo(name = "presigned_expires_at_epoch_sec")
    val presignedExpiresAt: Long? = null,

    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String? = null,

    @ColumnInfo(name = "thumbnail_expires_at_epoch_sec")
    val thumbnailExpiresAt: Long? = null,

    @ColumnInfo(name = "rank")
    val rank: Int = 0,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long? = null,
)
