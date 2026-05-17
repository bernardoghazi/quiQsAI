package com.quiqs.client

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.quiqs.client.alarms.AndroidAlarmScheduler
import com.quiqs.shared.QRepository
import com.quiqs.shared.SqlDelightQRepository
import com.quiqs.shared.db.QDatabase

/**
 * Builds an Android-backed [QRepository] rooted in the app's private SQLite database
 * (`quiqs.db`). Safe to call multiple times — each call returns a new driver/repository
 * pair, though in practice [MainActivity] creates exactly one per process.
 *
 * The repository is wired to an [AndroidAlarmScheduler] so add/edit/delete operations
 * keep the OS-level `AlarmManager` schedule in sync with the stored alarms.
 */
fun createQRepository(context: Context): QRepository {
    val driver = AndroidSqliteDriver(
        schema = QDatabase.Schema,
        context = context.applicationContext,
        name = "quiqs.db",
    )
    return SqlDelightQRepository(
        driver = driver,
        alarmScheduler = AndroidAlarmScheduler(context.applicationContext),
    )
}
