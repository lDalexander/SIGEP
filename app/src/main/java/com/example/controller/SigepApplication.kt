package com.example.controller

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.*
import com.example.controller.data.ColaSyncRepository
import com.example.controller.data.local.AppDatabase
import com.example.controller.sync.HeartbeatService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

class SigepApplication : Application(), LifecycleEventObserver {

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        // Identifica la tablet en Crashlytics ⇒ cuando crashea, sabemos cuál de las 20 es.
        ErrorReporter.setDeviceId(PrefsRepository(this).deviceId)
        // Canal de alarma listo desde el arranque. Antes se creaba solo dentro
        // de onMessageReceived, así que si Android entregaba la notificación
        // directamente (notification payload), salía con el canal default
        // (solo sonido, sin vibración ni full-screen).
        SigepNotificationManager.createAlarmNotificationChannel(applicationContext)
        CoroutineScope(Dispatchers.IO).launch { migrarColaLegacy() }
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> iniciarHeartbeat()
            Lifecycle.Event.ON_STOP  -> detenerHeartbeat()
            else -> Unit
        }
    }

    private fun iniciarHeartbeat() {
        val intent = Intent(this, HeartbeatService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun detenerHeartbeat() {
        stopService(Intent(this, HeartbeatService::class.java))
    }

    // Migración one-shot: mueve la cola legacy de SharedPreferences a Room.
    private suspend fun migrarColaLegacy() {
        val legacyPrefs = getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE)
        val turnoJson   = legacyPrefs.getString("turno_pendiente_crear", null)
        val colaJson    = legacyPrefs.getString("cola_pendientes", null)

        if (turnoJson == null && colaJson == null) return

        val repo = ColaSyncRepository(applicationContext)
        val gson = Gson()

        try {
            if (turnoJson != null) {
                val req = gson.fromJson(turnoJson, IniciarTurnoRequest::class.java)
                repo.enqueueIniciarTurno(req)
                Log.i("SigepApp", "Migrado turno pendiente legacy")
            }

            if (colaJson != null) {
                val tipo = object : TypeToken<List<RegistrarPalletRequest>>() {}.type
                val pallets: List<RegistrarPalletRequest> = gson.fromJson(colaJson, tipo) ?: emptyList()
                pallets.forEach { repo.enqueueRegistrarPallet(it) }
                Log.i("SigepApp", "Migrados ${pallets.size} pallets legacy")
            }

            legacyPrefs.edit()
                .remove("turno_pendiente_crear")
                .remove("cola_pendientes")
                .apply()

        } catch (e: Exception) {
            ErrorReporter.report("SigepApp", "Error en migración legacy", e)
        }
    }
}
