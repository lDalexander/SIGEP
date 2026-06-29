package com.example.controller

import android.content.Context
import java.util.UUID

class PrefsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("DetcuadorPrefs", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString("tablet_device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("tablet_device_id", it).apply()
        }

    var tabletNombre: String?
        get() = prefs.getString("tablet_nombre", null)
        set(v) { prefs.edit().putString("tablet_nombre", v).apply() }

    var tabletMaquina: String?
        get() = prefs.getString("tablet_maquina", null)
        set(v) { prefs.edit().putString("tablet_maquina", v).apply() }

    // --- Turno en curso (lo escribe MainActivity al iniciar; se limpia al finalizar) ---
    // Permite que el heartbeat reporte la máquina REAL del turno activo, para que el
    // admin pueda dirigirle mensajes/alertas sin depender de configurar Ajustes (v1.3.1).
    val tieneTurnoActivo: Boolean
        get() = prefs.getInt("sesion_activa", -1) != -1

    val turnoMaquina: String?
        get() = prefs.getString("turno_maquina", null)

    val turnoOperador: String?
        get() = prefs.getString("turno_operador", null)

    // Máquina a reportar en el heartbeat: la del turno activo si lo hay; si no, la de
    // Ajustes. Así estado_tablets.maquina coincide con sesiones_trabajo.maquina.
    val maquinaReporte: String?
        get() = if (tieneTurnoActivo) (turnoMaquina ?: tabletMaquina) else tabletMaquina

    val nombreReporte: String?
        get() = tabletNombre ?: if (tieneTurnoActivo) turnoOperador else null
}
