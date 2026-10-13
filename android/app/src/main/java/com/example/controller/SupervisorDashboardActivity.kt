package com.example.controller

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SupervisorDashboardActivity : AppCompatActivity() {

    private lateinit var rvDashboard: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var tvTitulo: TextView
    private lateinit var tvSubtitulo: TextView
    private lateinit var btnCambiarVista: android.widget.Button
    private lateinit var btnSalir: android.widget.Button

    private var adapterPlanta: MaquinaStatusAdapter? = null
    private var adapterBodega: PedidoSupervisorAdapter? = null

    private var rolUsuario: String = ""
    private var nombreUsuario: String = ""
    private var vistaActual: String = ""
    // Referencia al Job del radar para poder cancelarlo en onPause
    // y evitar que se acumulen coroutines al minimizar/restaurar la Activity.
    private var jobRadar: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // statusBarColor está deprecado en API 35 (edge-to-edge); se mantiene
        // para conservar el color de la barra en versiones anteriores.
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.parseColor("#1E293B")
        setContentView(R.layout.activity_supervisor_dashboard)

        val prefs = getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE)
        nombreUsuario = prefs.getString("usuario_nombre", "Supervisor") ?: "Supervisor"
        rolUsuario = (prefs.getString("usuario_rol", "") ?: "").uppercase()

        rvDashboard  = findViewById(R.id.rvDashboard)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        tvTitulo     = findViewById(R.id.tvTituloDashboard)
        tvSubtitulo  = findViewById(R.id.tvSubtituloDashboard)
        btnCambiarVista = findViewById(R.id.btnCambiarVista)
        btnSalir        = findViewById(R.id.btnSalirSupervisor)

        rvDashboard.layoutManager = LinearLayoutManager(this)

        configurarSegunRol()
        cargarDatos()

        // Solo SUPERADMIN/ADMIN pueden alternar entre vista Planta y Bodega;
        // los supervisores de cada área sólo ven su módulo.
        if (rolUsuario == "SUPERADMIN" || rolUsuario == "ADMIN") {
            btnCambiarVista.visibility = android.view.View.VISIBLE
            btnCambiarVista.setOnClickListener { alternarVista() }
        }

        btnSalir.setOnClickListener { cerrarSesion() }
        swipeRefresh.setOnRefreshListener { cargarDatos() }
    }

    private fun configurarSegunRol() {
        if (rolUsuario == "SUPERVISOR_BODEGA") activarVistaBodega() else activarVistaPlanta()
    }

    private fun alternarVista() {
        if (vistaActual == "PLANTA") activarVistaBodega() else activarVistaPlanta()
        cargarDatos()
    }

    private fun activarVistaPlanta() {
        vistaActual = "PLANTA"
        tvTitulo.text = "TORRE DE CONTROL - PLANTA"
        tvSubtitulo.text = "Supervisor: $nombreUsuario | Estado de Máquinas"
        adapterBodega = null
        adapterPlanta = MaquinaStatusAdapter(emptyList())
        rvDashboard.adapter = adapterPlanta
    }

    private fun activarVistaBodega() {
        vistaActual = "BODEGA"
        tvTitulo.text = "MONITOREO DE LOGÍSTICA"
        tvSubtitulo.text = "Supervisor: $nombreUsuario | Historial 24h"
        adapterPlanta = null
        adapterBodega = PedidoSupervisorAdapter(emptyList())
        rvDashboard.adapter = adapterBodega
    }

    private fun cerrarSesion() {
        RetrofitClient.tokenSesion = null
        PrefsRepository(applicationContext).limpiarSesionUsuario()
        Toast.makeText(this, "Sesión cerrada", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MenuRolesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun cargarDatos(esSilencioso: Boolean = false) {
        if (!esSilencioso) swipeRefresh.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (vistaActual == "PLANTA") {
                    val respuesta = RetrofitClient.apiService.obtenerEstadoPlanta()
                    withContext(Dispatchers.Main) {
                        if (respuesta.isSuccessful && respuesta.body() != null) {
                            adapterPlanta?.actualizarLista(respuesta.body()!!)
                        }
                    }
                } else {
                    val respuesta = RetrofitClient.apiService.obtenerHistorialPedidosSupervisor()
                    withContext(Dispatchers.Main) {
                        if (respuesta.isSuccessful && respuesta.body() != null) {
                            adapterBodega?.actualizarLista(respuesta.body()!!)
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.report("SupervisorDashboard", "Fallo al refrescar dashboard", e)
                if (!esSilencioso) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SupervisorDashboardActivity, "Error de conexión", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { swipeRefresh.isRefreshing = false }
            }
        }
    }

    private fun iniciarRadarSupervisor() {
        // Cancelamos el job anterior antes de crear uno nuevo para que nunca haya
        // más de una coroutine de polling activa al mismo tiempo.
        jobRadar?.cancel()
        // Intervalo de 5 s: equilibrio entre frescura del dato y consumo de red en la tablet.
        jobRadar = lifecycleScope.launch {
            while (true) {
                delay(5000)
                cargarDatos(esSilencioso = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        iniciarRadarSupervisor()
    }

    override fun onPause() {
        super.onPause()
        jobRadar?.cancel()
        jobRadar = null
    }

    override fun onDestroy() {
        super.onDestroy()
        jobRadar?.cancel()
    }
}
