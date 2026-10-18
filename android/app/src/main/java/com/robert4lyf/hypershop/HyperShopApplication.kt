package com.robert4lyf.hypershop

import android.app.Application
import androidx.work.Configuration
import com.robert4lyf.hypershop.data.EpochMarker
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.secrets.AndroidKeystoreCipher
import com.robert4lyf.hypershop.secrets.KeystoreApiKeyStore
import com.robert4lyf.hypershop.secrets.claudeKeyDataStore
import com.robert4lyf.hypershop.settings.Settings
import com.robert4lyf.hypershop.settings.settingsDataStore
import com.robert4lyf.hypershop.watch.ConnectIqWatchLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

class HyperShopApplication :
    Application(),
    Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        val keys =
            KeystoreApiKeyStore(
                claudeKeyDataStore,
                AndroidKeystoreCipher(),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
                onLost = { Settings(settingsDataStore).setKeyLostNotice(true) },
            )
        container = AppContainer(this, apiKeyStore = keys, watchLink = ConnectIqWatchLink(this))
        container.startUp(EpochMarker(noBackupFilesDir))
    }

    /** WorkManager is initialised on demand with the container's factory (InitializationProvider's initializer is removed). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(container.workerFactory).build()
}
