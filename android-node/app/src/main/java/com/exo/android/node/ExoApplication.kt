package com.exo.android.node

import android.app.Application
import timber.log.Timber

/**
 * Application class for EXO Android Node
 */
class ExoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Force IPv4 for all network operations (must be set before any networking)
        // This ensures gRPC binds to 0.0.0.0 (IPv4) instead of [::] (IPv6)
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.i("ExoApplication created - IPv4 stack preferred")
    }
}
