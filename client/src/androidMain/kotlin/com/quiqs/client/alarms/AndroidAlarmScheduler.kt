@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.client.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.quiqs.shared.Alarm
import com.quiqs.shared.AlarmScheduler
import com.quiqs.shared.nextRecurringInstant
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.TimeZone

/**
 * Android impl of [AlarmScheduler] backed by [AlarmManager.setExactAndAllowWhileIdle].
 *
 * One scheduled wakeup per Q — the [Uuid] hashes to a stable PendingIntent request code
 * so re-syncing reliably overwrites the previous schedule. The receiver
 * ([AlarmReceiver]) is responsible for posting the notification and, for recurring
 * alarms, re-scheduling the next instant.
 *
 * Permission: relies on `USE_EXACT_ALARM` (auto-granted for alarm/calendar-category apps
 * targeting API 33+) and falls back to `SCHEDULE_EXACT_ALARM` on API 31-32. If neither
 * is granted at runtime, we degrade gracefully to [AlarmManager.setAndAllowWhileIdle]
 * (inexact, but fires through Doze).
 */
class AndroidAlarmScheduler(context: Context) : AlarmScheduler {

    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun sync(qId: Uuid, alarm: Alarm?) {
        cancel(qId)
        val triggerAt = alarm?.nextFireInstant() ?: return
        scheduleExact(qId, triggerAt)
    }

    private fun cancel(qId: Uuid) {
        val pi = pendingIntent(qId, flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    private fun scheduleExact(qId: Uuid, triggerAt: Instant) {
        val pi = pendingIntent(qId, flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ?: return
        val triggerMs = triggerAt.toEpochMilliseconds()
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } catch (_: SecurityException) {
            // Exact-alarm permission revoked (only reachable on API 31-32 since
            // USE_EXACT_ALARM is auto-granted for our app category). Fall back to an
            // inexact wakeup so the user still gets a notification, just not at the
            // exact second.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    private fun pendingIntent(qId: Uuid, flags: Int): PendingIntent? {
        val intent = Intent(appContext, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(AlarmReceiver.EXTRA_Q_ID, qId.toString())
        }
        return PendingIntent.getBroadcast(appContext, qId.requestCode(), intent, flags)
    }
}

/**
 * Compute when this alarm should next wake the device, or `null` if it shouldn't.
 *
 * - One-off: the absolute instant, unless already fired or in the past.
 * - Recurring: next future occurrence per RFC 5545, or `null` if exhausted.
 * - Geofence: not handled by AlarmManager — returns `null`.
 */
internal fun Alarm.nextFireInstant(now: Instant = Clock.System.now()): Instant? = when (this) {
    is Alarm.OneOffTime -> at.takeIf { firedAt == null && it > now }
    is Alarm.RecurringTime -> nextRecurringInstant(this, now, TimeZone.currentSystemDefault())
    is Alarm.Geofence -> null
}

/** Stable per-Q PendingIntent request code derived from the Uuid string. */
internal fun Uuid.requestCode(): Int = toString().hashCode()
