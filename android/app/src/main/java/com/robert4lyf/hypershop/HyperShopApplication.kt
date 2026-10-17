package com.robert4lyf.hypershop

import android.app.Application
import androidx.work.Configuration
import com.robert4lyf.hypershop.data.EpochMarker
import com.robert4lyf.hypershop.di.AppContainer
import timber.log.Timber

class HyperShopApplication :
    Application(),
    Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        container = AppContainer(this)
        container.startUp(EpochMarker(noBackupFilesDir))
    }

    /** WorkManager is initialised on demand with the container's factory (InitializationProvider's initializer is removed). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(container.workerFactory).build()
}
