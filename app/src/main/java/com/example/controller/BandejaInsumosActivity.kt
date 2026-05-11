package com.example.controller

import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import android.media.AudioManager
import android.content.pm.PackageManager
import com.google.firebase.messaging.FirebaseMessaging
import android.util.Log
import androidx.appcompat.app.AlertDialog
import android.os.Vibrator
import android.os.VibrationEffect

class BandejaInsumosActivity : AppCompatActivity() {

    private lateinit var rvAsignados: RecyclerView
    private lateinit var tvSinAsignados: TextView
    private lateinit var adapterAsignados: PedidoAdapter

    private var mediaPlayer: android.media.MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var cantidadPedidosAnterior = 0
    private lateinit var rvPedidos: RecyclerView
    private lateinit var tvSinPedidos: TextView
    private lateinit var adapterPedidos: PedidoAdapter

    private lateinit var tvUsuarioLogueado: TextView
    private lateinit var btnCerrarSesion: Button

    private var usuarioId: Int = -1
    private var usuarioRol: String = ""
    private var tipoInsumoUsuario: String = ""
    private var gestorWebSocket: SigepWebSocketManager? = null

    // Código de solicitud para el diálogo de permisos de notificación en Android 13+.
    companion object {
        private const val CODIGO_SOLICITUD_PERMISO = 101
    }

