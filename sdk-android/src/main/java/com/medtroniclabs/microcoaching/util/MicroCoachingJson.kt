package com.medtroniclabs.microcoaching.util

import kotlinx.serialization.json.Json

/**
 * Canonical kotlinx-serialization [Json] instances for the SDK.
 *
 * Historically every parser site declared its own `Json { ... }` block, and the config
 * silently varied (some enabled `isLenient`, some did not). These two instances are the
 * single source of truth so parsing behaviour is consistent and defined in one place.
 *
 * - [LenientJson]: tolerant of unknown keys AND relaxed token syntax. Use for backend/
 *   on-device payloads where the shape may drift additively.
 * - [StrictJson]: tolerant of unknown keys only. Use for our own serialized blobs
 *   (DB columns, LocalizedText) which are always well-formed.
 */
internal val LenientJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal val StrictJson: Json = Json {
    ignoreUnknownKeys = true
}
