package com.medtroniclabs.microcoaching.domain.telemetry

import java.security.MessageDigest

/**
 * Returns the first 8 hex characters of SHA-256(this).
 *
 * Used to hash quasi-identifiers (CHW IDs, patient IDs) before logging or
 * including in sync session identifiers so the raw value never appears in
 * logcat or backend ingestion paths.
 */
internal fun String.sha256Short(): String = try {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    bytes.joinToString("") { "%02x".format(it) }.take(8)
} catch (_: Exception) {
    "hasherr"
}
