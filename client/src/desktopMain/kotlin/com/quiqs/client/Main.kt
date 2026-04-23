package com.quiqs.client

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.quiqs.shared.SqlDelightQRepository
import com.quiqs.shared.db.QDatabase

/**
 * Desktop entry point.
 *
 * Uses an in-memory SQLite database — desktop is a developer playground for the shared
 * UI, not a production target, so persistence across launches isn't wanted. No LLM or
 * audio providers are supplied; the stub providers from [App] surface an "unavailable"
 * message in those modes.
 */
fun main() = application {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { QDatabase.Schema.create(it) }
    val repository = SqlDelightQRepository(driver)
    Window(onCloseRequest = ::exitApplication, title = "quiQs") {
        App(repository)
    }
}
