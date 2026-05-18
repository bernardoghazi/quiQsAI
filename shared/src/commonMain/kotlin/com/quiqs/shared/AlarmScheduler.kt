@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.shared

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Bridge between the cross-platform [Q]/[Alarm] model and platform-native alarm
 * scheduling (Android `AlarmManager`, future iOS/desktop equivalents).
 *
 * Designed around "one scheduled alarm per Q" — re-syncing replaces any previous
 * schedule for [qId]. The impl is responsible for filtering out alarms that don't
 * warrant a wakeup (e.g. fired one-offs, exhausted recurrences, geofences).
 */
interface AlarmScheduler {
    /**
     * Reconcile the OS-level schedule for [qId] with [alarm]. Pass `null` to cancel
     * any pending wakeup. Calling with a non-null [alarm] replaces whatever was
     * previously scheduled for the same [qId].
     */
    fun sync(qId: Uuid, alarm: Alarm?)

    /**
     * Dismiss any notification currently posted for [qId]. Idempotent — if no
     * notification is showing, the call is a no-op. Called when a Q is deleted so
     * the user isn't left with a stale notification referencing a Q that no longer
     * exists.
     */
    fun dismissActiveNotification(qId: Uuid)
}

/** Default fallback on platforms with no alarm support yet (iOS, desktop). */
object NoopAlarmScheduler : AlarmScheduler {
    override fun sync(qId: Uuid, alarm: Alarm?) = Unit
    override fun dismissActiveNotification(qId: Uuid) = Unit
}
