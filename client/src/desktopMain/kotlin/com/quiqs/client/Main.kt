package com.quiqs.client

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.quiqs.shared.SqlDelightQRepository
import com.quiqs.shared.db.QDatabase

fun main() = application {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { QDatabase.Schema.create(it) }
    val repository = SqlDelightQRepository(driver)
    Window(onCloseRequest = ::exitApplication, title = "quiQs") {
        App(repository)
    }
}
