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

    private var usuarioId: Int = -1
    private var usuarioRol: String = ""
    private var tipoInsumoUsuario: String = ""
    private var gestorWebSocket: SigepWebSocketManager? = null

    private var fotoUri: Uri? = null
    private var fotoFile: File? = null
    private var tvFotoEstado: TextView? = null
    private var listaMaquinas: List<String> = emptyList()
    private lateinit var launcherCamara: ActivityResultLauncher<Uri>
    private lateinit var launcherGaleria: ActivityResultLauncher<String>

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
        btnEntregarInsumo.setOnClickListener { abrirFormularioEntregaProactiva() }
        cargarMaquinas()

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
            }
        }
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

        prefs.edit().clear().apply()
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

        // Insumo
        cont.addView(TextView(this).apply { text = "Insumo:"; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 20, 0, 0) })
        val spinnerInsumo = Spinner(this).also { cont.addView(it) }

        fun actualizarInsumos(tipo: String) {
            val opciones = when {
                esGranel        -> listOf("Materia Prima", "Preten")
                tipo == "Sólido" -> listOf("Lámina")
                else            -> listOf("Cajas", "Botellas", "Fajillas", "Etiquetas", "Tapas")
            }
            spinnerInsumo.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opciones)
        }
        actualizarInsumos(if (esGranel) "Líquido" else "Sólido")

        spinnerTipo?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                actualizarInsumos(spinnerTipo.selectedItem.toString())
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Máquina
        cont.addView(TextView(this).apply { text = "Máquina destino:"; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 20, 0, 0) })
        val spinnerMaquina = Spinner(this).also { cont.addView(it) }
        spinnerMaquina.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listaMaquinas.ifEmpty { listOf("(Sin máquinas cargadas)") })

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
                val ins   = spinnerInsumo.selectedItem?.toString() ?: ""
                val obs   = etObs.text.toString().trim().ifEmpty { null }
                enviarEntregaProactiva(tipo, ins, cantidad, maq, obs)
            }
            .setNegativeButton("Cancelar", null)
            .setOnDismissListener { tvFotoEstado = null }
            .show()
    }

    private fun enviarEntregaProactiva(tipo: String, insumo: String, cantidad: Int, maquina: String, obs: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fotoPart: MultipartBody.Part? = fotoUri?.let { uri ->
                    val stream = contentResolver.openInputStream(uri) ?: return@let null
                    val bytes = stream.readBytes(); stream.close()
                    val body = bytes.toRequestBody("image/jpeg".toMediaType())
                    MultipartBody.Part.createFormData("foto", "entrega_${usuarioId}.jpg", body)
                }
                val resp = RetrofitClient.apiService.registrarEntregaProactiva(
                    insumistaId   = usuarioId.toString().toRequestBody("text/plain".toMediaType()),
                    tipoProducto  = tipo.toRequestBody("text/plain".toMediaType()),
                    insumo        = insumo.toRequestBody("text/plain".toMediaType()),
                    cantidad      = cantidad.toString().toRequestBody("text/plain".toMediaType()),
                    maquina       = maquina.toRequestBody("text/plain".toMediaType()),
                    observaciones = obs?.toRequestBody("text/plain".toMediaType()),
                    foto          = fotoPart
                )
                withContext(Dispatchers.Main) {
                    if (resp.isSuccessful) {
                        Toast.makeText(this@BandejaInsumosActivity,
                            resp.body()?.mensaje ?: "Entrega registrada", Toast.LENGTH_LONG).show()
                        fotoUri = null; fotoFile = null
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
