package com.example.controller

import android.content.Intent
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

class BandejaInsumosActivity : AppCompatActivity() {

    // --- ZONA 1: MIS ENTREGAS (ARRIBA) ---
    private lateinit var rvAsignados: RecyclerView
    private lateinit var tvSinAsignados: TextView
    private lateinit var adapterAsignados: PedidoAdapter

    // --- ZONA 2: NUEVOS PEDIDOS (ABAJO) ---
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var cantidadPedidosAnterior = 0 // Para saber si llegó uno nuevo
    private lateinit var rvPedidos: RecyclerView
    private lateinit var tvSinPedidos: TextView
    private lateinit var adapterPedidos: PedidoAdapter

    private lateinit var tvUsuarioLogueado: TextView
    private lateinit var btnCerrarSesion: Button

    private var usuarioId: Int = -1
    private var usuarioRol: String = ""
    private var isPollingActivo = true

    // Seguridad: 8 Horas en milisegundos
    private val OCHO_HORAS_MILLIS = 8 * 60 * 60 * 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bandeja_insumos)
        // Pedir permiso para notificaciones en tablets modernas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        // 1. Vincular los elementos del XML
        rvAsignados = findViewById(R.id.rvAsignados)
        tvSinAsignados = findViewById(R.id.tvSinAsignados)
        rvPedidos = findViewById(R.id.rvPedidos)
        tvSinPedidos = findViewById(R.id.tvSinPedidos)
        tvUsuarioLogueado = findViewById(R.id.tvUsuarioLogueado)
        btnCerrarSesion = findViewById(R.id.btnCerrarSesion)

        // 2. Recuperar memoria y tiempo
        val prefs = getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE)
        usuarioId = prefs.getInt("usuario_id", -1)
        val nombre = prefs.getString("usuario_nombre", "Desconocido")
        usuarioRol = prefs.getString("usuario_rol", "").toString()
        val tiempoLogin = prefs.getLong("tiempo_login", 0L)

        // Control Inmediato: ¿Ya pasaron 8 horas desde que inició?
        if (System.currentTimeMillis() - tiempoLogin > OCHO_HORAS_MILLIS) {
            cerrarSesion("Tu sesión ha expirado por seguridad (8 horas).")
            return
        }

        tvUsuarioLogueado.text = "Insumista: $nombre | $usuarioRol"

        if (usuarioId == -1) {
            cerrarSesion("Error de sesión")
            return
        }

        btnCerrarSesion.setOnClickListener {
            cerrarSesion("Sesión cerrada correctamente")
        }

        // ==========================================
        // 3. CONFIGURAR LAS DOS LISTAS (RECYCLERVIEWS)
        // ==========================================

        // A) Motor de Asignados (Azul, llama a entregarPedido)
        rvAsignados.layoutManager = LinearLayoutManager(this)
        adapterAsignados = PedidoAdapter(emptyList(), esAsignado = true) { pedidoSeleccionado ->
            entregarPedido(pedidoSeleccionado.id)
        }
        rvAsignados.adapter = adapterAsignados

        // B) Motor de Pendientes (Verde, llama a aceptarPedido)
        rvPedidos.layoutManager = LinearLayoutManager(this)
        adapterPedidos = PedidoAdapter(emptyList(), esAsignado = false) { pedidoSeleccionado ->
            aceptarPedido(pedidoSeleccionado.id)
        }
        rvPedidos.adapter = adapterPedidos
        // Desactiva el scroll interno de las listas para que mande el NestedScrollView
        rvAsignados.isNestedScrollingEnabled = false
        rvPedidos.isNestedScrollingEnabled = false
        // 4. Encender el Radar Doble
        iniciarRadarDePedidos(tiempoLogin)
    }

    private fun iniciarRadarDePedidos(tiempoLogin: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && isPollingActivo) {

                // 1. Verificación de Seguridad: Solo si la sesión está activa y no ha caducado
                if (System.currentTimeMillis() - tiempoLogin > OCHO_HORAS_MILLIS) {
                    withContext(Dispatchers.Main) {
                        detenerAlarma() // Apagar todo antes de salir
                        cerrarSesion("Sesión expirada.")
                    }
                    break
                }

                try {
                    // El servidor ya filtra por ROL: Empaque solo recibe Empaque, Granel solo Granel
                    val respPendientes = RetrofitClient.apiService.obtenerPedidosPendientes(usuarioRol)
                    val respAsignados = RetrofitClient.apiService.obtenerPedidosAsignados(usuarioId)

                    withContext(Dispatchers.Main) {
                        if (respPendientes.isSuccessful && respPendientes.body() != null) {
                            val listaPend = respPendientes.body()!!

                            // 🔥 LÓGICA DE ALARMA SEGMENTADA:
                            // Solo suena si la lista que RECIBO (ya filtrada por mi rol) tiene más items que antes
                            if (listaPend.size > cantidadPedidosAnterior) {
                                reproducirAlarma()
                                mostrarNotificacionAlerta(listaPend.size)
                            }

                            // Si la lista de pendientes llega a cero (porque ya acepté todo), apagamos la alarma
                            if (listaPend.isEmpty()) {
                                detenerAlarma()
                            }

                            cantidadPedidosAnterior = listaPend.size

                            // Actualizar UI de pedidos nuevos
                            if (listaPend.isEmpty()) {
                                rvPedidos.visibility = View.GONE
                                tvSinPedidos.visibility = View.VISIBLE
                            } else {
                                rvPedidos.visibility = View.VISIBLE
                                tvSinPedidos.visibility = View.GONE
                                adapterPedidos.actualizarLista(listaPend)
                            }
                        }

                        // Actualizar UI de pedidos asignados (Zona Azul)
                        if (respAsignados.isSuccessful && respAsignados.body() != null) {
                            adapterAsignados.actualizarLista(respAsignados.body()!!)
                            // Si hay elementos arriba, la UI los muestra
                            rvAsignados.visibility = if (respAsignados.body()!!.isEmpty()) View.GONE else View.VISIBLE
                            tvSinAsignados.visibility = if (respAsignados.body()!!.isEmpty()) View.VISIBLE else View.GONE
                        }
                    }
                } catch (e: Exception) {
                    // Si se pierde el Wi-Fi, mejor silenciamos para no confundir
                    withContext(Dispatchers.Main) { detenerAlarma() }
                }
                delay(5000)
            }
        }
    }

    private fun aceptarPedido(pedidoId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = PedidoAccionRequest(pedidoId, usuarioId)
                val response = RetrofitClient.apiService.aceptarPedido(request)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@BandejaInsumosActivity, "✅ Pedido Asignado", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@BandejaInsumosActivity, "Alguien más ya tomó este pedido", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BandejaInsumosActivity, "Error de red al aceptar", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun entregarPedido(pedidoId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = PedidoAccionRequest(pedidoId, usuarioId)
                val response = RetrofitClient.apiService.entregarPedido(request)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@BandejaInsumosActivity, "🚚 Entrega Confirmada", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@BandejaInsumosActivity, "Error al confirmar entrega", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BandejaInsumosActivity, "Error de red al entregar", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- LA FUNCIÓN MAESTRA DE LIMPIEZA ---
    private fun cerrarSesion(mensaje: String) {
        isPollingActivo = false // Detenemos el radar

        // 🔥 PASO CRÍTICO: Detener y destruir el reproductor de audio
        detenerAlarma()
        mediaPlayer?.release()
        mediaPlayer = null // Limpiamos la variable para que no queden rastros

        // Limpiamos la memoria de la sesión
        getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE).edit().clear().apply()

        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()

        // Expulsamos al usuario
        val intent = Intent(this, LoginActivity::class.java)
        // Estas banderas aseguran que no se pueda volver atrás con el botón físico
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        isPollingActivo = false
    }
    private fun reproducirAlarma() {
        try {
            // 1. SECUESTRAR EL VOLUMEN FÍSICO DE LA TABLET
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Descubrimos cuál es el volumen máximo permitido por este modelo de tablet
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

            // Forzamos el volumen al máximo sin preguntarle al usuario
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)

            // 2. CONFIGURAR Y REPRODUCIR LA ALARMA
            if (mediaPlayer == null) {
                mediaPlayer = android.media.MediaPlayer.create(this, R.raw.alarm)
                mediaPlayer?.isLooping = true
            }

            if (mediaPlayer?.isPlaying == false) {
                mediaPlayer?.start()
            }
        } catch (e: Exception) {
            // Fallo silencioso por si la tablet tiene bloqueos de permisos de audio
        }
    }

    private fun detenerAlarma() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            mediaPlayer?.seekTo(0) // Regresamos el audio al segundo 0
        }
    }
    private fun mostrarNotificacionAlerta(cantidad: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "canal_insumos_urgente"

        // 1. Android exige crear un "Canal" para las versiones modernas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Alertas de Pedidos",
                NotificationManager.IMPORTANCE_HIGH // IMPORTANCIA ALTA para que salga el banner emergente
            )
            channel.description = "Notificaciones de la planta"
            notificationManager.createNotificationChannel(channel)
        }

        // 2. ¿Qué pasa si tocan la notificación? Que se abra esta misma pantalla
        val intent = Intent(this, BandejaInsumosActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 3. Diseñamos la tarjetita de la notificación
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // Ícono de alerta nativo de Android
            .setContentTitle("🚨 NUEVO PEDIDO EN PLANTA")
            .setContentText("Tienes $cantidad solicitud(es) esperando atención.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // Desaparece al tocarla

        // 4. Lanzamos la notificación
        notificationManager.notify(999, builder.build())
    }
}
