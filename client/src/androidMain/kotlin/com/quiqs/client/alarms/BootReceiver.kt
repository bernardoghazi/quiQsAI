package com.quiqs.client.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.quiqs.client.QApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms pending alarms after a device reboot.
 *
 * `AlarmManager` discards every scheduled wakeup on shutdown, so on `BOOT_COMPLETED`
 * we walk every Q and re-`sync` it. Ephemeral state — like a snooze's "side reminder"
 * that lives only as a PendingIntent — is intentionally not persisted and is lost
 * across reboot; only the alarm the user actually configured comes back.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as QApplication
        val pending = goAsync()
        scope.launch {
            try {
                val qs = app.repository.getAll()
                var scheduled = 0
                for (q in qs) {
                    val alarm = q.alarms.firstOrNull() ?: continue
                    app.alarmScheduler.sync(q.id, alarm)
                    scheduled++
                }
                Log.d(TAG, "BOOT_COMPLETED: re-armed $scheduled alarm(s) across ${qs.size} Qs")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "quiqs/alarms"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
