package com.robert4lyf.hypershop.di

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency graph (the BangGym pattern): one object on the
 * Application, no Hilt. Tests build their own with fakes.
 */
class AppContainer(
    val context: Context,
) {
    val applicationScope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, error ->
                    Log.e(TAG, "Uncaught in applicationScope", error)
                },
        )

    private companion object {
        const val TAG = "AppContainer"
    }
}
