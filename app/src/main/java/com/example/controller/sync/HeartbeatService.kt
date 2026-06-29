package com.example.controller.sync

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.controller.*
import com.example.controller.data.local.AppDatabase
import kotlinx.coroutines.*

class HeartbeatService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: PrefsRepository

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsRepository(applicationContext)
        crearCanalNotificacion()
        startForeground(NOTIF_ID, construirNotificacion())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            while (isActive) {
                try {
                    val pendientes = AppDatabase.getInstance(applicationContext)
                        .operacionDao().contarPendientes()
                    val resp = RetrofitClient.apiService.enviarHeartbeat(
                        HeartbeatRequest(
                            deviceId   = prefs.deviceId,
                            nombre     = prefs.tabletNombre,
                            maquina    = prefs.tabletMaquina,
                            pendientes = pendientes,
                            enLinea    = true
                        )
                    )
                    if (resp.isSuccessful && resp.body()?.syncSolicitada == true) {
                        enqueueSync()
                    }
                    // Respaldo de mensajes admin (CAMBIO 4): si el WS no estaba activo,
                    // llegan por aquí. MensajesAdmin deduplica por id para no repetir popup.
                    resp.body()?.mensajes?.forEach { m ->
                        com.example.controller.MensajesAdmin.mostrar(applicationContext, m.id, m.texto)
                    }
                } catch (e: Exception) {
                    com.example.controller.ErrorReporter.report("HeartbeatService", "Heartbeat falló", e)
                }
                delay(30_000)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enqueueSync() {
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork("flush_cola", ExistingWorkPolicy.REPLACE, work)
    }

    private fun crearCanalNotificacion() {
        val channel = NotificationChannel(CANAL_ID, "SIGEP Sync", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun construirNotificacion(): Notification =
        NotificationCompat.Builder(this, CANAL_ID)
            .setContentTitle("SIGEP activo")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val CANAL_ID  = "sigep_sync"
        private const val NOTIF_ID  = 8001
    }
}
