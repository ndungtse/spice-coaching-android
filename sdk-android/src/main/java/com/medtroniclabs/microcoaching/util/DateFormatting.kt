package com.medtroniclabs.microcoaching.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the date portion of a backend ISO-8601 timestamp
 * ("2026-07-02T09:15:00Z", offset, or naive variants) as e.g. "2 Jul 2026" in
 * the device locale. Only the leading `yyyy-MM-dd` is parsed, so every ISO-8601
 * shape the backend emits is covered without timezone handling. Returns null on
 * blank or malformed input.
 */
fun isoDateLabel(iso: String?): String? {
    if (iso.isNullOrBlank() || iso.length < 10) return null
    return runCatching {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso.substring(0, 10))
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(date!!)
    }.getOrNull()
}

/**
 * Compact, locale-aware "day month, HH:mm" label for an epoch-millis timestamp
 * (e.g. "29 Jun, 14:30"). A fresh [SimpleDateFormat] is created per call because
 * that type is not thread-safe.
 */
fun shortDateTimeLabel(epochMillis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMillis))

/**
 * Locale-aware "HH:mm" time label for an epoch-millis timestamp. A fresh
 * [SimpleDateFormat] is created per call because that type is not thread-safe.
 */
fun timeLabel(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))

/**
 * Human-friendly date-only label for an epoch-millis timestamp (e.g. "15 Jul
 * 2026") in the device locale — no time component. Used for coarse dates like a
 * module's assignment date where the clock time isn't meaningful. A fresh
 * [SimpleDateFormat] is created per call because that type is not thread-safe.
 */
fun friendlyDateLabel(epochMillis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMillis))
