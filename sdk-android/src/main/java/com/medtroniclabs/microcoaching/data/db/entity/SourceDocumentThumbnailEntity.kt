package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Holds presigned thumbnail URLs for source documents, one row per
 * `source_document_id`.
 *
 * Nothing reads or writes it: the source-document catalogue returns
 * `thumbnail_presigned_url` inline, so thumbnails are stored alongside the
 * documents themselves in `published_source_document` and `assigned_video`. The
 * entity stays declared so the table survives without a migration.
 */
@Entity(tableName = "source_document_thumbnail")
data class SourceDocumentThumbnailEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_document_id")
    val sourceDocumentId: String,

    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String? = null,

    @ColumnInfo(name = "thumbnail_expires_at_epoch_sec")
    val thumbnailExpiresAt: Long? = null,
)
