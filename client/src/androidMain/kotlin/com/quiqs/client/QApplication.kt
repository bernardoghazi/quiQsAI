package com.quiqs.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.quiqs.client.alarms.AndroidAlarmScheduler
import com.quiqs.shared.AlarmScheduler
import com.quiqs.shared.QRepository
import com.quiqs.shared.SqlDelightQRepository
import com.quiqs.shared.db.QDatabase

/**
 * Application singleton that owns process-wide handles to the [QRepository] and
 * [AlarmScheduler]. Both [MainActivity] and the alarm receivers read from the same
 * instances so the in-process flow observers see writes made from background contexts.
 *
 * Also creates the notification channel used by alarm wakeups — has to happen once
 * before the first notification is posted, and `onCreate` is the canonical place.
 */
class QApplication : Application() {

    val alarmScheduler: AlarmScheduler by lazy { AndroidAlarmScheduler(this) }

    val repository: QRepository by lazy {
        val driver = AndroidSqliteDriver(
            schema = QDatabase.Schema,
            context = this,
            name = "quiqs.db",
        )
        SqlDelightQRepository(driver = driver, alarmScheduler = alarmScheduler)
    }

    override fun onCreate() {
        super.onCreate()
        createAlarmsChannel()
    }

    private fun createAlarmsChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            ALARMS_CHANNEL_ID,
            "Alarms",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Reminders fired from your quiQs alarms."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ALARMS_CHANNEL_ID = "alarms"
    }
}