    // Receptor de broadcasts enviados por SigepFirebaseMessagingService cuando la app está abierta.
    // Se registra sólo mientras la Activity es visible para no consumir recursos en background.
    private val receptorPedidos = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.sigep.NUEVO_PEDIDO_INSUMO") {
                val tipoPedido = intent.getStringExtra("tipo_insumo") ?: ""
                if (tipoPedido == tipoInsumoUsuario || tipoInsumoUsuario == "ADMIN") {
                    Log.d("SIGEP_BANDEJA", "Nuevo pedido recibido vía broadcast — recargando lista")
                    actualizarListasManualmente()
                }
            }
        }
    }

    // 8 horas: límite de sesión para insumistas, alineado con el turno máximo de trabajo.
    private val OCHO_HORAS_MILLIS = 8 * 60 * 60 * 1000L

    override fun onStart() {
        super.onStart()
        verificarPermisosNotificacion()
        recuperarTokenFCM()
        verificarOptimizacionBateria()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bandeja_insumos)

        rvAsignados      = findViewById(R.id.rvAsignados)
        tvSinAsignados   = findViewById(R.id.tvSinAsignados)
        rvPedidos        = findViewById(R.id.rvPedidos)
        tvSinPedidos     = findViewById(R.id.tvSinPedidos)
        tvUsuarioLogueado = findViewById(R.id.tvUsuarioLogueado)
        btnCerrarSesion  = findViewById(R.id.btnCerrarSesion)

        val prefs = getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE)
        usuarioId         = prefs.getInt("usuario_id", -1)
        val nombre        = prefs.getString("usuario_nombre", "Desconocido")
        usuarioRol        = prefs.getString("usuario_rol", "").toString()
        tipoInsumoUsuario = prefs.getString("tipo_insumo_usuario", "EMPAQUE").toString()
        val tiempoLogin   = prefs.getLong("tiempo_login", 0L)

        AppState.usuarioRol = usuarioRol

        if (System.currentTimeMillis() - tiempoLogin > OCHO_HORAS_MILLIS) {
            cerrarSesion("Tu sesión ha expirado por seguridad (8 horas).")
            return
        }

        tvUsuarioLogueado.text = "Insumista: $nombre | $usuarioRol"

        if (usuarioId == -1) {
            cerrarSesion("Error de sesión")
            return
        }

        btnCerrarSesion.setOnClickListener { cerrarSesion("Sesión cerrada correctamente") }

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Adaptador de entregas propias (zona superior), acción: confirmar entrega
        rvAsignados.layoutManager = LinearLayoutManager(this)
        adapterAsignados = PedidoAdapter(emptyList(), esAsignado = true) { pedido ->
            entregarPedido(pedido.id)
        }
        rvAsignados.adapter = adapterAsignados
        rvAsignados.isNestedScrollingEnabled = false

        // Adaptador de nuevos pedidos (zona inferior), acción: aceptar pedido
        rvPedidos.layoutManager = LinearLayoutManager(this)
        adapterPedidos = PedidoAdapter(emptyList(), esAsignado = false) { pedido ->
            aceptarPedido(pedido.id)
        }
        rvPedidos.adapter = adapterPedidos
        rvPedidos.isNestedScrollingEnabled = false

        actualizarListasManualmente()
        conectarWebSocket()
    }

    override fun onResume() {
        super.onResume()
        AppState.moduloActivo = AppState.MODULO_GESTION_INSUMOS
        val filtro = IntentFilter("com.sigep.NUEVO_PEDIDO_INSUMO")
        ContextCompat.registerReceiver(this, receptorPedidos, filtro, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(receptorPedidos) } catch (e: Exception) { /* ya desregistrado */ }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) AppState.moduloActivo = null
    }

    private fun actualizarListasManualmente() {
        lifecycleScope.launch(Dispatchers.IO) { consultarServidor() }
    }

    private fun conectarWebSocket() {
        val tipoCanal = if (usuarioRol.contains("Granel", ignoreCase = true)) "GRANEL" else "EMPAQUE"
        val urlWs = RetrofitClient.BASE_URL
            .removeSuffix("/")
            .replace("http://", "ws://")
            .replace("https://", "wss://") + "/api/ws/insumos/$tipoCanal"

        Log.d("SIGEP_WS", "Conectando al canal: $urlWs")

        gestorWebSocket = SigepWebSocketManager(urlWs) { evento, id, _ ->
            when (evento) {
                "nuevo_pedido" -> {
                    actualizarListasManualmente()
                    reproducirAlarma()
                    if (id != null) mostrarNotificacionAlerta(1)
                }
                "pedido_aceptado" -> {
                    // Alguien más tomó el pedido: apagamos la alarma para evitar
                    // que un insumista siga corriendo hacia un pedido ya atendido.
                    detenerAlarma()
                    actualizarListasManualmente()
                }
            }
        }
        gestorWebSocket?.connect()
    }

    private fun verificarPermisosNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), CODIGO_SOLICITUD_PERMISO)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CODIGO_SOLICITUD_PERMISO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("SIGEP_NOTIF", "Permiso de notificaciones concedido")
            } else {
                Log.w("SIGEP_NOTIF", "Permiso de notificaciones denegado — las alertas FCM no mostrarán heads-up")
            }
        }
    }

    private fun recuperarTokenFCM() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { tarea ->
                if (!tarea.isSuccessful) {
                    Log.e("FCM_SIGEP", "Error al recuperar el token FCM", tarea.exception)
                    return@addOnCompleteListener
                }
                val token = tarea.result
                getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE)
                    .edit().putString("fcm_token", token).apply()
                guardarTokenEnServidor(token)
            }
        } catch (e: Exception) {
            Log.e("FCM_SIGEP", "Error crítico al inicializar Firebase: ${e.message}. Verifique google-services.json")
        }
    }

    private fun guardarTokenEnServidor(token: String) {
        // usuario_id permite al servidor asociar este dispositivo al usuario que tiene sesión,
        // habilitando notificaciones dirigidas por usuario en lugar de broadcast por rol.
        val idParaEnviar = if (usuarioId == -1) null else usuarioId

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val respuesta = RetrofitClient.apiService.registrarTokenFcm(
                    SolicitudTokenFcm(token = token, usuarioId = idParaEnviar)
                )
                when (respuesta.code()) {
                    201  -> Log.i("FCM_SIGEP", "Token registrado en servidor (id=${respuesta.body()?.id})")
                    200  -> Log.i("FCM_SIGEP", "Token actualizado en servidor (id=${respuesta.body()?.id})")
                    else -> Log.e("FCM_SIGEP", "Error al registrar token: HTTP ${respuesta.code()}")
                }
            } catch (e: Exception) {
                Log.e("FCM_SIGEP", "Fallo de red al registrar token: ${e.message}")
            }
        }
    }

    private fun verificarOptimizacionBateria() {
        val prefs = getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("battery_dialog_shown", false)) return

        val fabricante = Build.MANUFACTURER.uppercase()
        val fabricantesProblematicos = listOf("XIAOMI", "HUAWEI", "SAMSUNG")

        // Estos fabricantes matan agresivamente los servicios en background.
        // Sin esta configuración manual el insumista no recibe las alertas de insumos.
        if (fabricantesProblematicos.any { fabricante.contains(it) }) {
            AlertDialog.Builder(this)
                .setTitle("Configuración Requerida")
                .setMessage("Para recibir alertas en tiempo real, desactiva la optimización de batería para SIGEP y permite el inicio automático (Autostart) en los ajustes de tu dispositivo $fabricante.")
                .setPositiveButton("Entendido") { _, _ ->
                    prefs.edit().putBoolean("battery_dialog_shown", true).apply()
                }
                .setCancelable(false)
                .show()
        }
    }

    private suspend fun consultarServidor() {
        try {
            val respPendientes = RetrofitClient.apiService.obtenerPedidosPendientes(usuarioRol)
            val respAsignados  = RetrofitClient.apiService.obtenerPedidosAsignados(usuarioId)

            withContext(Dispatchers.Main) {
                if (respPendientes.isSuccessful && respPendientes.body() != null) {
                    val listaPend = respPendientes.body()!!
                    cantidadPedidosAnterior = listaPend.size

                    if (listaPend.isEmpty()) {
                        rvPedidos.visibility  = View.GONE
                        tvSinPedidos.visibility = View.VISIBLE
                        detenerAlarma()
                    } else {
                        rvPedidos.visibility  = View.VISIBLE
                        tvSinPedidos.visibility = View.GONE
                        adapterPedidos.actualizarLista(listaPend)
                    }
                }

                if (respAsignados.isSuccessful && respAsignados.body() != null) {
                    val listaAsig = respAsignados.body()!!
                    adapterAsignados.actualizarLista(listaAsig)
                    rvAsignados.visibility  = if (listaAsig.isEmpty()) View.GONE else View.VISIBLE
                    tvSinAsignados.visibility = if (listaAsig.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@BandejaInsumosActivity, "Error al actualizar datos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun aceptarPedido(pedidoId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val solicitud = PedidoAccionRequest(pedidoId, usuarioId)
                val respuesta = RetrofitClient.apiService.aceptarPedido(solicitud)
                withContext(Dispatchers.Main) {
                    if (respuesta.isSuccessful) {
                        Toast.makeText(this@BandejaInsumosActivity, "Pedido asignado", Toast.LENGTH_SHORT).show()
                        actualizarListasManualmente()
                        detenerAlarma()
                    } else {
                        Toast.makeText(this@BandejaInsumosActivity, "Alguien más ya tomó este pedido", Toast.LENGTH_LONG).show()
                        actualizarListasManualmente()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BandejaInsumosActivity, "Error de red al aceptar pedido", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun entregarPedido(pedidoId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val solicitud = PedidoAccionRequest(pedidoId, usuarioId)
                val respuesta = RetrofitClient.apiService.entregarPedido(solicitud)
                withContext(Dispatchers.Main) {
                    if (respuesta.isSuccessful) {
                        Toast.makeText(this@BandejaInsumosActivity, "Entrega confirmada", Toast.LENGTH_SHORT).show()
                        actualizarListasManualmente()
                    } else {
                        Toast.makeText(this@BandejaInsumosActivity, "Error al confirmar entrega", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BandejaInsumosActivity, "Error de red al confirmar entrega", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun cerrarSesion(mensaje: String) {
        AppState.usuarioRol = null
        AppState.moduloActivo = null
        detenerAlarma()
        mediaPlayer?.release()
        mediaPlayer = null
        getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE).edit().clear().apply()
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MenuRolesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        gestorWebSocket?.disconnect()
    }

    private fun reproducirAlarma() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0
            )
            if (mediaPlayer == null) {
                mediaPlayer = android.media.MediaPlayer.create(this, R.raw.alarm).apply {
                    isLooping = true
                }
            }
            if (mediaPlayer?.isPlaying == false) mediaPlayer?.start()

            val patron = longArrayOf(0, 500, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(patron, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(patron, 0)
            }
        } catch (e: Exception) {
            Log.e("SIGEP_ALARM", "Error al reproducir alarma: ${e.message}")
        }
    }

    private fun detenerAlarma() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            mediaPlayer?.seekTo(0)
        }
        vibrator?.cancel()
    }

    private fun mostrarNotificacionAlerta(cantidad: Int) {
        val gestorNotif = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val idCanal = "canal_insumos_urgente"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(idCanal, "Alertas de Pedidos", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Notificaciones de la planta" }
            gestorNotif.createNotificationChannel(canal)
        }

        val intent = Intent(this, BandejaInsumosActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificacion = NotificationCompat.Builder(this, idCanal)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("NUEVO PEDIDO EN PLANTA")
            .setContentText("Tienes $cantidad solicitud(es) esperando atención.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        gestorNotif.notify(999, notificacion)
    }
}
