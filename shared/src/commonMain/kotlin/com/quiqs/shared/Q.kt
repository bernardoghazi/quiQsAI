package com.quiqs.shared

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

/**
 * A Q — the app's core domain object.
 *
 * A Q is a user-authored note attached to zero or more [tags] and zero or more [alarms].
 * Alarms are what turn a Q into a reminder (one-off time, recurring rule, or geofence).
 *
 * @property id Stable UUID assigned at creation.
 * @property text Note content as written by the user, in their language.
 * @property tags Free-form classifier labels.
 * @property alarms Triggers that surface this Q to the user. See [Alarm].
 * @property createdAt When the Q was first persisted.
 * @property updatedAt When the Q was last modified.
 */
data class Q(
    val id: Uuid,
    val text: String,
    val tags: List<String> = emptyList(),
    val alarms: List<Alarm> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
)
