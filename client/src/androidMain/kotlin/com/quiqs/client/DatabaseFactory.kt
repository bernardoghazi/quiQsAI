package com.quiqs.client

import android.content.Context
import com.quiqs.shared.QRepository

/**
 * Returns the process-wide [QRepository] held by [QApplication]. Delegating here keeps
 * a single SQLDelight driver — and therefore a single set of Flow observers — across
 * activities, alarm receivers, and any other entry point.
 */
fun createQRepository(context: Context): QRepository =
    (context.applicationContext as QApplication).repository
