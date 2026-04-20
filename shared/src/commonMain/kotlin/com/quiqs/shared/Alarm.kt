package com.quiqs.shared

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Alarm {
    @Serializable
    @SerialName("oneOffTime")
    data class OneOffTime(val at: Instant) : Alarm

    @Serializable
    @SerialName("recurringTime")
    data class RecurringTime(
        val rule: String,
        val until: Instant? = null,
    ) : Alarm

    @Serializable
    @SerialName("geofence")
    data class Geofence(
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Double,
        val activeUntil: Instant? = null,
    ) : Alarm
}
