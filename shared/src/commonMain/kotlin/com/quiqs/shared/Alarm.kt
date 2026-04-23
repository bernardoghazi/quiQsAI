package com.quiqs.shared

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A trigger attached to a [Q] that determines when/where the user is reminded of it.
 *
 * The polymorphic JSON form uses `"type"` as the class discriminator (see [SerialName]
 * on each variant). This exact wire format is also part of the LLM prompt contract —
 * see `ALARMS_AND_TIME_RULES` in `LlmPromptBuilder`.
 */
@Serializable
sealed interface Alarm {

    /**
     * Fires exactly once at an absolute instant in time.
     *
     * @property at ISO-8601 moment when the alarm fires.
     */
    @Serializable
    @SerialName("oneOffTime")
    data class OneOffTime(val at: Instant) : Alarm

    /**
     * Fires on a recurring schedule described by an iCalendar RFC 5545 RRULE.
     *
     * @property rule RRULE string (e.g. `FREQ=WEEKLY;BYDAY=MO,WE,FR`).
     * @property until Optional end of the recurrence window; `null` means recur forever.
     */
    @Serializable
    @SerialName("recurringTime")
    data class RecurringTime(
        val rule: String,
        val until: Instant? = null,
    ) : Alarm

    /**
     * Fires when the device enters a circular region around a geographic point.
     *
     * @property latitude Center latitude in degrees.
     * @property longitude Center longitude in degrees.
     * @property radiusMeters Trigger radius around the center in metres.
     * @property activeUntil Optional deactivation time; `null` means the fence is always armed.
     */
    @Serializable
    @SerialName("geofence")
    data class Geofence(
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Double,
        val activeUntil: Instant? = null,
    ) : Alarm
}
