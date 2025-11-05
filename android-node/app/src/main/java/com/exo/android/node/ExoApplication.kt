package com.exo.android.node

import android.app.Application
import timber.log.Timber

/**
 * Application class for EXO Android Node
 */
class ExoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.i("ExoApplication created")
    }
}
