package com.quiqs.client

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.quiqs.shared.QRepository
import com.quiqs.shared.SqlDelightQRepository
import com.quiqs.shared.db.QDatabase

fun createQRepository(context: Context): QRepository {
    val driver = AndroidSqliteDriver(
        schema = QDatabase.Schema,
        context = context.applicationContext,
        name = "quiqs.db",
    )
    return SqlDelightQRepository(driver)
}
