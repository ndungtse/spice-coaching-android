package com.medtroniclabs.microcoaching.domain.telemetry

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/**
 * One-way patient identifier hasher.
 *
 * The privacy contract for the SDK (per `docs/UseCases_v2.md` §"Privacy & Data
 * Rules", line 508) is that **raw SPICE patient IDs never leave the device**.
 * Every event row that carries a patient reference stores the SHA-256 hex
 * digest of the raw ID in `patient_id_hash` instead.
 *
 * Hashing is deterministic — the backend can join across event rows for the
 * same patient by comparing hashes — but irreversible.
 *
 * Same hash everywhere: the digest is over the raw UTF-8 bytes of the ID
 * string. SPICE passes whatever shape it uses (`patientTrackId` as a `Long`,
 * a UUID, etc.) — callers stringify before hashing.
 */
internal object PatientIdHasher {
    /** Legacy deterministic hash (no per-install pepper). Avoid for patient identifiers. */
    @Deprecated("Use hash(context, rawSpicePatientId) for patient identifiers.")
    fun hash(rawSpicePatientId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(rawSpicePatientId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private const val PREFS_NAME = "micro_coaching_security"
    private const val KEY_HASH_PEPPER = "patient_hash_pepper"

    fun hash(context: Context, rawSpicePatientId: String): String {
        val pepper = getOrCreatePepper(context)
        return MessageDigest.getInstance("SHA-256")
            .digest("$pepper:$rawSpicePatientId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun getOrCreatePepper(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_HASH_PEPPER, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_HASH_PEPPER, generated).apply()
        return generated
    }
}
