package com.example.controller.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.*

class ConnectivityObserver(
    context: Context,
    // Se invoca cuando vuelve la conexión, además de vaciar la cola. Lo usa
    // MainActivity para re-descargar catálogos/jerarquía (el "scanneo": revalidar
    // contra MySQL que la jerarquía cacheada siga vigente). Corre en hilo de binder.
    private val onReconnect: (() -> Unit)? = null
) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wm = WorkManager.getInstance(context)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val work = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniqueWork("flush_cola", ExistingWorkPolicy.KEEP, work)
            onReconnect?.invoke()
        }
    }

    fun register() {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(req, callback)
    }

    fun unregister() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}
