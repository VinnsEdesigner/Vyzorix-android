package com.vyzorix.agent

import android.app.Application
import android.util.Log
import com.vyzorix.connector.CloudConnectionFactory
import com.vyzorix.service.CloudLinkProvider

/**
 * Installs the cloud connection before anything else runs.
 *
 * The foreground service is started by the system from an `Intent`, so there is no
 * place to inject dependencies into it; `Application.onCreate` always runs first,
 * so the connection is built here and parked where the service can pick it up.
 *
 * The connection is built by `CloudConnectionFactory`, so this module never names
 * OkHttp or the format library — the app depends on the connector, not the other
 * way round.
 *
 * If the build carries no cloud configuration the connection is simply not
 * installed, and the device runs over the bridge alone — a valid state, not an
 * error.
 */
class VyzorixApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val connection =
            try {
                CloudConnectionFactory.create(
                    context = this,
                    baseUrl = BuildConfig.CLOUD_BASE_URL,
                    serverPublicKeyPem = BuildConfig.SERVER_PUBLIC_KEY,
                    appVersion = BuildConfig.VERSION_NAME,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "cloud wiring failed; running bridge-only", t)

                null
            }

        CloudLinkProvider.install(connection)

        Log.i(TAG, "cloud connection installed=${connection != null}")
    }

    private companion object {
        const val TAG = "VyzorixApplication"
    }
}
