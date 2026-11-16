@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.client.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
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

    /**
     * Per-Q set of outstanding side-reminder fire instants. Lets us cancel them
     * alongside the main schedule in [sync] — AlarmManager itself has no enumeration API.
     *
     * In-memory only by design: side reminders survive AlarmManager but not the process,
     * so this map only tracks the ones scheduled in this process. Stale side reminders
     * left behind by a previous process are caught by [AlarmReceiver]'s `completed`
     * short-circuit at fire time.
     */
    private val sideReminders = mutableMapOf<Uuid, MutableSet<Long>>()

    override fun sync(qId: Uuid, alarm: Alarm?) {
        cancel(qId)
        cancelSideReminders(qId)
        val triggerAt = alarm?.nextFireInstant()
        if (triggerAt == null) {
            Log.d(TAG, "sync($qId): no future fire instant for alarm=$alarm — cancelled only")
            return
        }
        scheduleExact(qId, triggerAt)
    }

    override fun dismissActiveNotification(qId: Uuid) {
        // Posted notifications use qId.requestCode() as their id (see
        // AlarmReceiver.postNotification). cancel() is a no-op if nothing is showing.
        NotificationManagerCompat.from(appContext).cancel(qId.requestCode())
        Log.d(TAG, "dismissActiveNotification($qId): cancelled")
    }

    private fun cancel(qId: Uuid) {
        val pi = pendingIntent(qId, flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    @Synchronized
    private fun cancelSideReminders(qId: Uuid) {
        val tracked = sideReminders.remove(qId) ?: return
        for (fireAtMs in tracked) {
            val code = sideReminderRequestCode(qId, fireAtMs)
            val intent = Intent(appContext, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_FIRE_SIDE_REMINDER
                putExtra(AlarmReceiver.EXTRA_Q_ID, qId.toString())
            }
            val pi = PendingIntent.getBroadcast(
                appContext,
                code,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pi != null) {
                alarmManager.cancel(pi)
                pi.cancel()
            }
        }
        Log.d(TAG, "cancelSideReminders($qId): cancelled ${tracked.size}")
    }

    private fun scheduleExact(qId: Uuid, triggerAt: Instant) {
        val pi = pendingIntent(qId, flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ?: return
        scheduleAt(pi, triggerAt, label = "sync($qId)")
    }

    /**
     * Schedules a one-off "side reminder" wakeup that re-fires the notification for
     * [qId] at [fireAt]. Used by the recurring-alarm snooze path: the recurrence itself
     * stays untouched, but the user gets an extra reminder at the snoozed time.
     *
     * Each call uses a unique PendingIntent (keyed on `qId + fireAt`) so stacked snoozes
     * — e.g. the user hits "+15m" twice — produce two distinct side reminders rather
     * than the second overwriting the first.
     */
    fun scheduleSideReminder(qId: Uuid, fireAt: Instant) {
        val fireAtMs = fireAt.toEpochMilliseconds()
        val intent = Intent(appContext, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE_SIDE_REMINDER
            putExtra(AlarmReceiver.EXTRA_Q_ID, qId.toString())
        }
        val code = sideReminderRequestCode(qId, fireAtMs)
        val pi = PendingIntent.getBroadcast(
            appContext,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        recordSideReminder(qId, fireAtMs)
        scheduleAt(pi, fireAt, label = "scheduleSideReminder($qId)")
    }

    @Synchronized
    private fun recordSideReminder(qId: Uuid, fireAtMs: Long) {
        sideReminders.getOrPut(qId) { mutableSetOf() }.add(fireAtMs)
    }

    private fun scheduleAt(pi: PendingIntent, triggerAt: Instant, label: String) {
        val triggerMs = triggerAt.toEpochMilliseconds()
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            Log.d(TAG, "$label: scheduled EXACT for $triggerAt (in ${triggerMs - System.currentTimeMillis()}ms)")
        } catch (e: SecurityException) {
            // Exact-alarm permission revoked (only reachable on API 31-32 since
            // USE_EXACT_ALARM is auto-granted for our app category). Fall back to an
            // inexact wakeup so the user still gets a notification, just not at the
            // exact second.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            Log.w(TAG, "$label: exact alarm denied (${e.message}); fell back to inexact for $triggerAt")
        }
    }

    private fun sideReminderRequestCode(qId: Uuid, fireAtMs: Long): Int =
        (qId.toString() + "/side/" + fireAtMs).hashCode()

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

private const val TAG = "quiqs/alarms"
