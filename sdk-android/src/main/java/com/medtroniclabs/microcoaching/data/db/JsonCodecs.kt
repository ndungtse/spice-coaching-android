package com.medtroniclabs.microcoaching.data.db

import com.medtroniclabs.microcoaching.network.SourceDocumentRef
import com.medtroniclabs.microcoaching.util.StrictJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Canonical encode/decode helpers for the SDK's JSON-blob columns.
 *
 * Previously each of [com.medtroniclabs.microcoaching.data.repository.ChatRepositoryImpl]
 * and [com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity] reimplemented the
 * same "encode to `[]` when empty, decode tolerantly (empty on malformed), drop blanks"
 * logic. These are the single source of truth.
 *
 * Semantics are intentionally tolerant — malformed input yields an empty list rather than
 * throwing, matching the forward-compatible skip-and-warn design for cached blobs.
 */
internal object JsonCodecs {

    private val stringListSerializer = ListSerializer(String.serializer())
    private val refListSerializer = ListSerializer(SourceDocumentRef.serializer())

    /** Encode a string list to a compact JSON array; "[]" when empty. */
    fun encodeStringList(list: List<String>): String =
        if (list.isEmpty()) "[]" else StrictJson.encodeToString(stringListSerializer, list)

    /** Decode a JSON array string to a string list; blanks and malformed input dropped. */
    fun decodeStringList(raw: String): List<String> =
        runCatching {
            StrictJson.parseToJsonElement(raw).jsonArray
                .map { it.jsonPrimitive.content }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

    /** Encode source-document refs to a compact JSON array; "[]" when empty. */
    fun encodeRefs(refs: List<SourceDocumentRef>): String =
        if (refs.isEmpty()) "[]" else StrictJson.encodeToString(refListSerializer, refs)

    /** Decode source-document refs; blank-id entries and malformed input dropped. */
    fun decodeRefs(raw: String): List<SourceDocumentRef> =
        runCatching {
            StrictJson.decodeFromString(refListSerializer, raw).filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
}
