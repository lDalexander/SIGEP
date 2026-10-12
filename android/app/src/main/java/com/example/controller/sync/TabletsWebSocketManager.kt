package com.example.controller.sync

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TabletsWebSocketManager(
    private val deviceId: String,
    private val workManager: WorkManager,
    // Callback al recibir un mensaje admin (CAMBIO 4). Se invoca en el hilo principal.
    private val onMensajeAdmin: (id: Int, texto: String) -> Unit = { _, _ -> }
) {
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isManualClose = false
    private var retryDelay = 1000L
    private val maxRetryDelay = 30_000L

    private val url = "ws://150.36.200.252:8000/api/tablets/ws/tablets/$deviceId"

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("TABLETS_WS", "Conectado: $url")
            retryDelay = 1000L
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("TABLETS_WS", "Mensaje: $text")
            try {
                val json = JSONObject(text)
                when (json.optString("tipo")) {
                    "sync_now" -> enqueueSync()
                    "mensaje_admin" -> {
                        val id = json.optInt("id", -1)
                        val texto = json.optString("texto", "")
                        if (texto.isNotBlank()) handler.post { onMensajeAdmin(id, texto) }
                    }
                }
            } catch (e: Exception) {
                com.example.controller.ErrorReporter.report("TABLETS_WS", "Error al parsear mensaje WS", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("TABLETS_WS", "Cerrando ($code): $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isManualClose) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isManualClose) {
                Log.e("TABLETS_WS", "Fallo: ${t.message}")
                scheduleReconnect()
            }
        }
    }

    fun connect() {
        isManualClose = false
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
        webSocket = client?.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun disconnect() {
        isManualClose = true
        webSocket?.close(1000, "App background")
        client?.dispatcher?.executorService?.shutdown()
        handler.removeCallbacksAndMessages(null)
    }

    private fun scheduleReconnect() {
        handler.postDelayed({
            Log.d("TABLETS_WS", "Reconectando en ${retryDelay / 1000}s...")
            connect()
            retryDelay = (retryDelay * 2).coerceAtMost(maxRetryDelay)
        }, retryDelay)
    }

    private fun enqueueSync() {
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork("flush_cola", ExistingWorkPolicy.KEEP, work)
    }
}
