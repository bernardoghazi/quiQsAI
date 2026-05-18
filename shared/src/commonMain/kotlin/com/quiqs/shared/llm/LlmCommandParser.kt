package com.quiqs.shared.llm

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json

private val parserJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    isLenient = true
}

/**
 * Extracts and decodes a [QCommand] from raw LLM output.
 *
 * The model ideally emits a single JSON object, but in practice output can include
 * leading text, a ```json fence, or trailing commentary. [parse] tolerates those by
 * locating the first balanced `{ … }` block and decoding it.
 *
 * Gemma 4 E2B is small enough that it sometimes mangles ISO-8601 datetimes — wrong
 * digit counts in year/hour/offset fields, missing colons in the offset, etc.
 * [repairInstantsIn] runs a best-effort repair on the alarm-time fields before
 * deserialization so those outputs still produce a usable [Alarm] instead of a
 * SerializationException all the way back to the UI.
 */
class LlmCommandParser {

    /**
     * Parse [raw] into a [QCommand], or return a [Result.failure] with a human-readable
     * reason (no JSON found, or JSON that does not match any command variant).
     */
    fun parse(raw: String): Result<QCommand> {
        val payload = extractJsonObject(raw)
            ?: return Result.failure(IllegalArgumentException("No JSON object found in LLM output"))
        val repaired = repairInstantsIn(payload)
        return runCatching { parserJson.decodeFromString(QCommand.serializer(), repaired) }
    }

    /**
     * Finds the first top-level `{ … }` block in [raw], respecting string literals and
     * escape sequences so braces inside JSON strings don't confuse the balance counter.
     * Returns the substring, or `null` if no balanced object exists.
     */
    private fun extractJsonObject(raw: String): String? {
        val trimmed = raw.trim().removeFencing()
        val start = trimmed.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until trimmed.length) {
            val c = trimmed[i]
            if (escape) { escape = false; continue }
            when {
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return trimmed.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Strips a leading ``` fence (with optional language tag) and the matching trailing fence. */
    private fun String.removeFencing(): String {
        if (!startsWith("```")) return this
        val firstNewline = indexOf('\n')
        if (firstNewline < 0) return this
        val withoutOpen = substring(firstNewline + 1)
        val closeIdx = withoutOpen.lastIndexOf("```")
        return if (closeIdx >= 0) withoutOpen.substring(0, closeIdx).trim() else withoutOpen
    }
}

/**
 * Replaces values of alarm-time fields (`at`, `dtStart`, `until`, `activeUntil`) in
 * the JSON [json] with a repaired version when the original isn't a valid ISO-8601
 * instant. Pass-through if the value already parses, or if it can't be coaxed into shape.
 */
internal fun repairInstantsIn(json: String): String =
    json.replace(INSTANT_FIELD_REGEX) { match ->
        val key = match.groupValues[1]
        val value = match.groupValues[2]
        val cleaned = repairLooseInstant(value) ?: value
        "\"$key\":\"$cleaned\""
    }

private val INSTANT_FIELD_REGEX = Regex(
    "\"(at|dtStart|until|activeUntil)\"\\s*:\\s*\"([^\"]*)\""
)

/**
 * Best-effort repair of a string the model emitted in an alarm-time field. Returns the
 * cleaned ISO-8601 instant on success, the original on no-op (already valid), or `null`
 * if the input doesn't even loosely match a datetime+offset shape.
 *
 * Strategy: match a permissive `YYYY-MM-DDTHH:MM:SS±HH(:MM)?` regex, then clamp each
 * field to its valid range and zero-pad. Years that don't fall in `1900..3000` (or
 * aren't exactly four digits) are substituted with the device's current year — the
 * model usually meant "now-ish" when it produces a malformed year. Overlong fields
 * (e.g. `:0000` minutes) are truncated to two leading digits; underlong fields are
 * padded.
 */
internal fun repairLooseInstant(raw: String): String? {
    val trimmed = raw.trim()
    // Already valid → leave it alone.
    runCatching { Instant.parse(trimmed) }.onSuccess { return trimmed }

    val match = LOOSE_INSTANT_REGEX.matchEntire(trimmed) ?: return null
    val groups = match.groupValues
    val yearStr = groups[1]
    val monthStr = groups[2]
    val dayStr = groups[3]
    val hourStr = groups[4]
    val minStr = groups[5]
    val secStr = groups[6]
    val signStr = groups[7]
    val offAStr = groups[8]
    val offBStr = groups[9] // empty when no colon in the offset

    val currentYear = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .year
    val year = yearStr.toIntOrNull()
        ?.takeIf { yearStr.length == 4 && it in 1900..3000 }
        ?: currentYear

    val month = clampPad(monthStr, 1..12) ?: return null
    val day = clampPad(dayStr, 1..31) ?: return null
    val hour = clampPad(hourStr, 0..23) ?: return null
    val minute = clampPad(minStr, 0..59) ?: return null
    // Seconds are spec'd to be `:00` by the prompt; tolerate other values via clamp.
    val second = clampPad(secStr, 0..59) ?: "00"

    val (offHour, offMinute) = if (offBStr.isNotEmpty()) {
        val h = clampPad(offAStr, 0..18) ?: return null
        // Truncate over-long minute fields (`:0000` → first two digits → `00`).
        val m = clampPad(offBStr.take(2), 0..59) ?: return null
        h to m
    } else when (offAStr.length) {
        in 1..2 -> {
            val h = clampPad(offAStr, 0..18) ?: return null
            h to "00"
        }
        3, 4 -> {
            // Basic-form offset like `-0300` or `+0530` — split positionally.
            val h = clampPad(offAStr.dropLast(2), 0..18) ?: return null
            val m = clampPad(offAStr.takeLast(2), 0..59) ?: return null
            h to m
        }
        else -> {
            // Garbage tail: keep what looks like hours and minutes.
            val h = clampPad(offAStr.take(2), 0..18) ?: return null
            val m = clampPad(offAStr.substring(2, (offAStr.length).coerceAtMost(4)), 0..59) ?: return null
            h to m
        }
    }

    val yearPadded = year.toString().padStart(4, '0')
    return "$yearPadded-$month-${day}T$hour:$minute:$second$signStr$offHour:$offMinute"
}

private val LOOSE_INSTANT_REGEX = Regex(
    """^(\d+)-(\d+)-(\d+)T(\d+):(\d+):(\d+)([+-])(\d+)(?::(\d+))?$"""
)

/** Parses [s] as a non-negative integer and, if it falls in [range], returns it zero-padded to width 2. */
private fun clampPad(s: String, range: IntRange): String? {
    val n = s.toIntOrNull() ?: return null
    if (n !in range) return null
    return n.toString().padStart(2, '0')
}
