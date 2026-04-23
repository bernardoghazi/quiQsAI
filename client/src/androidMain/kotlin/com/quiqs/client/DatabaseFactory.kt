package com.quiqs.client

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.quiqs.shared.QRepository
import com.quiqs.shared.SqlDelightQRepository
import com.quiqs.shared.db.QDatabase

/**
 * Builds an Android-backed [QRepository] rooted in the app's private SQLite database
 * (`quiqs.db`). Safe to call multiple times — each call returns a new driver/repository
 * pair, though in practice [MainActivity] creates exactly one per process.
 */
fun createQRepository(context: Context): QRepository {
    val driver = AndroidSqliteDriver(
        schema = QDatabase.Schema,
        context = context.applicationContext,
        name = "quiqs.db",
    )
    return SqlDelightQRepository(driver)
}
