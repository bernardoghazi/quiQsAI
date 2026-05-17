@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.client.alarms

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.quiqs.client.MainActivity
import com.quiqs.client.QApplication
import com.quiqs.shared.Alarm
import com.quiqs.shared.Q
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives [AndroidAlarmScheduler] wakeups and posts the user-facing notification.
 *
 * Notification payload: title = Q text, action buttons for Snooze (15m / 1h / 1d) and
 * Mark done. Tapping the notification opens the app (deep-linking to the Q is a future
 * polish item). After firing:
 *  - one-off alarms get [Alarm.OneOffTime.firedAt] set, which propagates through the
 *    repository → grey-pill UI in [Alarm.OneOffTime].
 *  - recurring alarms re-sync with the scheduler so the next instant gets queued.
 *
 * Snooze / Mark done action handlers are filled in by the "Notification action handling"
 * step.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive action=${intent.action} qId=${intent.getStringExtra(EXTRA_Q_ID)}")
        val qIdString = intent.getStringExtra(EXTRA_Q_ID) ?: return
        val qId = try {
            Uuid.parse(qIdString)
        } catch (_: IllegalArgumentException) {
            return
        }
        when (intent.action) {
            ACTION_FIRE -> handleFire(context, qId, isSideReminder = false)
            ACTION_FIRE_SIDE_REMINDER -> handleFire(context, qId, isSideReminder = true)
            ACTION_SNOOZE_15M -> handleSnooze(context, qId, 15.minutes)
            ACTION_SNOOZE_1H -> handleSnooze(context, qId, 1.hours)
            ACTION_SNOOZE_1D -> handleSnooze(context, qId, 1.days)
            ACTION_MARK_DONE -> handleMarkDone(context, qId)
        }
    }

    /**
     * Post the notification for [qId]. When [isSideReminder] is false (the main fire
     * path) we also reconcile state: one-off alarms get `firedAt` stamped (greys the
     * pill) and recurring alarms re-sync to queue the next occurrence. Side reminders
     * are pure UX — they don't mutate the DB or touch the main recurrence schedule.
     */
    private fun handleFire(context: Context, qId: Uuid, isSideReminder: Boolean) {
        val app = context.applicationContext as QApplication
        val pending = goAsync()
        scope.launch {
            try {
                val q = app.repository.getById(qId)
                if (q == null) {
                    Log.w(TAG, "handleFire($qId): Q not found in repo — dropping")
                    return@launch
                }
                val alarm = q.alarms.firstOrNull()
                if (alarm == null) {
                    Log.w(TAG, "handleFire($qId): no alarm on Q — dropping")
                    return@launch
                }
                Log.d(TAG, "handleFire($qId, side=$isSideReminder): posting notification for alarm=$alarm")
                postNotification(context, q)
                if (isSideReminder) return@launch
                when (alarm) {
                    is Alarm.OneOffTime -> {
                        app.repository.update(
                            id = qId,
                            alarms = listOf(alarm.copy(firedAt = Clock.System.now())),
                        )
                    }
                    is Alarm.RecurringTime -> {
                        // Re-sync queues the next future occurrence; the rule itself
                        // isn't mutated.
                        app.alarmScheduler.sync(qId, alarm)
                    }
                    is Alarm.Geofence -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Dismiss the active notification and re-arm for [delta] later.
     *
     * One-off: shift `at` to `now + delta` and clear `firedAt`. The repository write
     * propagates through [com.quiqs.shared.AlarmScheduler.sync] so the OS schedule
     * follows the new time and the pill un-greys.
     *
     * Recurring: the rule itself stays put — we schedule a one-shot "side reminder" at
     * `now + delta` via [AndroidAlarmScheduler.scheduleSideReminder]. Stacked snoozes
     * are allowed (each adds its own side reminder).
     */
    private fun handleSnooze(context: Context, qId: Uuid, delta: Duration) {
        NotificationManagerCompat.from(context).cancel(qId.requestCode())
        val app = context.applicationContext as QApplication
        val pending = goAsync()
        scope.launch {
            try {
                val q = app.repository.getById(qId) ?: return@launch
                val alarm = q.alarms.firstOrNull() ?: return@launch
                val fireAt = Clock.System.now() + delta
                when (alarm) {
                    is Alarm.OneOffTime -> {
                        app.repository.update(
                            id = qId,
                            alarms = listOf(alarm.copy(at = fireAt, firedAt = null)),
                        )
                    }
                    is Alarm.RecurringTime -> {
                        (app.alarmScheduler as AndroidAlarmScheduler)
                            .scheduleSideReminder(qId, fireAt)
                    }
                    is Alarm.Geofence -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Acknowledge the firing without further action. Just dismisses the notification —
     * one-off alarms already have `firedAt` set from the fire path, and recurring
     * alarms already have their next occurrence queued.
     */
    private fun handleMarkDone(context: Context, qId: Uuid) {
        NotificationManagerCompat.from(context).cancel(qId.requestCode())
    }

    private fun postNotification(context: Context, q: Q) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "postNotification(${q.id}): POST_NOTIFICATIONS not granted — system will drop the notification")
            }
        }
        val builder = NotificationCompat.Builder(context, QApplication.ALARMS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(q.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, q.id))
            .addAction(0, "Snooze 15m", actionIntent(context, q.id, ACTION_SNOOZE_15M))
            .addAction(0, "Snooze 1h", actionIntent(context, q.id, ACTION_SNOOZE_1H))
            .addAction(0, "Snooze 1d", actionIntent(context, q.id, ACTION_SNOOZE_1D))
            .addAction(0, "Done", actionIntent(context, q.id, ACTION_MARK_DONE))
        // Notification ID matches the alarm's PendingIntent request code so a subsequent
        // post for the same Q replaces the previous notification rather than stacking.
        NotificationManagerCompat.from(context).notify(q.id.requestCode(), builder.build())
    }

    private fun actionIntent(context: Context, qId: Uuid, action: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_Q_ID, qId.toString())
        }
        // Each (qId, action) pair gets a distinct PendingIntent so a snooze button doesn't
        // collide with the Mark-done button for the same Q.
        val code = (qId.toString() + "/" + action).hashCode()
        return PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(context: Context, qId: Uuid): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // SINGLE_TOP pairs with the manifest launchMode so an already-running
            // MainActivity receives this via onNewIntent rather than being recreated.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_Q_ID, qId.toString())
        }
        // Per-Q request code so two pending notifications for different Qs don't end up
        // sharing the same PendingIntent and losing their qId extras.
        return PendingIntent.getActivity(
            context,
            qId.requestCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_FIRE = "com.quiqs.client.alarms.ACTION_FIRE"
        const val ACTION_FIRE_SIDE_REMINDER = "com.quiqs.client.alarms.ACTION_FIRE_SIDE_REMINDER"
        const val ACTION_SNOOZE_15M = "com.quiqs.client.alarms.ACTION_SNOOZE_15M"
        const val ACTION_SNOOZE_1H = "com.quiqs.client.alarms.ACTION_SNOOZE_1H"
        const val ACTION_SNOOZE_1D = "com.quiqs.client.alarms.ACTION_SNOOZE_1D"
        const val ACTION_MARK_DONE = "com.quiqs.client.alarms.ACTION_MARK_DONE"
        const val EXTRA_Q_ID = "qId"

        // Receiver-wide coroutine scope: BroadcastReceiver instances are short-lived,
        // but our async work spans the goAsync() window and shouldn't be tied to a
        // single instance's lifetime.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private const val TAG = "quiqs/alarms"
    }
}
