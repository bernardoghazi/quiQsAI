package com.quiqs.client.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Wakes when a scheduled alarm fires. Posts the user-facing notification, marks one-off
 * alarms as fired, and re-schedules the next instant for recurring alarms.
 *
 * Body is filled in by the "Alarms notification channel + receiver" step — for now this
 * is a placeholder so [AndroidAlarmScheduler]'s `PendingIntent` has a valid target.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // TODO: post notification + update repository (next step).
    }

    companion object {
        const val ACTION_FIRE = "com.quiqs.client.alarms.ACTION_FIRE"
        const val EXTRA_Q_ID = "qId"
    }
}
