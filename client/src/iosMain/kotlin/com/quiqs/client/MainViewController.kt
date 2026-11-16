package com.quiqs.client

import androidx.compose.ui.window.ComposeUIViewController
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.quiqs.shared.SqlDelightQRepository
import com.quiqs.shared.db.QDatabase

/**
 * iOS entry point — returns a `UIViewController` the host Xcode project embeds.
 *
 * Uses the native SQLite driver for on-device persistence. LLM/audio providers are not
 * wired up yet (see `LlmEngine.ios.kt`), so the prompt screen will show the stub
 * unavailable messages from [App]'s defaults.
 */
fun MainViewController() = ComposeUIViewController {
    val driver = NativeSqliteDriver(QDatabase.Schema, "quiqs.db")
    val repository = SqlDelightQRepository(driver)
    App(repository)
}
