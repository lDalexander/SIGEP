package com.example.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manager Senior para WebSockets usando OkHttp.
 * Implementa reconexión automática con Backoff y manejo de ciclo de vida.
 */
class SigepWebSocketManager(
    private val url: String,
    private val onUpdate: (evento: String, id: Int?, descripcion: String?) -> Unit
) {
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isManualClose = false
    private var retryDelay = 2000L // 2 segundos iniciales
    private val maxRetryDelay = 30000L // 30 segundos máximo

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("SIGEP_WS", "✅ Conectado: $url")
            retryDelay = 2000L // Reset del backoff al conectar
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("SIGEP_WS", "📩 Mensaje: $text")
            try {
                val json = JSONObject(text)
                val evento = json.optString("evento") // 🔥 Extraemos la palabra clave

                // Le enseñamos a escuchar AMBOS eventos
                if (evento == "nuevo_pedido" || evento == "pedido_aceptado") {
                    val id = if (json.has("solicitud_id")) json.getInt("solicitud_id") else null
                    val desc = json.optString("descripcion", "Actualización")

                    // 🔥 Le enviamos el nombre del evento a la BandejaInsumosActivity
                    handler.post { onUpdate(evento, id, desc) }
                }
            } catch (e: Exception) {
                Log.e("SIGEP_WS", "❌ Error JSON: ${e.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("SIGEP_WS", "⚠️ Cerrando ($code): $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isManualClose) {
                Log.e("SIGEP_WS", "🔴 Conexión perdida. Reintentando...")
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isManualClose) {
                Log.e("SIGEP_WS", "❌ Fallo Crítico: ${t.message}")
                scheduleReconnect()
            }
        }
    }

    fun connect() {
        isManualClose = false
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // Mantener viva indefinidamente
            .pingInterval(15, TimeUnit.SECONDS) // 🔥 EL LATIDO: Enviar Ping cada 15 segundos
            .build()

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client?.newWebSocket(request, listener)
    }

    private fun scheduleReconnect() {
        handler.postDelayed({
            Log.d("SIGEP_WS", "🔄 Intentando reconexión en ${retryDelay/1000}s...")
            connect()
            // Exponential Backoff
            retryDelay = (retryDelay * 2).coerceAtMost(maxRetryDelay)
        }, retryDelay)
    }

    fun disconnect() {
        isManualClose = true
        webSocket?.close(1000, "App paused/closed")
        client?.dispatcher?.executorService?.shutdown()
        handler.removeCallbacksAndMessages(null)
    }
}
