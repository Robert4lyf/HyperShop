package com.robert4lyf.hypershop

import android.app.Application
import com.robert4lyf.hypershop.di.AppContainer

class HyperShopApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
