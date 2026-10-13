package com.example.controller

import android.content.Context
import java.util.UUID

class PrefsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("DetcuadorPrefs", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString("tablet_device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("tablet_device_id", it).apply()
        }

    // Token de la sesion de turno del insumista/supervisor (v1.7.0). Es lo que permite
    // al servidor saber QUIEN pregunta en vez de creerle al rol que manda la URL.
    // Vive en prefs y no solo en memoria para sobrevivir a que Android mate el proceso:
    // la tablet esta horas en segundo plano y perderlo obligaria a volver a entrar.
    var userToken: String?
        get() = prefs.getString("user_token", null)
        set(v) { prefs.edit().putString("user_token", v).apply() }

    /** Token del supervisor. Ver la nota de `RetrofitClient.tokenAdmin`. */
    var adminToken: String?
        get() = prefs.getString("admin_token", null)
        set(v) { prefs.edit().putString("admin_token", v).apply() }

    /**
     * Borra SOLO lo que identifica al usuario que cierra sesion.
     *
     * Antes el logout hacia `prefs.edit().clear()` sobre todo DetcuadorPrefs, y de paso
     * se llevaba por delante cosas que no son del usuario sino de la TABLET:
     *
     *  - `tablet_device_id`: se regeneraba un UUID nuevo en el siguiente login, asi que
     *    la tablet cambiaba de identidad en cada relevo. Con el login unico de la
     *    v1.7.0 eso importa: volver a entrar en la misma tablet dejaria de reconocerse
     *    como relevo, y `estado_tablets` acumularia una fila por cada cierre de sesion.
     *  - `tablet_nombre` y `tablet_maquina`: lo que se configura a mano en Ajustes.
     *    Un insumista cerrando sesion dejaba la tablet sin configurar.
     *  - los avisos ya aceptados (bateria, pantalla completa), que volvian a salir.
     */
    fun limpiarSesionUsuario() {
        prefs.edit()
            .remove("usuario_id")
            .remove("usuario_nombre")
            .remove("usuario_rol")
            .remove("tipo_insumo_usuario")
            .remove("tiempo_login")
            .remove("user_token")
            .remove("admin_token")
            .apply()
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
