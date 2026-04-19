package com.quiqs.client

import androidx.compose.ui.window.ComposeUIViewController
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.quiqs.shared.SqlDelightQRepository
import com.quiqs.shared.db.QDatabase

fun MainViewController() = ComposeUIViewController {
    val driver = NativeSqliteDriver(QDatabase.Schema, "quiqs.db")
    val repository = SqlDelightQRepository(driver)
    App(repository)
}
