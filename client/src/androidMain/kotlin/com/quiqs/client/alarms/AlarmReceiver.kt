@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.client.alarms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.quiqs.client.MainActivity
import com.quiqs.client.QApplication
import com.quiqs.shared.Alarm
import com.quiqs.shared.Q
import kotlin.time.Clock
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
        val qIdString = intent.getStringExtra(EXTRA_Q_ID) ?: return
        val qId = try {
            Uuid.parse(qIdString)
        } catch (_: IllegalArgumentException) {
            return
        }
        when (intent.action) {
            ACTION_FIRE -> handleFire(context, qId)
            // Snooze / Mark-done branches are added in the next step.
        }
    }

    private fun handleFire(context: Context, qId: Uuid) {
        val app = context.applicationContext as QApplication
        val pending = goAsync()
        scope.launch {
            try {
                val q = app.repository.getById(qId) ?: return@launch
                val alarm = q.alarms.firstOrNull() ?: return@launch
                postNotification(context, q)
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

    private fun postNotification(context: Context, q: Q) {
        val builder = NotificationCompat.Builder(context, QApplication.ALARMS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(q.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
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

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_FIRE = "com.quiqs.client.alarms.ACTION_FIRE"
        const val ACTION_SNOOZE_15M = "com.quiqs.client.alarms.ACTION_SNOOZE_15M"
        const val ACTION_SNOOZE_1H = "com.quiqs.client.alarms.ACTION_SNOOZE_1H"
        const val ACTION_SNOOZE_1D = "com.quiqs.client.alarms.ACTION_SNOOZE_1D"
        const val ACTION_MARK_DONE = "com.quiqs.client.alarms.ACTION_MARK_DONE"
        const val EXTRA_Q_ID = "qId"

        // Receiver-wide coroutine scope: BroadcastReceiver instances are short-lived,
        // but our async work spans the goAsync() window and shouldn't be tied to a
        // single instance's lifetime.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
