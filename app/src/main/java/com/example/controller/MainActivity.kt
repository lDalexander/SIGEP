package com.example.controller

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.widget.Chronometer
import androidx.appcompat.app.AlertDialog
import android.os.SystemClock
import android.graphics.Color
import android.content.Intent
import android.os.Build
import android.content.Context
import android.provider.Settings
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.example.controller.data.ColaSyncRepository
import com.example.controller.sync.ConnectivityObserver
import com.example.controller.sync.TabletsWebSocketManager
import com.example.controller.ui.AjustesTabletActivity
import com.example.controller.ui.PendientesActivity
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity() {

    private var sesionActualId: Int? = null
    private lateinit var btnSolicitarInsumo: Button
    private lateinit var btnParo: Button
    private lateinit var colaRepo: ColaSyncRepository
    private lateinit var connectivityObserver: ConnectivityObserver
    private lateinit var tabletsWsManager: TabletsWebSocketManager
    private lateinit var tvBadge: android.widget.TextView

    private var paroActivo = false
    private lateinit var seccionConfiguracion: LinearLayout
    private lateinit var seccionProduccion: LinearLayout
    private lateinit var etCantidadPacas: EditText
    private lateinit var btnIniciarTurno: Button
    private lateinit var btnEnviarPallet: Button
    private lateinit var tvHistorialPacas: TextView
    private lateinit var btnTerminarTurno: Button
    private lateinit var tvStatusPedidos: TextView
    private lateinit var btnVerEstadoPedidos: Button
    private lateinit var rvPendientesConfirmacion: RecyclerView
    private lateinit var tvPendientesConfirmacionLabel: TextView
    private lateinit var adapterPendientesConfirmacion: PedidoAdapter

    private lateinit var spinnerTipo: Spinner
    private lateinit var spinnerMaquina: Spinner
    private lateinit var spinnerOperador: Spinner
    private lateinit var spinnerMarca: Spinner
    private lateinit var spinnerPresentacion: Spinner
    private lateinit var spinnerFragancia: Spinner

    private val NOMBRE_PREFS = "DetcuadorPrefs"

    // Listener para que el worker pueda "empujarnos" el id real de la sesión cuando
    // el server lo confirma. Si no escuchamos esto, MainActivity sigue creyendo que
    // la sesión es -99 y al finalizar el server devuelve 404 "Sesion no encontrada".
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key != "sesion_activa") return@OnSharedPreferenceChangeListener
        val nuevo = prefs.getInt("sesion_activa", -1)
        runOnUiThread {
            when {
                // Worker limpió la sesión (finalizar falló 404, o nosotros mismos
                // disparamos forzarReinicioPorError): volver a la pantalla de inicio.
                nuevo == -1 && sesionActualId != null -> {
                    forzarReinicioPorError("Sesión cerrada — inicie un turno nuevo.")
                }
                // Worker confirmó el id real: reemplazamos el -99 local.
                nuevo > 0 && (sesionActualId == null || sesionActualId == -99) -> {
                    sesionActualId = nuevo
                    iniciarRadarStatusPedidos(nuevo)
                }
            }
        }
    }

    // Turno pendiente de iniciar mientras el operario llena el checklist de equipo.
    private var turnoPendiente: IniciarTurnoRequest? = null

    // Lanzador del checklist: si vuelve OK, continúa con el inicio de turno.
    private val checklistLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val req = turnoPendiente
        turnoPendiente = null
        if (result.resultCode == RESULT_OK && req != null) {
            llamarApiIniciarTurno(req)
        } else {
            btnIniciarTurno.isEnabled = true
            btnIniciarTurno.text = "INICIAR TURNO"
        }
    }

    val tipos = arrayOf("-- Seleccione Tipo --", "Sólido", "Líquido")
    val fragancias = arrayOf("-- Seleccione Fragancia --", "Limón", "Floral")

    private var listaOperadoresServidor: List<String> = emptyList()
    private var listaMaquinasServidor: List<String> = emptyList()

    // Jerarquía dinámica descargada con la lista de máquinas.
    // Cuando el backend incluya `marcas` en api/maquinas, este mapa reemplaza los locales.
    private var jerarquiaMaquinas: Map<String, List<MarcaResponse>> = emptyMap()

    // Fallback local hasta que el backend devuelva la jerarquía en api/maquinas.
    // Eliminar estos mapas cuando el servidor implemente el campo `marcas` en MaquinaResponse.
    private val listaMaquinasLocal = listOf(
        "-- Seleccione Máquina --",
        "Máquina 7", "Máquina 7B", "Máquina 8", "Máquina 9", "Máquina 16"
    )

    private val listaOperadoresLocal = listOf(
        "-- Seleccione Operador --",
        "ALEX VALENZUELA", "JONATHAN VICUÑA", "ROSENDO VALENZUELA",
        "CRISTHIAN CEDEÑO", "ROLANDO MORAN", "SAMUEL MEJIA",
        "JESUS LAZO", "ANTHONY MERCADO", "ERWIN ARAGON",
        "JOSIAS VILLAMAR", "PEDRO SARABIA", "KEVIN SORIANO"
    )

    private val marcasPorMaquinaLocal = mapOf(
        "Máquina 7"  to arrayOf("-- Seleccione Marca --", "TORBELLINO", "ULTREX", "COMISARIATO"),
        "Máquina 7B" to arrayOf("-- Seleccione Marca --", "ULTREX", "PQP", "COMISARIATO", "HIT"),
        "Máquina 8"  to arrayOf("-- Seleccione Marca --", "ULTREX", "HIT"),
        "Máquina 9"  to arrayOf("-- Seleccione Marca --", "TORBELLINO", "ULTREX", "COMISARIATO"),
        "Máquina 16" to arrayOf("-- Seleccione Marca --", "TORBELLINO", "ULTREX", "COMISARIATO")
    )

    private val presentacionesPorMaquinaMarcaLocal = mapOf(
        "Máquina 7|ULTREX"       to arrayOf("-- Seleccione Presentación --", "1 KG", "2 KG", "3 KG"),
        "Máquina 7|TORBELLINO"   to arrayOf("-- Seleccione Presentación --", "1 KG"),
        "Máquina 7|COMISARIATO"  to arrayOf("-- Seleccione Presentación --", "1.2 KG"),
        "Máquina 8|ULTREX"       to arrayOf("-- Seleccione Presentación --", "100 GR", "250 GR", "500 GR"),
        "Máquina 8|HIT"          to arrayOf("-- Seleccione Presentación --", "360 GR"),
        "Máquina 7B|PQP"         to arrayOf("-- Seleccione Presentación --", "15 KG", "25 KG"),
        "Máquina 7B|HIT"         to arrayOf("-- Seleccione Presentación --", "5 KG"),
        "Máquina 7B|ULTREX"      to arrayOf("-- Seleccione Presentación --", "5 KG"),
        "Máquina 7B|COMISARIATO" to arrayOf("-- Seleccione Presentación --", "5 KG"),
        "Máquina 9|TORBELLINO"   to arrayOf("-- Seleccione Presentación --", "1 KG"),
        "Máquina 9|ULTREX"       to arrayOf("-- Seleccione Presentación --", "1 KG"),
        "Máquina 9|COMISARIATO"  to arrayOf("-- Seleccione Presentación --", "1.2 KG"),
        "Máquina 16|ULTREX"      to arrayOf("-- Seleccione Presentación --", "1 KG", "2 KG", "3 KG"),
        "Máquina 16|TORBELLINO"  to arrayOf("-- Seleccione Presentación --", "1 KG"),
        "Máquina 16|COMISARIATO" to arrayOf("-- Seleccione Presentación --", "1.2 KG")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvHistorialPacas    = findViewById(R.id.tvHistorialPacas)
        btnVerEstadoPedidos = findViewById(R.id.btnVerEstadoPedidos)
        tvBadge             = findViewById(R.id.tvBadgePendientes)
        vincularVistas()

        rvPendientesConfirmacion.layoutManager = LinearLayoutManager(this)
        adapterPendientesConfirmacion = PedidoAdapter(emptyList(), esAsignado = false, esConfirmacion = true) { pedido ->
            confirmarRecepcion(pedido.id)
        }
        rvPendientesConfirmacion.adapter = adapterPendientesConfirmacion
        rvPendientesConfirmacion.isNestedScrollingEnabled = false

        colaRepo = ColaSyncRepository(applicationContext)
        val tabletPrefs = PrefsRepository(applicationContext)
        connectivityObserver = ConnectivityObserver(applicationContext)
        tabletsWsManager = TabletsWebSocketManager(tabletPrefs.deviceId, WorkManager.getInstance(applicationContext))

        observarBadge()
        cargarCatalogosDesdeCache()
        configurarFiltrosEnCascada()
        configurarBotones()

        btnVerEstadoPedidos.setOnClickListener {
            if (sesionActualId != null && sesionActualId != -1) {
                mostrarVentanaEstadoPedidos()
            } else {
                Toast.makeText(this, "Debes iniciar turno primero", Toast.LENGTH_SHORT).show()
            }
        }

        descargarCatalogosDesdeServidor()

        val prefs = getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        val sesionGuardada  = prefs.getInt("sesion_activa", -1)
        val timestampInicio = prefs.getLong("hora_inicio_ms", 0L)

        if (sesionGuardada != -1) {
            val horasTranscurridas = (System.currentTimeMillis() - timestampInicio) / (1000 * 60 * 60)

            if (horasTranscurridas >= 9) {
                // Límite de 9h: evita que un turno del día anterior quede activo indefinidamente
                // si el operario olvidó cerrarlo al final de su jornada.
                forzarReinicioPorError("Turno anterior caducado (límite de 9h). Inicie uno nuevo.")
            } else if (sesionGuardada == -99) {
                // Sesión local "en sincronización". Si NO hay INICIAR_TURNO esperando
                // en cola, es huérfana (el server ya tiene id real pero MainActivity
                // nunca se enteró por la versión vieja, o el usuario descartó).
                // Limpiamos para sacarlo del limbo.
                lifecycleScope.launch {
                    if (colaRepo.existeIniciarTurnoEnCola()) {
                        sesionActualId = -99
                        AppState.usuarioRol = prefs.getString("usuario_rol", "OPERADOR")
                        seccionConfiguracion.visibility = View.GONE
                        seccionProduccion.visibility    = View.VISIBLE
                        Toast.makeText(this@MainActivity, "Turno en sincronización…", Toast.LENGTH_SHORT).show()
                    } else {
                        forzarReinicioPorError("Sesión local huérfana — inicie un turno nuevo.")
                    }
                }
            } else {
                sesionActualId = sesionGuardada
                AppState.usuarioRol = prefs.getString("usuario_rol", "OPERADOR")
                seccionConfiguracion.visibility = View.GONE
                seccionProduccion.visibility    = View.VISIBLE
                Toast.makeText(this, "Turno recuperado", Toast.LENGTH_SHORT).show()
                recuperarHistorial(sesionGuardada)
                iniciarRadarStatusPedidos(sesionGuardada)
            }
        }
    }

    override fun onDestroy() {
        getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    private fun mostrarVentanaEstadoPedidos() {
        val tvContenido = android.widget.TextView(this).apply {
            setPadding(50, 30, 50, 30)
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#1E293B"))
            text = "Consultando al servidor..."
        }
        val scrollView = android.widget.ScrollView(this).apply { addView(tvContenido) }

        val dialogo = AlertDialog.Builder(this)
            .setTitle("Mis Pedidos de Insumos")
            .setView(scrollView)
            .setPositiveButton("CERRAR", null)
            .create()
        dialogo.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val respDetalle = RetrofitClient.apiService.obtenerDetallePedidos(sesionActualId!!)
                if (respDetalle.isSuccessful && respDetalle.body() != null) {
                    val pedidos = respDetalle.body()!!
                    withContext(Dispatchers.Main) {
                        if (pedidos.isEmpty()) {
                            tvContenido.text = "No has realizado ninguna solicitud en este turno."
                            return@withContext
                        }
                        val sb = StringBuilder()
                        pedidos.forEach { p ->
                            val (emoji, estadoTexto) = when (p.estado.lowercase()) {
                                "pendiente"           -> "🕐" to "Sin atender"
                                "en camino"           -> "🚶" to "En camino"
                                "entregado_insumista" -> "⏳" to "Entregado — esperando tu confirmación"
                                "entregado"           -> "✅" to "Completado"
                                else                  -> "❓" to p.estado
                            }
                            sb.append("━━━━━━━━━━━━━━━━━━━━\n")
                            sb.append("📦 ${p.cantidad}x ${p.detalle}\n")
                            sb.append("⏰ Solicitado:  ${p.hora_solicitud}\n")
                            sb.append("$emoji $estadoTexto\n")
                            if (p.insumista != null) {
                                sb.append("👤 ${p.insumista}")
                                if (p.hora_aceptacion != null) sb.append(" · aceptó: ${p.hora_aceptacion}")
                                sb.append("\n")
                            }
                            if (p.hora_entrega != null) {
                                sb.append("📬 Entregó: ${p.hora_entrega}\n")
                            }
                            sb.append("\n")
                        }
                        tvContenido.text = sb.toString().trimEnd()
                    }
                } else {
                    // Fallback a conteos básicos si el endpoint detallado falla
                    val respBasico = RetrofitClient.apiService.obtenerEstadoPedidos(sesionActualId!!)
                    withContext(Dispatchers.Main) {
                        if (respBasico.isSuccessful && respBasico.body() != null) {
                            val d = respBasico.body()!!
                            val sinActividad = d.pendientes == 0 && d.en_camino == 0 &&
                                               d.entregados == 0 && d.pendiente_confirmacion == 0
                            tvContenido.text = if (sinActividad) {
                                "No has realizado ninguna solicitud en este turno."
                            } else {
                                buildString {
                                    if (d.pendientes > 0)             append("Pendientes:    ${d.pendientes}\n")
                                    if (d.en_camino > 0)              append("En Camino:     ${d.en_camino}\n")
                                    if (d.pendiente_confirmacion > 0) append("Por confirmar: ${d.pendiente_confirmacion}\n")
                                    if (d.entregados > 0)             append("Entregados:    ${d.entregados}")
                                }.trimEnd()
                            }
                        } else {
                            tvContenido.text = "Error al obtener los datos."
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.report("MainActivity", "Fallo al consultar detalle de pedidos", e)
                withContext(Dispatchers.Main) {
                    tvContenido.text = "Error de conexión. Revisa tu red."
                }
            }
        }
    }

    private fun iniciarRadarStatusPedidos(sesionId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val respuesta = RetrofitClient.apiService.obtenerEstadoPedidos(sesionId)
                    withContext(Dispatchers.Main) {
                        if (respuesta.isSuccessful && respuesta.body() != null) {
                            val d = respuesta.body()!!
                            val sinActividad = d.pendientes == 0 && d.en_camino == 0 &&
                                               d.entregados == 0 && d.pendiente_confirmacion == 0
                            tvStatusPedidos.text = if (sinActividad) {
                                "Sin solicitudes aún"
                            } else {
                                buildString {
                                    if (d.pendientes > 0)             append("Pendientes: ${d.pendientes}\n")
                                    if (d.en_camino > 0)              append("En Camino:  ${d.en_camino}\n")
                                    if (d.pendiente_confirmacion > 0) append("⚠️ Por confirmar: ${d.pendiente_confirmacion}\n")
                                    if (d.entregados > 0)             append("Entregados: ${d.entregados}")
                                }.trimEnd()
                            }
                        }
                    }
                    cargarPendientesConfirmacion(sesionId)
                } catch (e: Exception) {
                    // El radar es informativo: si la red se cayó, no molestamos al operario,
                    // pero si es un bug real (parse error, NPE) ErrorReporter lo reporta.
                    ErrorReporter.report("RadarPedidos", "Fallo en polling del radar", e)
                }
                delay(5000)
            }
        }
    }

    private fun cargarPendientesConfirmacion(sesionId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.apiService.obtenerPendientesConfirmacion(sesionId)
                withContext(Dispatchers.Main) {
                    if (resp.isSuccessful) {
                        val lista = resp.body() ?: emptyList()
                        val tieneItems = lista.isNotEmpty()
                        tvPendientesConfirmacionLabel.visibility = if (tieneItems) View.VISIBLE else View.GONE
                        rvPendientesConfirmacion.visibility      = if (tieneItems) View.VISIBLE else View.GONE
                        adapterPendientesConfirmacion.actualizarLista(lista)
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.report("PendientesConfirmacion", "Fallo al cargar lista", e)
            }
        }
    }

    private fun confirmarRecepcion(pedidoId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.apiService.confirmarRecepcion(
                    ConfirmarRecepcionRequest(pedidoId, sesionActualId!!)
                )
                withContext(Dispatchers.Main) {
                    if (resp.isSuccessful) {
                        Toast.makeText(this@MainActivity, "Recepción confirmada", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Error al confirmar recepción", Toast.LENGTH_SHORT).show()
                    }
                    cargarPendientesConfirmacion(sesionActualId!!)
                }
            } catch (e: Exception) {
                ErrorReporter.report("MainActivity", "Fallo al confirmar recepción", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error de red", Toast.LENGTH_SHORT).show()
                    cargarPendientesConfirmacion(sesionActualId!!)
                }
            }
        }
    }

    private fun vincularVistas() {
        btnSolicitarInsumo   = findViewById(R.id.btnSolicitarInsumo)
        seccionConfiguracion = findViewById(R.id.seccionConfiguracion)
        seccionProduccion    = findViewById(R.id.seccionProduccion)
        spinnerTipo          = findViewById(R.id.spinnerTipo)
        spinnerMaquina       = findViewById(R.id.spinnerMaquina)
        spinnerOperador      = findViewById(R.id.spinnerOperador)
        spinnerMarca         = findViewById(R.id.spinnerMarca)
        spinnerPresentacion  = findViewById(R.id.spinnerPresentacion)
        spinnerFragancia     = findViewById(R.id.spinnerFragancia)
        etCantidadPacas      = findViewById(R.id.etCantidadPacas)
        btnIniciarTurno      = findViewById(R.id.btnIniciarTurno)
        btnEnviarPallet      = findViewById(R.id.btnEnviarPallet)
        btnTerminarTurno     = findViewById(R.id.btnTerminarTurno)
        btnParo              = findViewById(R.id.btnParo)
        tvStatusPedidos               = findViewById(R.id.tvStatusPedidos)
        rvPendientesConfirmacion      = findViewById(R.id.rvPendientesConfirmacion)
        tvPendientesConfirmacionLabel = findViewById(R.id.tvPendientesConfirmacionLabel)
    }

    private fun configurarFiltrosEnCascada() {
        spinnerTipo.adapter = ArrayAdapter(this, R.layout.spinner_item, tipos)
        bloquearSpinnersDesdeMaquina()

        spinnerTipo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    if (tipos[position] == "Sólido") {
                        val maquinas = if (listaMaquinasServidor.size > 1) listaMaquinasServidor else listaMaquinasLocal
                        spinnerMaquina.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, maquinas)
                        spinnerMaquina.isEnabled = true
                    } else {
                        spinnerMaquina.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, arrayOf("-- En desarrollo --"))
                        spinnerMaquina.isEnabled = false
                    }
                    bloquearSpinnersDesdeOperador()
                } else {
                    bloquearSpinnersDesdeMaquina()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerMaquina.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    val maquinaSeleccionada = spinnerMaquina.selectedItem.toString()

                    val operadores = if (listaOperadoresServidor.size > 1) listaOperadoresServidor else listaOperadoresLocal
                    spinnerOperador.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, operadores)
                    spinnerOperador.isEnabled = true
                    spinnerFragancia.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, fragancias)
                    spinnerFragancia.isEnabled = true

                    // Usamos la jerarquía del servidor cuando ya está disponible;
                    // el fallback local mantiene funcionalidad hasta que el backend la implemente.
                    val marcas: List<String> = if (jerarquiaMaquinas.containsKey(maquinaSeleccionada)) {
                        listOf("-- Seleccione Marca --") + jerarquiaMaquinas[maquinaSeleccionada]!!.map { it.nombre }
                    } else {
                        (marcasPorMaquinaLocal[maquinaSeleccionada] ?: arrayOf("-- Sin Configurar --")).toList()
                    }
                    spinnerMarca.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, marcas)
                    spinnerMarca.isEnabled = true

                    spinnerPresentacion.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, arrayOf("-- Seleccione primero la Marca --"))
                    spinnerPresentacion.isEnabled = false
                    btnIniciarTurno.isEnabled = false
                } else {
                    bloquearSpinnersDesdeOperador()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerMarca.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    val maquinaActual = spinnerMaquina.selectedItem.toString()
                    val marcaActual   = spinnerMarca.selectedItem.toString()

                    val presentaciones: List<String> = if (jerarquiaMaquinas.containsKey(maquinaActual)) {
                        val marcaEncontrada = jerarquiaMaquinas[maquinaActual]?.find { it.nombre == marcaActual }
                        listOf("-- Seleccione Presentación --") + (marcaEncontrada?.presentaciones ?: emptyList())
                    } else {
                        val clave = "$maquinaActual|$marcaActual"
                        (presentacionesPorMaquinaMarcaLocal[clave] ?: arrayOf("-- Sin Configurar --")).toList()
                    }
                    spinnerPresentacion.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, presentaciones)
                    spinnerPresentacion.isEnabled = true
                    btnIniciarTurno.isEnabled = true
                } else {
                    spinnerPresentacion.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, arrayOf("-- Seleccione primero la Marca --"))
                    spinnerPresentacion.isEnabled = false
                    btnIniciarTurno.isEnabled = false
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun bloquearSpinnersDesdeMaquina() {
        spinnerMaquina.adapter = ArrayAdapter(this, R.layout.spinner_item, arrayOf("-- Bloqueado --"))
        spinnerMaquina.isEnabled = false
        bloquearSpinnersDesdeOperador()
    }

    private fun bloquearSpinnersDesdeOperador() {
        spinnerOperador.adapter    = ArrayAdapter(this, R.layout.spinner_item, arrayOf("-- Bloqueado --"))
        spinnerMarca.adapter       = ArrayAdapter(this, R.layout.spinner_item, arrayOf("-- Bloqueado --"))
        spinnerPresentacion.adapter = ArrayAdapter(this, R.layout.spinner_item, arrayOf("-- Bloqueado --"))
        spinnerFragancia.adapter   = ArrayAdapter(this, R.layout.spinner_item, arrayOf("-- Bloqueado --"))
        spinnerOperador.isEnabled    = false
        spinnerMarca.isEnabled       = false
        spinnerPresentacion.isEnabled = false
        spinnerFragancia.isEnabled   = false
        btnIniciarTurno.isEnabled    = false
    }

    private fun configurarBotones() {
        btnIniciarTurno.setOnClickListener {
            btnIniciarTurno.isEnabled = false
            btnIniciarTurno.text = "Iniciando..."

            if (spinnerMaquina.selectedItemPosition == 0 ||
                spinnerOperador.selectedItemPosition == 0 ||
                spinnerMarca.selectedItemPosition == 0 ||
                spinnerPresentacion.selectedItemPosition == 0 ||
                spinnerFragancia.selectedItemPosition == 0) {

                Toast.makeText(this, "Debe completar todos los campos obligatorios.", Toast.LENGTH_LONG).show()
                btnIniciarTurno.isEnabled = true
                btnIniciarTurno.text = "INICIAR TURNO"
                return@setOnClickListener
            }

            val maquinaSel  = spinnerMaquina.selectedItem.toString()
            val operadorSel = spinnerOperador.selectedItem.toString()

            lifecycleScope.launch {
                val conflicto = verificarConflictoTurnoActivo(operadorSel, maquinaSel)
                if (conflicto != null) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No se puede iniciar el turno")
                        .setMessage(conflicto)
                        .setPositiveButton("Entendido", null)
                        .show()
                    btnIniciarTurno.isEnabled = true
                    btnIniciarTurno.text = "INICIAR TURNO"
                    return@launch
                }

                // Antes de iniciar turno, exigir el checklist de estado de equipo.
                // Si la misma máquina + mismo operador ya lo hizo este turno, se salta.
                verificarChecklistYContinuar(
                    IniciarTurnoRequest(
                        tipo         = spinnerTipo.selectedItem.toString(),
                        maquina      = maquinaSel,
                        operador     = operadorSel,
                        marca        = spinnerMarca.selectedItem.toString(),
                        presentacion = spinnerPresentacion.selectedItem.toString(),
                        fragancia    = spinnerFragancia.selectedItem.toString(),
                        request_id   = ""   // ColaSyncRepository asigna el UUID real al insertar
                    )
                )
            }
        }

        btnEnviarPallet.setOnClickListener {
            val cantidad = etCantidadPacas.text.toString().toIntOrNull()
            if (cantidad == null || cantidad <= 0) {
                Toast.makeText(this, "Ingrese una cantidad válida", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (sesionActualId != null) {
                llamarApiRegistrarPallet(RegistrarPalletRequest(sesionActualId!!, cantidad))
            }
        }

        btnSolicitarInsumo.setOnClickListener {
            if (sesionActualId == null || sesionActualId == -99) {
                Toast.makeText(this, "Debe tener red para solicitar insumos.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            abrirModalInsumosInteligente()
        }

        btnTerminarTurno.setOnClickListener {
            if (sesionActualId != null) {
                llamarApiFinalizarTurno(FinalizarTurnoRequest(sesionActualId!!))
            }
        }

        btnParo.setOnClickListener {
            if (sesionActualId == null || sesionActualId == -99) {
                Toast.makeText(this, "Debe tener red para registrar paros.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!paroActivo) {
                val contenedor = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(50, 40, 50, 20)
                }

                val tituloSpinner = TextView(this).apply {
                    text = "Categoría del paro:"
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                contenedor.addView(tituloSpinner)

                val spinnerTipoParo = Spinner(this).apply {
                    setPadding(0, 0, 0, 30)
                    adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        arrayOf("Mantenimiento", "Bodega", "Planeación", "Aseo/Limpieza", "Otro")
                    )
                }
                contenedor.addView(spinnerTipoParo)

                val tituloTexto = TextView(this).apply {
                    text = "Motivo detallado:"
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 20, 0, 0)
                }
                contenedor.addView(tituloTexto)

                val inputMotivo = EditText(this).apply { hint = "Ej: Por temas de arrastres..." }
                contenedor.addView(inputMotivo)

                AlertDialog.Builder(this)
                    .setTitle("Registrar Tiempo Muerto")
                    .setView(contenedor)
                    .setPositiveButton("Confirmar Paro") { _, _ ->
                        val detalle = inputMotivo.text.toString().trim()
                        if (detalle.isNotEmpty()) {
                            // Concatenamos categoría y detalle en un solo campo
                            // para no modificar el esquema de base de datos existente.
                            llamarApiIniciarParo("[${spinnerTipoParo.selectedItem}] - $detalle")
                        } else {
                            Toast.makeText(this, "Debe escribir el motivo detallado", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            } else {
                llamarApiFinalizarParo()
            }
        }

        // Engranaje (esquina superior): editor de formularios, sólo para admin.
        findViewById<android.widget.ImageButton>(R.id.btnConfigChecklist).setOnClickListener {
            mostrarDialogoAdminChecklist()
        }
    }

    // Pide PIN de admin (POST /api/admin/login). Si valida, abre el constructor de
    // formularios. Reutiliza el layout dialog_admin_auth, igual que MenuRolesActivity.
    private fun mostrarDialogoAdminChecklist() {
        val vista = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_admin_auth, null)
        val etUsuario = vista.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAdminUser)
        val etPass = vista.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAdminPassword)

        val dialogo = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Acceso de administrador")
            .setView(vista)
            .setCancelable(true)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Ingresar", null)
            .create()
        dialogo.show()

        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val usuario = etUsuario.text.toString().trim()
            val pass = etPass.text.toString().trim()
            if (usuario.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Complete las credenciales", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val btn = dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = false
            btn.text = "Validando…"
            lifecycleScope.launch {
                try {
                    val resp = withContext(Dispatchers.IO) {
                        RetrofitClient.apiService.loginAdministrador(AdminLoginRequest(usuario, pass))
                    }
                    if (resp.isSuccessful && resp.body() != null) {
                        dialogo.dismiss()
                        startActivity(Intent(this@MainActivity, com.example.controller.ui.AdminChecklistActivity::class.java))
                    } else {
                        btn.isEnabled = true
                        btn.text = "Ingresar"
                        etPass.error = "Credenciales incorrectas"
                        etPass.setText("")
                    }
                } catch (e: Exception) {
                    ErrorReporter.warn("MainActivity", "Fallo login admin checklist: ${e.message}")
                    btn.isEnabled = true
                    btn.text = "Ingresar"
                    Toast.makeText(this@MainActivity, "Error de conexión", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ==========================================
    // LLAMADAS A LA API
    // ==========================================

    // Pre-flight contra el estado en tiempo real de la planta para que el operador
    // no quede atascado con un INICIAR_TURNO en cola que el backend va a rechazar
    // ("operador ya tiene turno" / "máquina ya tiene sesión activa"). Si la red
    // falla devolvemos null para no bloquear cuando estamos offline.
    private suspend fun verificarConflictoTurnoActivo(operador: String, maquina: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.apiService.obtenerEstadoPlanta()
                if (!resp.isSuccessful) return@withContext null
                val planta = resp.body() ?: return@withContext null

                val maquinaOcupada = planta.find {
                    it.maquina.equals(maquina, ignoreCase = true) &&
                    !it.estado.equals("OFFLINE", ignoreCase = true)
                }
                if (maquinaOcupada != null) {
                    return@withContext "La máquina \"$maquina\" ya tiene un turno activo" +
                        (if (maquinaOcupada.operador.isNotBlank()) " (operador: ${maquinaOcupada.operador})." else ".")
                }

                val operadorOcupado = planta.find {
                    it.operador.equals(operador, ignoreCase = true) &&
                    !it.estado.equals("OFFLINE", ignoreCase = true)
                }
                if (operadorOcupado != null) {
                    return@withContext "El operador \"$operador\" ya tiene un turno activo en la máquina ${operadorOcupado.maquina}."
                }

                null
            } catch (e: Exception) {
                // Pre-flight es best-effort: si falla por red, dejamos pasar.
                // Reportamos sólo si es un bug real (parse, NPE), no si la tablet está offline.
                ErrorReporter.report("PreflightTurno", "Fallo en verificación de conflicto", e)
                null
            }
        }
    }

    private fun descargarCatalogosDesdeServidor() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val respOps  = RetrofitClient.apiService.obtenerOperadores()
                val respMaqs = RetrofitClient.apiService.obtenerMaquinas()

                if (respOps.isSuccessful && respMaqs.isSuccessful) {
                    val operadores = listOf("-- Seleccione Operador --") + (respOps.body()?.map { it.nombre } ?: emptyList())
                    val maquinas   = listOf("-- Seleccione Máquina --") + (respMaqs.body()?.map { it.nombre } ?: emptyList())

                    // Extraemos la jerarquía si el backend ya la incluye en la respuesta.
                    // Si el campo `marcas` es null, el fallback local sigue activo.
                    val jerarquia = mutableMapOf<String, List<MarcaResponse>>()
                    respMaqs.body()?.forEach { maquina ->
                        if (!maquina.marcas.isNullOrEmpty()) {
                            jerarquia[maquina.nombre] = maquina.marcas
                        }
                    }

                    withContext(Dispatchers.Main) {
                        listaOperadoresServidor = operadores
                        listaMaquinasServidor   = maquinas
                        if (jerarquia.isNotEmpty()) jerarquiaMaquinas = jerarquia
                        guardarCatalogosEnCache(operadores, maquinas, jerarquia)
                        val pos = spinnerTipo.selectedItemPosition
                        if (pos > 0 && tipos[pos] == "Sólido") {
                            spinnerMaquina.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, maquinas)
                            spinnerMaquina.isEnabled = true
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.report("Catalogos", "No se pudo actualizar el catálogo — usando caché local", e)
            }
        }
    }

    // Decide si hace falta el checklist de estado de equipo para esta máquina+operario
    // en el turno actual. Si no hace falta, inicia turno directo; si hace falta, abre
    // el formulario y continúa al volver con RESULT_OK.
    private fun verificarChecklistYContinuar(req: IniciarTurnoRequest) {
        lifecycleScope.launch {
            val requiere = checklistRequerido(req.maquina, req.operador)
            if (!requiere) {
                llamarApiIniciarTurno(req)
            } else {
                turnoPendiente = req
                val intent = Intent(this@MainActivity, com.example.controller.ui.ChecklistEquipoActivity::class.java).apply {
                    putExtra(com.example.controller.ui.ChecklistEquipoActivity.EXTRA_MAQUINA, req.maquina)
                    putExtra(com.example.controller.ui.ChecklistEquipoActivity.EXTRA_OPERADOR, req.operador)
                }
                checklistLauncher.launch(intent)
            }
        }
    }

    // Online: el backend es la autoridad (calcula turno con su hora). Offline: usamos
    // el guard local — si esta máquina+operario ya completó el checklist este turno,
    // no se vuelve a pedir.
    private suspend fun checklistRequerido(maquina: String, operador: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.apiService.obtenerEstadoChecklist(maquina, operador)
                if (resp.isSuccessful && resp.body() != null) {
                    return@withContext resp.body()!!.requiereChecklist
                }
            } catch (_: Exception) {
                // Sin red: caemos al guard local.
            }
            val turno = com.example.controller.util.TurnoUtil.actual()
            !com.example.controller.data.local.AppDatabase.getInstance(applicationContext)
                .checklistDao()
                .yaCompletado(maquina, operador, turno.fechaTurno, turno.codigo)
        }
    }

    private fun llamarApiIniciarTurno(datos: IniciarTurnoRequest) {
        btnIniciarTurno.isEnabled = false
        btnIniciarTurno.text = "Enviando..."

        // Estrategia: intentar directo primero. Si el server responde (con éxito o
        // con rechazo del negocio), damos feedback honesto al usuario. Solo si la
        // red falla (timeout/IOException) caemos al modo cola.
        // Generamos un request_id estable que se reutiliza si toca encolar, para
        // que el server pueda deduplicar si el intento directo sí llegó pero la
        // respuesta se perdió.
        lifecycleScope.launch {
            val datosConId = datos.copy(request_id = java.util.UUID.randomUUID().toString())

            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.iniciarTurno(datosConId)
                }
                if (resp.isSuccessful && resp.body() != null) {
                    val sesionId = resp.body()!!.sesion_id
                    sesionActualId = sesionId
                    getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE).edit().apply {
                        putInt("sesion_activa", sesionId)
                        putLong("hora_inicio_ms", System.currentTimeMillis())
                        apply()
                    }
                    seccionConfiguracion.visibility = View.GONE
                    seccionProduccion.visibility    = View.VISIBLE
                    Toast.makeText(this@MainActivity, "Turno iniciado", Toast.LENGTH_SHORT).show()
                    iniciarRadarStatusPedidos(sesionId)
                } else {
                    // El server respondió pero rechazó. Encolar acá solo va a fallar
                    // otra vez con el mismo 4xx, así que mostramos el motivo real y
                    // dejamos que el usuario corrija (ej. seleccionar otra máquina).
                    val detalle = try {
                        resp.errorBody()?.string()?.take(300)?.replace("\n", " ")?.trim()
                    } catch (_: Exception) { null } ?: "Error HTTP ${resp.code()}"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No se pudo iniciar el turno")
                        .setMessage(detalle)
                        .setPositiveButton("Entendido", null)
                        .show()
                }
            } catch (e: Exception) {
                // Sin red o timeout: cae al modo cola. Reusamos el mismo request_id
                // para que el server pueda deduplicar si el intento directo sí entró.
                ErrorReporter.warn("MainActivity", "Sin red al iniciar turno, encolando: ${e.message}")
                colaRepo.enqueueIniciarTurno(datosConId)
                sesionActualId = -99
                getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE).edit().apply {
                    putInt("sesion_activa", -99)
                    putLong("hora_inicio_ms", System.currentTimeMillis())
                    apply()
                }
                seccionConfiguracion.visibility = View.GONE
                seccionProduccion.visibility    = View.VISIBLE
                Toast.makeText(
                    this@MainActivity,
                    "Sin red — turno guardado, se enviará al reconectar",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                btnIniciarTurno.isEnabled = true
                btnIniciarTurno.text = "INICIAR TURNO"
            }
        }
    }

    // Helper para que los 4 botones (pacas, finalizar, paros) tengan el mismo
    // mensaje cuando el server rechaza, sin duplicar el código de leer errorBody.
    private fun extraerDetalleErrorHttp(resp: retrofit2.Response<*>): String {
        return try {
            resp.errorBody()?.string()?.take(300)?.replace("\n", " ")?.trim()
        } catch (_: Exception) { null } ?: "Error HTTP ${resp.code()}"
    }

    private fun llamarApiRegistrarPallet(datos: RegistrarPalletRequest) {
        btnEnviarPallet.isEnabled = false
        val cantidad = datos.cantidad_pacas
        val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

        lifecycleScope.launch {
            // Sesión aún sin id real (turno encolado offline): no tiene sentido
            // intentar directo, el server no conoce -99. Encolamos y SyncWorker
            // la enviará con el id real cuando iniciar_turno se sincronice.
            if (sesionActualId == -99) {
                colaRepo.enqueueRegistrarPallet(datos)
                tvHistorialPacas.text = "🟡 $cantidad pacas - $hora (pendiente)\n" + tvHistorialPacas.text
                etCantidadPacas.text.clear()
                btnEnviarPallet.isEnabled = true
                return@launch
            }

            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.registrarPallet(datos)
                }
                if (resp.isSuccessful) {
                    tvHistorialPacas.text = "🟢 $cantidad pacas - $hora\n" + tvHistorialPacas.text
                    etCantidadPacas.text.clear()
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No se pudo registrar la paca")
                        .setMessage(extraerDetalleErrorHttp(resp))
                        .setPositiveButton("Entendido", null)
                        .show()
                }
            } catch (e: Exception) {
                ErrorReporter.warn("MainActivity", "Sin red al registrar paca, encolando: ${e.message}")
                colaRepo.enqueueRegistrarPallet(datos)
                tvHistorialPacas.text = "🟡 $cantidad pacas - $hora (pendiente)\n" + tvHistorialPacas.text
                etCantidadPacas.text.clear()
            } finally {
                btnEnviarPallet.isEnabled = true
            }
        }
    }

    private fun llamarApiFinalizarTurno(datos: FinalizarTurnoRequest) {
        btnTerminarTurno.isEnabled = false

        lifecycleScope.launch {
            // Sesión sin id real: solo podemos encolar. El bloqueo de cascada en
            // SyncWorker lo manejará si iniciar_turno quedó fatal.
            if (sesionActualId == -99) {
                colaRepo.enqueueFinalizarTurno(datos)
                tvHistorialPacas.text = ""
                forzarReinicioPorError("Turno enviado a cola — se cerrará al conectar")
                btnTerminarTurno.isEnabled = true
                return@launch
            }

            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.finalizarTurno(datos)
                }
                if (resp.isSuccessful) {
                    tvHistorialPacas.text = ""
                    forzarReinicioPorError("Turno finalizado")
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No se pudo finalizar el turno")
                        .setMessage(extraerDetalleErrorHttp(resp))
                        .setPositiveButton("Entendido", null)
                        .show()
                    btnTerminarTurno.isEnabled = true
                }
            } catch (e: Exception) {
                ErrorReporter.warn("MainActivity", "Sin red al finalizar turno, encolando: ${e.message}")
                colaRepo.enqueueFinalizarTurno(datos)
                tvHistorialPacas.text = ""
                forzarReinicioPorError("Sin red — turno guardado, se cerrará al reconectar")
                btnTerminarTurno.isEnabled = true
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connectivityObserver.register()
        tabletsWsManager.connect()
        colaRepo.dispararSync()
    }

    override fun onStop() {
        super.onStop()
        connectivityObserver.unregister()
        tabletsWsManager.disconnect()
    }

    private fun observarBadge() {
        lifecycleScope.launch {
            colaRepo.operacionesFlow.collectLatest { lista ->
                val count = lista.count { !it.fatal }
                tvBadge.text = count.toString()
                val hasNetwork = (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                    .activeNetworkInfo?.isConnected == true
                val color = when {
                    !hasNetwork -> "#F44336"
                    count > 0   -> "#FF9800"
                    else        -> "#4CAF50"
                }
                tvBadge.background.mutate().also {
                    (it as? android.graphics.drawable.GradientDrawable)?.setColor(android.graphics.Color.parseColor(color))
                }
                tvBadge.setOnClickListener {
                    startActivity(Intent(this@MainActivity, PendientesActivity::class.java))
                }
            }
        }
    }

    private fun forzarReinicioPorError(mensaje: String) {
        Toast.makeText(this@MainActivity, mensaje, Toast.LENGTH_LONG).show()
        sesionActualId = null
        getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE).edit().remove("sesion_activa").apply()
        etCantidadPacas.text.clear()
        spinnerTipo.setSelection(0)
        bloquearSpinnersDesdeMaquina()
        limpiarEstadoParo()
        seccionProduccion.visibility    = View.GONE
        seccionConfiguracion.visibility = View.VISIBLE
    }


    // ==========================================
    // CACHÉ LOCAL DE CATÁLOGOS
    // ==========================================

    private fun guardarCatalogosEnCache(
        operadores: List<String>,
        maquinas: List<String>,
        jerarquia: Map<String, List<MarcaResponse>>
    ) {
        val gson = Gson()
        getSharedPreferences("SIGEP_CATALOGOS", MODE_PRIVATE).edit().apply {
            putString("cache_operadores", gson.toJson(operadores))
            putString("cache_maquinas", gson.toJson(maquinas))
            if (jerarquia.isNotEmpty()) {
                putString("cache_jerarquia_maquinas", gson.toJson(jerarquia))
            }
            apply()
        }
    }

    private fun cargarCatalogosDesdeCache() {
        val prefs = getSharedPreferences("SIGEP_CATALOGOS", MODE_PRIVATE)
        val gson  = Gson()
        val tipoLista = object : TypeToken<List<String>>() {}.type

        val jsonOp  = prefs.getString("cache_operadores", null)
        val jsonMaq = prefs.getString("cache_maquinas", null)
        if (jsonOp != null && jsonMaq != null) {
            listaOperadoresServidor = gson.fromJson(jsonOp, tipoLista)
            listaMaquinasServidor   = gson.fromJson(jsonMaq, tipoLista)
        }

        val jsonJerarquia = prefs.getString("cache_jerarquia_maquinas", null)
        if (jsonJerarquia != null) {
            val tipoJerarquia = object : TypeToken<Map<String, List<MarcaResponse>>>() {}.type
            jerarquiaMaquinas = gson.fromJson(jsonJerarquia, tipoJerarquia) ?: emptyMap()
        }
    }

    // ==========================================
    // PAROS
    // ==========================================

    private fun llamarApiIniciarParo(motivo: String) {
        btnParo.isEnabled = false
        val req = IniciarParoRequest(sesion_id = sesionActualId!!, motivo = motivo)

        // Helper local para aplicar el cambio visual del paro activo, una vez que
        // sabemos que el server lo aceptó (o que lo encolamos para reintentar).
        fun aplicarParoActivo(mensaje: String) {
            paroActivo = true
            btnParo.text = "REANUDAR OPERACIÓN"
            btnParo.setBackgroundColor(Color.parseColor("#4CAF50"))
            btnEnviarPallet.isEnabled = false
            btnEnviarPallet.setBackgroundColor(Color.GRAY)
            Toast.makeText(this@MainActivity, mensaje, Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            if (sesionActualId == -99) {
                colaRepo.enqueueIniciarParo(req)
                aplicarParoActivo("Paro en cola")
                btnParo.isEnabled = true
                return@launch
            }

            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.iniciarParo(req)
                }
                if (resp.isSuccessful) {
                    aplicarParoActivo("Paro registrado")
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No se pudo registrar el paro")
                        .setMessage(extraerDetalleErrorHttp(resp))
                        .setPositiveButton("Entendido", null)
                        .show()
                }
            } catch (e: Exception) {
                ErrorReporter.warn("MainActivity", "Sin red al iniciar paro, encolando: ${e.message}")
                colaRepo.enqueueIniciarParo(req)
                aplicarParoActivo("Sin red — paro guardado, se enviará al reconectar")
            } finally {
                btnParo.isEnabled = true
            }
        }
    }

    private fun llamarApiFinalizarParo() {
        btnParo.isEnabled = false
        val req = FinalizarParoRequest(sesion_id = sesionActualId!!)

        fun aplicarReanudacion(mensaje: String) {
            paroActivo = false
            btnParo.text = "REGISTRAR PARO"
            btnParo.setBackgroundColor(Color.parseColor("#FA8400"))
            btnEnviarPallet.isEnabled = true
            btnEnviarPallet.setBackgroundColor(Color.parseColor("#2196F3"))
            Toast.makeText(this@MainActivity, mensaje, Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            if (sesionActualId == -99) {
                colaRepo.enqueueFinalizarParo(req)
                aplicarReanudacion("Reanudación en cola")
                btnParo.isEnabled = true
                return@launch
            }

            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.finalizarParo(req)
                }
                if (resp.isSuccessful) {
                    aplicarReanudacion("Producción reanudada")
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No se pudo finalizar el paro")
                        .setMessage(extraerDetalleErrorHttp(resp))
                        .setPositiveButton("Entendido", null)
                        .show()
                }
            } catch (e: Exception) {
                ErrorReporter.warn("MainActivity", "Sin red al finalizar paro, encolando: ${e.message}")
                colaRepo.enqueueFinalizarParo(req)
                aplicarReanudacion("Sin red — reanudación guardada, se enviará al reconectar")
            } finally {
                btnParo.isEnabled = true
            }
        }
    }

    private fun limpiarEstadoParo() {
        paroActivo = false
        btnParo.text = "REGISTRAR PARO"
        btnParo.setBackgroundColor(Color.parseColor("#FA8400"))
        btnParo.isEnabled = true
        btnEnviarPallet.isEnabled = true
        btnEnviarPallet.setBackgroundColor(Color.parseColor("#2196F3"))
    }

    // ==========================================
    // HISTORIAL Y MÓDULO DE INSUMOS
    // ==========================================

    private fun recuperarHistorial(idSesion: Int) {
        tvHistorialPacas.text = "Cargando historial..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val respuesta = RetrofitClient.apiService.obtenerHistorialSesion(idSesion)
                withContext(Dispatchers.Main) {
                    var texto = ""
                    if (respuesta.isSuccessful && respuesta.body() != null) {
                        for (p in respuesta.body()!!) {
                            texto += "🟢 ${p.cantidad_pacas} pacas - ${p.hora}\n"
                        }
                    }
                    tvHistorialPacas.text = if (texto.isEmpty()) "No hay registros aún." else texto
                }
            } catch (e: Exception) {
                ErrorReporter.report("MainActivity", "Fallo al cargar historial de sesión", e)
                withContext(Dispatchers.Main) {
                    tvHistorialPacas.text = "Sin red para cargar historial anterior."
                }
            }
        }
    }

    private fun abrirModalInsumosInteligente() {
        val contenedor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val tituloSpinner = TextView(this).apply {
            text = "Insumo Requerido:"
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        contenedor.addView(tituloSpinner)

        val spinnerInsumos = Spinner(this)
        val maquinaActual = spinnerMaquina.selectedItem.toString()
        // La Máquina 7B usa sacos tejidos; las demás usan lámina en rollo.
        val opcionesInsumo = if (maquinaActual == "Máquina 7B") {
            mutableListOf("Sacos Tejidos", "Materia Prima (Granel)")
        } else {
            mutableListOf("Lámina (Rollo)", "Materia Prima (Granel)")
        }
        spinnerInsumos.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opcionesInsumo)
        spinnerInsumos.setPadding(0, 0, 0, 30)
        contenedor.addView(spinnerInsumos)

        val tituloCantidad = TextView(this).apply {
            text = "Cantidad a solicitar:"
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 20, 0, 0)
        }
        contenedor.addView(tituloCantidad)

        val inputCantidad = EditText(this).apply {
            hint = "Ej: 2"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        contenedor.addView(inputCantidad)

        AlertDialog.Builder(this)
            .setTitle("Solicitar a Bodega")
            .setView(contenedor)
            .setPositiveButton("Enviar Pedido") { _, _ ->
                val cantidad = inputCantidad.text.toString().toIntOrNull()
                if (cantidad != null && cantidad > 0) {
                    val seleccion = spinnerInsumos.selectedItem.toString()
                    val marca = spinnerMarca.selectedItem.toString()
                    val fragancia = spinnerFragancia.selectedItem.toString()
                    val presentacion = spinnerPresentacion.selectedItem.toString()

                    // El texto armado describe exactamente qué producto necesita el operario,
                    // sin requerir cambios en el modelo de base de datos.
                    val textoArmado = if (seleccion.contains("Lámina") || seleccion.contains("Sacos")) {
                        "$seleccion - $marca - $fragancia - $presentacion"
                    } else {
                        "Preten $marca $fragancia (Granel)"
                    }

                    enviarPeticionInsumo(SolicitarInsumoRequest(sesionActualId!!, textoArmado, cantidad))
                } else {
                    Toast.makeText(this, "Debe ingresar una cantidad válida", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarPeticionInsumo(solicitud: SolicitarInsumoRequest) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val respuesta = RetrofitClient.apiService.solicitarInsumo(solicitud)
                withContext(Dispatchers.Main) {
                    if (respuesta.isSuccessful) {
                        Toast.makeText(this@MainActivity, "Pedido enviado al Insumista", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Error al enviar el pedido", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.report("MainActivity", "Fallo al enviar pedido de insumo", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error de red. Intente de nuevo.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
