package com.example.controller

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SigepFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("SIGEP_FCM", "Nuevo token recibido: $token")
        getSharedPreferences("DetcuadorPrefs", Context.MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
        guardarTokenEnServidor(token)
    }

    private fun guardarTokenEnServidor(token: String) {
        // usuario_id permite al servidor asociar este dispositivo al usuario que tiene sesión,
        // habilitando notificaciones dirigidas por usuario en lugar de broadcast por rol.
        val prefs = getSharedPreferences("DetcuadorPrefs", Context.MODE_PRIVATE)
        val idGuardado = prefs.getInt("usuario_id", -1)
        val usuarioId = if (idGuardado == -1) null else idGuardado

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val respuesta = RetrofitClient.apiService.registrarTokenFcm(
                    SolicitudTokenFcm(token = token, usuarioId = usuarioId)
                )
                when (respuesta.code()) {
                    201  -> Log.i("SIGEP_FCM", "Token registrado en servidor (id=${respuesta.body()?.id})")
                    200  -> Log.i("SIGEP_FCM", "Token actualizado en servidor (id=${respuesta.body()?.id})")
                    else -> Log.e("SIGEP_FCM", "Error al registrar token: HTTP ${respuesta.code()}")
                }
            } catch (e: Exception) {
                ErrorReporter.report("SIGEP_FCM", "Fallo de red al registrar token", e)
            }
        }
    }

    private fun obtenerTipoInsumoActual(): String {
        return getSharedPreferences("DetcuadorPrefs", Context.MODE_PRIVATE)
            .getString("tipo_insumo_usuario", "") ?: ""
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("SIGEP_FCM", "Mensaje recibido: ${remoteMessage.data}")

        if (remoteMessage.data.isEmpty()) return

        val data = remoteMessage.data
        if (data["tipo"] != "SOLICITUD_INSUMO") return

        val tipoInsumoMsg = data["tipo_insumo"] ?: "DESCONOCIDO"
        val tipoInsumoUsuario = obtenerTipoInsumoActual()
        Log.d("SIGEP_FCM", "Rol leído: $tipoInsumoUsuario | Tipo mensaje: $tipoInsumoMsg")

        // Descartamos mensajes que no corresponden al tipo de insumo del usuario
        // para evitar que Empaque reciba alertas de Granel y viceversa.
        if (tipoInsumoMsg != tipoInsumoUsuario && tipoInsumoUsuario != "ADMIN") {
            Log.d("SIGEP_FCM", "Mensaje ignorado — no corresponde al rol (Usuario: $tipoInsumoUsuario)")
            return
        }

        val titulo = data["titulo"] ?: "NUEVO PEDIDO"
        val cuerpo = data["cuerpo"] ?: "Se requiere atención inmediata."
        val solicitudId = data["solicitudId"]

        Log.d("SIGEP_FCM", "Procesando solicitud de insumo ID: $solicitudId, Tipo: $tipoInsumoMsg")

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        // El WakeLock de 10 s garantiza que la pantalla encienda el tiempo suficiente
        // para que el insumista vea la alerta antes de que el dispositivo vuelva a dormir.
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "Sigep:FCM_AlarmWakeLock"
        )
        wakeLock.acquire(10_000L)

        try {
            SigepNotificationManager.dispararAlertaPedido(this, titulo, cuerpo)

            val intentBroadcast = Intent("com.sigep.NUEVO_PEDIDO_INSUMO").apply {
                putExtra("solicitudId", solicitudId)
                putExtra("tipo_insumo", tipoInsumoMsg)
                setPackage(packageName)
            }
            sendBroadcast(intentBroadcast)
        } finally {
            // Liberación garantizada aunque fallen las operaciones anteriores,
            // para no drenar la batería del dispositivo industrial.
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}
