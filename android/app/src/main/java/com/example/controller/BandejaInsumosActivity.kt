package com.example.controller

import android.annotation.SuppressLint
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var btnEntregarInsumo: Button
    private lateinit var btnHistorialEntregas: Button
    private lateinit var btnMensajesBodega: Button

    // Planes del día. El insumista entrega CONTRA un plan cuando el material es para
    // producción planificada y no para una máquina que lo pidió: ese es el caso que no
    // existía hasta la v1.7.0, cuando todo el flujo era reactivo.
    private var planesDeHoy: List<PlanProduccion> = emptyList()

    private var usuarioId: Int = -1
    private var usuarioRol: String = ""
    private var tipoInsumoUsuario: String = ""
    private var gestorWebSocket: SigepWebSocketManager? = null

    private var fotoUri: Uri? = null
    private var fotoFile: File? = null
    private var tvFotoEstado: TextView? = null
    private var listaMaquinas: List<String> = emptyList()

    // Catálogo de la bodega de este insumista. Sustituye a las listas que estaban
    // quemadas en el formulario de entrega (`["Cajas","Botellas","Fajillas",...]`),
    // que no coincidían ni con las del operario ni con la tabla `insumos`.
    private var catalogoInsumos: List<InsumoResponse> = emptyList()
    private lateinit var launcherCamara: ActivityResultLauncher<Uri>
    private lateinit var launcherGaleria: ActivityResultLauncher<String>

    // Código de solicitud para el diálogo de permisos de notificación en Android 13+.
    companion object {
        private const val CODIGO_SOLICITUD_PERMISO = 101
        /** Lo pone la notificación de un mensaje de supervisión: abre los chats al llegar. */
        const val EXTRA_ABRIR_CHAT = "abrir_chat"
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

    // 12 horas: límite de sesión para insumistas, alineado con el turno máximo de trabajo.
    private val LIMITE_SESION_MILLIS = 12 * 60 * 60 * 1000L

    // Salvavidas por si el WebSocket se desconectó: refrescamos la bandeja
    // cada 15s así, aunque no llegue el evento "operario_confirmo_recepcion",
    // el pedido entregado deja de aparecer al cabo de pocos segundos.
    private var jobRefrescoPeriodico: kotlinx.coroutines.Job? = null

    override fun onStart() {
        super.onStart()
        verificarPermisosNotificacion()
        verificarPermisoFullScreenIntent()
        recuperarTokenFCM()
        verificarOptimizacionBateria()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        launcherCamara = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) tvFotoEstado?.text = "📷 Foto capturada"
            else { fotoUri = null; fotoFile = null }
        }
        launcherGaleria = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) { fotoUri = uri; fotoFile = null; tvFotoEstado?.text = "🖼️ Imagen seleccionada" }
        }

        setContentView(R.layout.activity_bandeja_insumos)

        rvAsignados      = findViewById(R.id.rvAsignados)
        tvSinAsignados   = findViewById(R.id.tvSinAsignados)
        rvPedidos        = findViewById(R.id.rvPedidos)
        tvSinPedidos     = findViewById(R.id.tvSinPedidos)
        tvUsuarioLogueado = findViewById(R.id.tvUsuarioLogueado)
        btnCerrarSesion   = findViewById(R.id.btnCerrarSesion)
        btnEntregarInsumo = findViewById(R.id.btnEntregarInsumo)
        btnHistorialEntregas = findViewById(R.id.btnHistorialEntregas)
        btnMensajesBodega = findViewById(R.id.btnMensajesBodega)

        val prefs = getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE)
        usuarioId         = prefs.getInt("usuario_id", -1)
        val nombre        = prefs.getString("usuario_nombre", "Desconocido")
        usuarioRol        = prefs.getString("usuario_rol", "").toString()
        // Derivamos del rol — antes leíamos una pref que nadie escribía, por lo que
        // el filtro de SigepFirebaseMessagingService descartaba todo FCM en background.
        tipoInsumoUsuario = derivarTipoInsumo(usuarioRol)
        prefs.edit().putString("tipo_insumo_usuario", tipoInsumoUsuario).apply()
        val tiempoLogin   = prefs.getLong("tiempo_login", 0L)

        AppState.usuarioRol = usuarioRol

        if (System.currentTimeMillis() - tiempoLogin > LIMITE_SESION_MILLIS) {
            cerrarSesion("Tu sesión ha expirado por seguridad (12 horas).")
            return
        }

        tvUsuarioLogueado.text = "Insumista: $nombre | $usuarioRol"

        if (usuarioId == -1) {
            cerrarSesion("Error de sesión")
            return
        }

        btnCerrarSesion.setOnClickListener { cerrarSesion("Sesión cerrada correctamente") }
        btnEntregarInsumo.setOnClickListener { abrirFormularioEntregaProactiva() }
        btnHistorialEntregas.setOnClickListener { mostrarHistorialEntregas() }
        btnMensajesBodega.setOnClickListener { abrirChats() }
        cargarMaquinas()
        cargarCatalogoInsumos()
        cargarPlanesDeHoy()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Adaptador de entregas propias (zona superior), acción: confirmar entrega
        rvAsignados.layoutManager = LinearLayoutManager(this)
        adapterAsignados = PedidoAdapter(emptyList(), esAsignado = true) { pedido ->
            pedirCantidadYEntregar(pedido)
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
        if (intent?.getBooleanExtra(EXTRA_ABRIR_CHAT, false) == true) abrirChats()
    }

    // La notificación de un mensaje trae aquí con la activity ya abierta (SINGLE_TOP).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ABRIR_CHAT, false)) abrirChats()
    }

    override fun onResume() {
        super.onResume()
        AppState.moduloActivo = AppState.MODULO_GESTION_INSUMOS
        val filtro = IntentFilter("com.sigep.NUEVO_PEDIDO_INSUMO")
        ContextCompat.registerReceiver(this, receptorPedidos, filtro, ContextCompat.RECEIVER_EXPORTED)

        // Salvavidas: refresca la bandeja cada 15s mientras la activity está
        // visible, por si el WS se cayó y no nos llegó el evento de confirmación.
        jobRefrescoPeriodico = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15_000)
                consultarServidor()
                refrescarAvisoChat()
            }
        }
        refrescarAvisoChat()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(receptorPedidos) } catch (e: Exception) { /* ya desregistrado */ }
        jobRefrescoPeriodico?.cancel()
        jobRefrescoPeriodico = null
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
                    SigepNotificationManager.cancelarAlertaPedido(this)
                    actualizarListasManualmente()
                }
                // El operario confirmó la recepción: refrescamos para que el pedido
                // entregado deje de aparecer en "asignados" del insumista.
                "operario_confirmo_recepcion",
                "pedido_confirmado",
                "pedido_entregado" -> {
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
                    Log.e("SIGEP_FCM", "Error al recuperar el token FCM", tarea.exception)
                    return@addOnCompleteListener
                }
                val token = tarea.result
                getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE)
                    .edit().putString("fcm_token", token).apply()
                guardarTokenEnServidor(token)
            }
        } catch (e: Exception) {
            ErrorReporter.report("SIGEP_FCM", "Error crítico al inicializar Firebase (verifique google-services.json)", e)
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
                    201  -> Log.i("SIGEP_FCM", "Token registrado en servidor (id=${respuesta.body()?.id})")
                    200  -> Log.i("SIGEP_FCM", "Token actualizado en servidor (id=${respuesta.body()?.id})")
                    else -> Log.e("SIGEP_FCM", "Error al registrar token: HTTP ${respuesta.code()}")
                }
            } catch (e: Exception) {
                ErrorReporter.report("SIGEP_FCM", "Fallo al registrar token FCM en servidor", e)
            }
        }
    }

    // Deriva el tipo de insumo (GRANEL/EMPAQUE/ADMIN) desde el rol del usuario.
    // Es el filtro que usa SigepFirebaseMessagingService para descartar mensajes
    // que no correspondan a este insumista (granel no recibe alertas de empaque).
    private fun derivarTipoInsumo(rol: String): String = when {
        rol.contains("granel", ignoreCase = true)  -> "GRANEL"
        rol.contains("empaque", ignoreCase = true) -> "EMPAQUE"
        rol.equals("ADMIN", ignoreCase = true) ||
        rol.equals("SUPERVISOR", ignoreCase = true) -> "ADMIN"
        else -> "EMPAQUE"  // fallback histórico
    }

    private fun verificarOptimizacionBateria() {
        val prefs = getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("battery_dialog_shown", false)) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            // Ya está exenta — no molestamos al usuario otra vez.
            prefs.edit().putBoolean("battery_dialog_shown", true).apply()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Configuración requerida")
            .setMessage("Para recibir alertas con la pantalla apagada, SIGEP debe estar exenta de la optimización de batería.")
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                try {
                    @SuppressLint("BatteryLife")
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback: pantalla genérica de optimización de batería.
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Abre Ajustes → Batería → SIGEP → Sin restricción", Toast.LENGTH_LONG).show()
                    }
                }
                prefs.edit().putBoolean("battery_dialog_shown", true).apply()
            }
            .setNegativeButton("Después", null)
            .show()
    }

    // Android 14+ restringe USE_FULL_SCREEN_INTENT: la alarma full-screen
    // (encender pantalla + saltarse lockscreen) requiere permiso especial
    // otorgado manualmente por el usuario. Sin esto, en API 34+ Android
    // convierte la alerta en una heads-up normal y no se enciende la pantalla.
    private fun verificarPermisoFullScreenIntent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        val prefs = getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("fullscreen_dialog_shown", false)) return

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.canUseFullScreenIntent()) return

        AlertDialog.Builder(this)
            .setTitle("Permiso para alarmas")
            .setMessage("Para que el aviso de pedido encienda la pantalla y suene como alarma, otorga el permiso de 'pantalla completa' a SIGEP.")
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(android.net.Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Abre Ajustes → Apps → SIGEP → Pantalla completa", Toast.LENGTH_LONG).show()
                }
                prefs.edit().putBoolean("fullscreen_dialog_shown", true).apply()
            }
            .setNegativeButton("Después", null)
            .show()
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
            ErrorReporter.report("BandejaInsumos", "Fallo al consultar pedidos del servidor", e)
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
                        // El insumista pudo aceptar desde la bandeja sin pasar por
                        // AlertaPedidoActivity (ej. tabla abierta cuando llegó el FCM).
                        // En ese caso la notif del canal sigue sonando — la matamos acá.
                        SigepNotificationManager.cancelarAlertaPedido(this@BandejaInsumosActivity)
                    } else {
                        Toast.makeText(this@BandejaInsumosActivity, "Alguien más ya tomó este pedido", Toast.LENGTH_LONG).show()
                        actualizarListasManualmente()
                        // Otro insumista la tomó: también apagamos la alarma local.
                        SigepNotificationManager.cancelarAlertaPedido(this@BandejaInsumosActivity)
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.report("BandejaInsumos", "Fallo al aceptar pedido", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BandejaInsumosActivity, "Error de red al aceptar pedido", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Pregunta cuánto se entrega realmente (precargado con lo solicitado) y luego entrega.
    // Permite registrar entregas parciales para luego comparar con lo que recibe el operario.
    private fun pedirCantidadYEntregar(pedido: PedidoPendienteResponse) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(pedido.cantidad.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Cantidad entregada")
            .setMessage("Solicitado: ${pedido.cantidad} x ${pedido.detalle}\n\n¿Cuánto entregas realmente?")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Entregar") { _, _ ->
                val cant = input.text.toString().toIntOrNull()
                if (cant == null || cant < 0) {
                    Toast.makeText(this, "Ingrese una cantidad válida", Toast.LENGTH_SHORT).show()
                    actualizarListasManualmente()
                } else {
                    entregarPedido(pedido.id, cant)
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> actualizarListasManualmente() }
            .show()
    }

    private fun entregarPedido(pedidoId: Int, cantidadEntregada: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val solicitud = PedidoAccionRequest(pedidoId, usuarioId, cantidadEntregada)
                val respuesta = RetrofitClient.apiService.entregarPedido(solicitud)
                withContext(Dispatchers.Main) {
                    if (respuesta.isSuccessful) {
                        Toast.makeText(this@BandejaInsumosActivity,
                            "Entregado. Esperando confirmación del operario.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@BandejaInsumosActivity, "Error al confirmar entrega", Toast.LENGTH_LONG).show()
                    }
                    actualizarListasManualmente()
                }
            } catch (e: Exception) {
                ErrorReporter.report("BandejaInsumos", "Fallo al confirmar entrega", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BandejaInsumosActivity, "Error de red al confirmar entrega", Toast.LENGTH_SHORT).show()
                    actualizarListasManualmente()
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

        val prefs = getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE)
        val idUsuario = prefs.getInt("usuario_id", -1)
        val deviceId  = prefs.getString("tablet_device_id", null)

        // Avisarle al server para que libere la sesión-única. Es best-effort:
        // si la red está caída, el local logout procede igual y el server
        // auto-libera tras 9h. No bloqueamos al usuario por un fallo de red.
        if (idUsuario != -1 && !deviceId.isNullOrBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    RetrofitClient.apiService.cerrarSesionUsuario(
                        LogoutRequest(usuario_id = idUsuario, device_id = deviceId)
                    )
                } catch (e: Exception) {
                    ErrorReporter.warn("BandejaInsumos", "Logout server falló (auto-libera en 9h): ${e.message}")
                }
            }
        }

        RetrofitClient.tokenSesion = null
        PrefsRepository(applicationContext).limpiarSesionUsuario()
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
            ErrorReporter.report("SIGEP_ALARM", "Error al reproducir alarma", e)
        }
    }

    private fun detenerAlarma() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            mediaPlayer?.seekTo(0)
        }
        vibrator?.cancel()
    }

    private fun cargarMaquinas() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.apiService.obtenerMaquinas()
                withContext(Dispatchers.Main) {
                    if (resp.isSuccessful && resp.body() != null) {
                        listaMaquinas = resp.body()!!.map { it.nombre }
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.report("BandejaInsumos", "Fallo al cargar lista de máquinas", e)
            }
        }
    }

    /**
     * Descarga el catálogo de la bodega de este insumista y lo cachea.
     *
     * Se cachea por la misma razón que el BOM del operario: el formulario de entrega
     * tiene que poder abrirse sin red. Un insumista que ya dejó el material en la
     * máquina no puede quedarse sin poder registrarlo porque el wifi falle.
     */
    private fun cargarCatalogoInsumos() {
        val prefs = getSharedPreferences("SIGEP_CATALOGOS", MODE_PRIVATE)
        val tipoLista = object : com.google.gson.reflect.TypeToken<List<InsumoResponse>>() {}.type
        try {
            val json = prefs.getString("cache_catalogo_insumos", null)
            if (!json.isNullOrBlank()) {
                catalogoInsumos = com.google.gson.Gson().fromJson(json, tipoLista)
            }
        } catch (e: Exception) {
            ErrorReporter.warn("Catalogo", "Caché de catálogo ilegible: ${e.message}")
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.apiService.obtenerCatalogoInsumos(tipoInsumoUsuario)
                val lista = resp.body()
                if (resp.isSuccessful && lista != null && lista.isNotEmpty()) {
                    withContext(Dispatchers.Main) { catalogoInsumos = lista }
                    prefs.edit().putString("cache_catalogo_insumos", com.google.gson.Gson().toJson(lista)).apply()
                }
            } catch (e: Exception) {
                ErrorReporter.warn("Catalogo", "No se pudo actualizar el catálogo — se usa la caché: ${e.message}")
            }
        }
    }

    /**
     * Lo que este insumista ha entregado (últimas 20).
     *
     * El endpoint existía desde antes y la app nunca lo llamaba, así que el insumista
     * no tenía forma de comprobar si una entrega se había registrado: la registraba y
     * se quedaba sin saber. Con la cola de trabajo de un turno completo, eso lleva a
     * registrar dos veces lo mismo por si acaso.
     */
    private fun mostrarHistorialEntregas() {
        val dialogo = AlertDialog.Builder(this)
            .setTitle("Mis entregas")
            .setMessage("Consultando…")
            .setPositiveButton("Cerrar", null)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            val texto = try {
                val resp = RetrofitClient.apiService.obtenerEntregasProactivas(usuarioId)
                val lista = resp.body()
                when {
                    !resp.isSuccessful -> "No se pudo consultar (HTTP ${resp.code()})."
                    lista.isNullOrEmpty() -> "Todavía no has registrado ninguna entrega."
                    else -> lista.joinToString("\n\n") { e ->
                        buildString {
                            append("• ${e.cantidad} x ${e.insumo}\n")
                            append("   ${e.maquina} · ${e.fecha_hora}")
                            if (e.foto_path.isNullOrBlank()) append("\n   ⚠️ sin foto")
                            if (!e.observaciones.isNullOrBlank()) append("\n   «${e.observaciones}»")
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.warn("Historial", "Fallo al consultar entregas: ${e.message}")
                "Sin conexión. Intenta de nuevo cuando haya red."
            }
            withContext(Dispatchers.Main) { dialogo.setMessage(texto) }
        }
    }

    /** Planes del día, para poder entregar contra uno en vez de contra una máquina. */
    private fun cargarPlanesDeHoy() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val hoy = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val resp = RetrofitClient.apiService.obtenerPlanes(hoy, hoy)
                val lista = resp.body()?.planes
                if (resp.isSuccessful && lista != null) {
                    // Los cancelados no se ofrecen: dejar material para algo que no se
                    // va a producir es justo lo que esto viene a evitar.
                    val vivos = lista.filter { !"Cancelado".equals(it.estado, ignoreCase = true) }
                    withContext(Dispatchers.Main) { planesDeHoy = vivos }
                }
            } catch (e: Exception) {
                ErrorReporter.warn("Planes", "No se pudieron cargar los planes: ${e.message}")
            }
        }
    }

    /**
     * Chats con supervisión, uno por supervisor que te escribe, como en WhatsApp.
     *
     * Si un solo chat tiene mensajes nuevos se abre directamente: quien pulsa el botón
     * después de un aviso quiere leer ESE mensaje, no elegirlo de una lista.
     */
    private fun abrirChats() {
        ChatBodega.abrirLista(this, esSupervisor = false, abrirNoLeidoUnico = true) {
            refrescarAvisoChat()
        }
    }

    /** El botón cuenta los mensajes sin leer: «💬 MENSAJES DE SUPERVISIÓN (2)». */
    private fun refrescarAvisoChat() {
        lifecycleScope.launch {
            val sinLeer = withContext(Dispatchers.IO) {
                try {
                    RetrofitClient.apiService.resumenChatBodega().body()?.sin_leer
                } catch (e: Exception) { null }
            } ?: return@launch
            btnMensajesBodega.text =
                if (sinLeer > 0) "💬 MENSAJES DE SUPERVISIÓN ($sinLeer)" else "💬 MENSAJES DE SUPERVISIÓN"
        }
    }

    private fun crearArchivoTemporal(): File {
        val nombre = "SIGEP_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(nombre, ".jpg", dir)
    }

    private fun abrirFormularioEntregaProactiva() {
        fotoUri = null
        fotoFile = null
        val esGranel = usuarioRol.contains("Granel", ignoreCase = true)

        val scroll = ScrollView(this)
        val cont = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }
        scroll.addView(cont)

        // Tipo de producto (solo Empaque)
        var spinnerTipo: Spinner? = null
        if (!esGranel) {
            cont.addView(TextView(this).apply { text = "Tipo de producto:"; setTypeface(null, android.graphics.Typeface.BOLD) })
            spinnerTipo = Spinner(this).also { cont.addView(it) }
            spinnerTipo.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Sólido", "Líquido"))
        }

        // Insumo — del catálogo de la bodega, no de una lista quemada.
        cont.addView(TextView(this).apply { text = "Insumo:"; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 20, 0, 0) })
        val spinnerInsumo = Spinner(this).also { cont.addView(it) }

        // Lista mínima de emergencia: solo si la tablet nunca pudo descargar el
        // catálogo. Sin esto el formulario quedaría vacío y el insumista no podría
        // registrar el material que ya dejó en la máquina.
        val deEmergencia = catalogoInsumos.isEmpty()
        if (deEmergencia) {
            cont.addView(TextView(this).apply {
                text = "Sin catálogo descargado — lista básica"
                setTextColor(android.graphics.Color.parseColor("#B26A00"))
                textSize = 12f
            })
        }
        val etiquetasInsumo = if (deEmergencia) {
            if (esGranel) listOf("Materia Prima", "Preten")
            else listOf("Cajas", "Botellas", "Fajillas", "Etiquetas", "Tapas")
        } else {
            catalogoInsumos.map { if (it.unidad.isBlank()) it.nombre else "${it.nombre}  (${it.unidad})" }
        }
        spinnerInsumo.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, etiquetasInsumo)

        // Máquina
        cont.addView(TextView(this).apply { text = "Máquina destino:"; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 20, 0, 0) })
        val spinnerMaquina = Spinner(this).also { cont.addView(it) }
        spinnerMaquina.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listaMaquinas.ifEmpty { listOf("(Sin máquinas cargadas)") })

        // Plan de producción (opcional). Es lo que hace que la entrega cuente para la
        // cobertura del plan, y lo que permite dejar material para producción que
        // todavía no tiene máquina asignada.
        var spinnerPlan: Spinner? = null
        if (planesDeHoy.isNotEmpty()) {
            cont.addView(TextView(this).apply {
                text = "¿Para un plan de hoy? (opcional)"
                setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 20, 0, 0)
            })
            spinnerPlan = Spinner(this).also { cont.addView(it) }
            val etiquetas = mutableListOf("(ninguno — entrega suelta)")
            etiquetas += planesDeHoy.map { p ->
                val que = listOfNotNull(p.producto, p.marca, p.presentacion)
                    .filter { it.isNotBlank() }.joinToString(" ")
                val donde = p.maquina ?: "sin máquina"
                val falta = if (p.insumos_incompletos > 0) "  · faltan ${p.insumos_incompletos}" else "  · completo"
                "$que — $donde$falta"
            }
            spinnerPlan.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, etiquetas)
        }

        // Cantidad
        cont.addView(TextView(this).apply { text = "Cantidad:"; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 20, 0, 0) })
        val etCantidad = EditText(this).apply { hint = "Ej: 3"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        cont.addView(etCantidad)

        // Observaciones
        cont.addView(TextView(this).apply { text = "Observaciones (opcional):"; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 20, 0, 0) })
        val etObs = EditText(this).apply { hint = "Nota adicional..." }
        cont.addView(etObs)

        // Foto
        cont.addView(TextView(this).apply { text = "Evidencia fotográfica:"; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 20, 0, 0) })
        val btnCam = Button(this).apply {
            text = "📷 TOMAR FOTO"
            setBackgroundColor(android.graphics.Color.parseColor("#455A64"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        cont.addView(btnCam)

        val tvFoto = TextView(this).apply { text = "Sin foto adjunta"; setTextColor(android.graphics.Color.GRAY); setPadding(0, 8, 0, 0) }
        tvFotoEstado = tvFoto
        cont.addView(tvFoto)

        btnCam.setOnClickListener {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 202)
                return@setOnClickListener
            }
            val tmp = crearArchivoTemporal()
            fotoFile = tmp
            fotoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmp)
            launcherCamara.launch(fotoUri!!)
        }

        AlertDialog.Builder(this)
            .setTitle("Registrar Entrega de Insumo")
            .setView(scroll)
            .setPositiveButton("REGISTRAR") { _, _ ->
                val cantidad = etCantidad.text.toString().toIntOrNull()
                if (cantidad == null || cantidad <= 0) {
                    Toast.makeText(this, "Ingrese una cantidad válida", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val maq = spinnerMaquina.selectedItem?.toString() ?: ""
                if (maq.isEmpty() || maq.startsWith("(")) {
                    Toast.makeText(this, "Seleccione una máquina", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (fotoUri == null) {
                    Toast.makeText(this, "La foto de evidencia es obligatoria", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val tipo  = spinnerTipo?.selectedItem?.toString() ?: "Líquido"
                val pos   = spinnerInsumo.selectedItemPosition
                val delCatalogo = if (deEmergencia) null else catalogoInsumos.getOrNull(pos)
                // El servidor reescribe el nombre desde el catálogo cuando va `insumo_id`;
                // se manda igual porque es lo que se lee en el dashboard y en el Excel.
                val ins   = delCatalogo?.nombre ?: spinnerInsumo.selectedItem?.toString() ?: ""
                val obs   = etObs.text.toString().trim().ifEmpty { null }
                // La posición 0 es «ninguno», así que el índice del plan es pos - 1.
                val posPlan = spinnerPlan?.selectedItemPosition ?: 0
                val plan = if (posPlan > 0) planesDeHoy.getOrNull(posPlan - 1) else null
                enviarEntregaProactiva(tipo, ins, cantidad, maq, obs, delCatalogo?.id, plan?.id)
            }
            .setNegativeButton("Cancelar", null)
            .setOnDismissListener { tvFotoEstado = null }
            .show()
    }

    private fun enviarEntregaProactiva(
        tipo: String, insumo: String, cantidad: Int, maquina: String, obs: String?,
        insumoId: Int? = null, planId: Int? = null
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fotoPart: MultipartBody.Part? = fotoUri?.let { uri ->
                    val stream = contentResolver.openInputStream(uri) ?: return@let null
                    val bytes = stream.readBytes(); stream.close()
                    val body = bytes.toRequestBody("image/jpeg".toMediaType())
                    MultipartBody.Part.createFormData("foto", "entrega_${usuarioId}.jpg", body)
                }
                // request_id generado aquí y no en el servidor: la entrega lleva foto,
                // así que un reintento por red lenta subiría el archivo dos veces y
                // dejaría dos registros de la misma entrega.
                val idPeticion = java.util.UUID.randomUUID().toString()
                val resp = RetrofitClient.apiService.registrarEntregaProactiva(
                    insumistaId   = usuarioId.toString().toRequestBody("text/plain".toMediaType()),
                    tipoProducto  = tipo.toRequestBody("text/plain".toMediaType()),
                    insumo        = insumo.toRequestBody("text/plain".toMediaType()),
                    cantidad      = cantidad.toString().toRequestBody("text/plain".toMediaType()),
                    maquina       = maquina.toRequestBody("text/plain".toMediaType()),
                    observaciones = obs?.toRequestBody("text/plain".toMediaType()),
                    insumoId      = insumoId?.toString()?.toRequestBody("text/plain".toMediaType()),
                    requestId     = idPeticion.toRequestBody("text/plain".toMediaType()),
                    planId        = planId?.toString()?.toRequestBody("text/plain".toMediaType()),
                    foto          = fotoPart
                )
                withContext(Dispatchers.Main) {
                    if (resp.isSuccessful) {
                        Toast.makeText(this@BandejaInsumosActivity,
                            resp.body()?.mensaje ?: "Entrega registrada", Toast.LENGTH_LONG).show()
                        fotoUri = null; fotoFile = null
                        // La cobertura del plan acaba de cambiar: se recarga para que el
                        // siguiente formulario muestre cuánto falta de verdad.
                        if (planId != null) cargarPlanesDeHoy()
                    } else {
                        Toast.makeText(this@BandejaInsumosActivity, "Error al registrar entrega", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.report("BandejaInsumos", "Fallo al registrar entrega proactiva", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BandejaInsumosActivity, "Error de red", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
