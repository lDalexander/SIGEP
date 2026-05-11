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

class MainActivity : AppCompatActivity() {

    private var sesionActualId: Int? = null
    // El token de idempotencia evita que el servidor registre el mismo turno dos veces
    // si el usuario presiona el botón mientras la primera petición está en vuelo.
    private var tokenIdempotencia: String = ""
    private lateinit var btnSolicitarInsumo: Button
    private lateinit var btnParo: Button

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

    val tipos = arrayOf("-- Seleccione Tipo --", "Sólido", "Líquido")
    val fragancias = arrayOf("-- Seleccione Fragancia --", "Limón", "Floral")

    private var listaOperadoresServidor: List<String> = emptyList()
    private var listaMaquinasServidor: List<String> = emptyList()

    // Jerarquía dinámica descargada con la lista de máquinas.
    // Cuando el backend incluya `marcas` en api/maquinas, este mapa reemplaza los locales.
    private var jerarquiaMaquinas: Map<String, List<MarcaResponse>> = emptyMap()

    // Fallback local hasta que el backend devuelva la jerarquía en api/maquinas.
    // Eliminar estos mapas cuando el servidor implemente el campo `marcas` en MaquinaResponse.
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
        vincularVistas()

        rvPendientesConfirmacion.layoutManager = LinearLayoutManager(this)
        adapterPendientesConfirmacion = PedidoAdapter(emptyList(), esAsignado = false, esConfirmacion = true) { pedido ->
            confirmarRecepcion(pedido.id)
        }
        rvPendientesConfirmacion.adapter = adapterPendientesConfirmacion
        rvPendientesConfirmacion.isNestedScrollingEnabled = false

        // Token de idempotencia generado en cada sesión para evitar turnos duplicados
        // si el operario presiona el botón mientras la petición está en vuelo.
        tokenIdempotencia = java.util.UUID.randomUUID().toString()

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
        val sesionGuardada  = prefs.getInt("sesion_activa", -1)
        val timestampInicio = prefs.getLong("hora_inicio_ms", 0L)

        if (sesionGuardada != -1) {
            val horasTranscurridas = (System.currentTimeMillis() - timestampInicio) / (1000 * 60 * 60)

            if (horasTranscurridas >= 9) {
                // Límite de 9h: evita que un turno del día anterior quede activo indefinidamente
                // si el operario olvidó cerrarlo al final de su jornada.
                forzarReinicioPorError("Turno anterior caducado (límite de 9h). Inicie uno nuevo.")
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
                            val (emoji, estadoTexto) = when (p.estado) {
                                "PENDIENTE"           -> "🕐" to "Pendiente de atención"
                                "EN_CAMINO"           -> "🚶" to "En camino"
                                "ENTREGADO_INSUMISTA" -> "⏳" to "Entregado — esperando tu confirmación"
                                "ENTREGADO"           -> "✅" to "Completado"
                                else                  -> "❓" to p.estado
                            }
                            sb.append("━━━━━━━━━━━━━━━━━━━━\n")
                            sb.append("📦 ${p.cantidad}x ${p.detalle}\n")
                            sb.append("⏰ Solicitado: ${p.hora_solicitud}\n")
                            sb.append("$emoji $estadoTexto\n")
                            if (p.insumista != null) {
                                sb.append("👤 Insumista: ${p.insumista}")
                                if (p.hora_aceptacion != null) sb.append("  (aceptó: ${p.hora_aceptacion})")
                                sb.append("\n")
                            }
                            if (p.hora_entrega != null) {
                                sb.append("📬 Insumista entregó: ${p.hora_entrega}\n")
                            }
                            sb.append("\n")
                        }
                        tvContenido.text = sb.toString().trimEnd()
                    }
                } else {
                    // Endpoint detallado aún no disponible en el backend — usar resumen básico
                    val respBasico = RetrofitClient.apiService.obtenerEstadoPedidos(sesionActualId!!)
                    withContext(Dispatchers.Main) {
                        if (respBasico.isSuccessful && respBasico.body() != null) {
                            val d = respBasico.body()!!
                            tvContenido.text = if (d.pendientes == 0 && d.en_camino == 0 && d.entregados == 0) {
                                "No has realizado ninguna solicitud en este turno."
                            } else {
                                "Solicitados:  ${d.pendientes + d.en_camino + d.entregados}\n" +
                                "Pendientes:   ${d.pendientes}\n" +
                                "En Camino:    ${d.en_camino}\n" +
                                "Entregados:   ${d.entregados}"
                            }
                        } else {
                            tvContenido.text = "Error al obtener los datos.\nIntente de nuevo."
                        }
                    }
                }
            } catch (e: Exception) {
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
                    // Falla silenciosa: el radar es informativo, no debe interrumpir al operario
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
            } catch (e: Exception) { /* silencioso */ }
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
                        spinnerMaquina.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, listaMaquinasServidor)
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

                    spinnerOperador.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, listaOperadoresServidor)
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

            llamarApiIniciarTurno(
                IniciarTurnoRequest(
                    tipo        = spinnerTipo.selectedItem.toString(),
                    maquina     = spinnerMaquina.selectedItem.toString(),
                    operador    = spinnerOperador.selectedItem.toString(),
                    marca       = spinnerMarca.selectedItem.toString(),
                    presentacion = spinnerPresentacion.selectedItem.toString(),
                    fragancia   = spinnerFragancia.selectedItem.toString(),
                    request_id  = tokenIdempotencia
                )
            )
        }

        btnEnviarPallet.setOnClickListener {
            val cantidad = etCantidadPacas.text.toString().toIntOrNull()
            if (cantidad == null || cantidad <= 0) {
                Toast.makeText(this, "Ingrese una cantidad válida", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (sesionActualId == -99) {
                // Modo offline: guardamos en cola local y disparamos la sincronización
                // para que en cuanto vuelva la red se cree el turno real y se envíe la paca.
                val datosTemporal = RegistrarPalletRequest(sesionActualId!!, cantidad)
                guardarPacaPendiente(datosTemporal)
                val horaActual = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                tvHistorialPacas.text = "🟡 ${cantidad} pacas - $horaActual (Creando turno...)\n" + tvHistorialPacas.text
                etCantidadPacas.text.clear()
                Toast.makeText(this, "Reconectando y creando turno...", Toast.LENGTH_SHORT).show()
                iniciarSincronizacion()
            } else if (sesionActualId != null) {
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
                val pendientes = obtenerPendientes()
                if (pendientes.isNotEmpty()) {
                    // Sincronizamos antes de cerrar para no perder pacas registradas sin conexión.
                    Toast.makeText(this, "Sincronizando pacas pendientes antes de cerrar...", Toast.LENGTH_LONG).show()
                    iniciarSincronizacion {
                        llamarApiFinalizarTurno(FinalizarTurnoRequest(sesionActualId!!))
                    }
                } else {
                    llamarApiFinalizarTurno(FinalizarTurnoRequest(sesionActualId!!))
                }
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
    }

    // ==========================================
    // LLAMADAS A LA API
    // ==========================================

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
                    }
                }
            } catch (e: Exception) {
                Log.d("SIGEP", "No se pudo actualizar el catálogo — usando caché local")
            }
        }
    }

    private fun llamarApiIniciarTurno(datos: IniciarTurnoRequest) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val respuesta = RetrofitClient.apiService.iniciarTurno(datos)
                withContext(Dispatchers.Main) {
                    if (respuesta.isSuccessful && respuesta.body() != null) {
                        val idRecibido = respuesta.body()!!.sesion_id
                        getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE).edit().apply {
                            putInt("sesion_activa", idRecibido)
                            putLong("hora_inicio_ms", System.currentTimeMillis())
                            apply()
                        }
                        sesionActualId = idRecibido
                        Toast.makeText(this@MainActivity, "Turno Iniciado con Éxito", Toast.LENGTH_SHORT).show()
                        seccionConfiguracion.visibility = View.GONE
                        seccionProduccion.visibility    = View.VISIBLE
                        iniciarRadarStatusPedidos(idRecibido)
                    } else {
                        btnIniciarTurno.isEnabled = true
                        btnIniciarTurno.text = "INICIAR TURNO"
                        // Renovamos el token para que el reintento no sea bloqueado por idempotencia.
                        tokenIdempotencia = java.util.UUID.randomUUID().toString()
                        val codigo = respuesta.code()
                        val cuerpoError = respuesta.errorBody()?.string()
                        val mensaje = when (codigo) {
                            400  -> cuerpoError ?: "Datos inválidos o turno duplicado."
                            500  -> "Error 500: el servidor respondió con error interno."
                            else -> "Error $codigo al iniciar en servidor"
                        }
                        Log.e("SIGEP_API", "Error al iniciar turno ($codigo): $cuerpoError")
                        Toast.makeText(this@MainActivity, mensaje, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnIniciarTurno.isEnabled = true
                    btnIniciarTurno.text = "INICIAR TURNO"
                    tokenIdempotencia = java.util.UUID.randomUUID().toString()
                    guardarTurnoOffline(datos)
                    sesionActualId = -99
                    seccionConfiguracion.visibility = View.GONE
                    seccionProduccion.visibility    = View.VISIBLE
                    Toast.makeText(this@MainActivity, "Modo Offline: turno iniciado localmente", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun llamarApiRegistrarPallet(datos: RegistrarPalletRequest) {
        btnEnviarPallet.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val respuesta = RetrofitClient.apiService.registrarPallet(datos)
                withContext(Dispatchers.Main) {
                    if (respuesta.isSuccessful) {
                        Toast.makeText(this@MainActivity, "Registro exitoso", Toast.LENGTH_SHORT).show()
                        etCantidadPacas.text.clear()
                        val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        tvHistorialPacas.text = "🟢 ${datos.cantidad_pacas} pacas - $hora\n" + tvHistorialPacas.text
                        iniciarSincronizacion()
                    } else {
                        forzarReinicioPorError("Sesión inválida en el servidor.")
                    }
                    btnEnviarPallet.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    guardarPacaPendiente(datos)
                    val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    tvHistorialPacas.text = "🟡 ${datos.cantidad_pacas} pacas - $hora (PENDIENTE)\n" + tvHistorialPacas.text
                    Toast.makeText(this@MainActivity, "Sin red. Paca guardada en cola local.", Toast.LENGTH_LONG).show()
                    etCantidadPacas.text.clear()
                    btnEnviarPallet.isEnabled = true
                }
            }
        }
    }

    private fun llamarApiFinalizarTurno(datos: FinalizarTurnoRequest) {
        btnTerminarTurno.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val respuesta = RetrofitClient.apiService.finalizarTurno(datos)
                withContext(Dispatchers.Main) {
                    if (respuesta.isSuccessful) {
                        tvHistorialPacas.text = ""
                        forzarReinicioPorError("Turno Cerrado Correctamente")
                    } else {
                        forzarReinicioPorError("No se encontró este turno.")
                    }
                    btnTerminarTurno.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error de conexión.", Toast.LENGTH_LONG).show()
                    btnTerminarTurno.isEnabled = true
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
    // COLA OFFLINE
    // ==========================================

    private fun guardarPacaPendiente(datos: RegistrarPalletRequest) {
        val prefs = getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE)
        val lista = obtenerPendientes()
        lista.add(datos)
        prefs.edit().putString("cola_pendientes", Gson().toJson(lista)).apply()
    }

    private fun obtenerPendientes(): MutableList<RegistrarPalletRequest> {
        val prefs = getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE)
        val json  = prefs.getString("cola_pendientes", "[]")
        val tipo  = object : TypeToken<MutableList<RegistrarPalletRequest>>() {}.type
        return Gson().fromJson(json, tipo) ?: mutableListOf()
    }

    private fun guardarListaActualizada(lista: List<RegistrarPalletRequest>) {
        getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE)
            .edit().putString("cola_pendientes", Gson().toJson(lista)).apply()
    }

    private fun guardarTurnoOffline(solicitud: IniciarTurnoRequest) {
        getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE)
            .edit().putString("turno_pendiente_crear", Gson().toJson(solicitud)).apply()
    }

    private fun obtenerTurnoPendiente(): IniciarTurnoRequest? {
        val json = getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE)
            .getString("turno_pendiente_crear", null) ?: return null
        return Gson().fromJson(json, IniciarTurnoRequest::class.java)
    }

    private fun actualizarIdEnPalletsPendientes(nuevoId: Int) {
        val lista = obtenerPendientes()
        lista.forEach { it.sesion_id = nuevoId }
        guardarListaActualizada(lista)
    }

    // Sincronización en dos fases: primero crea el turno si no existe en servidor,
    // luego envía los pallets uno a uno hasta vaciar la cola.
    private fun iniciarSincronizacion(alFinalizar: () -> Unit = {}) {
        val turnoPendiente = obtenerTurnoPendiente()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (turnoPendiente != null) {
                    val resTurno = RetrofitClient.apiService.iniciarTurno(turnoPendiente)
                    if (resTurno.isSuccessful) {
                        val nuevoId = resTurno.body()?.sesion_id ?: -1
                        sesionActualId = nuevoId
                        getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE)
                            .edit().remove("turno_pendiente_crear").apply()
                        actualizarIdEnPalletsPendientes(nuevoId)
                        withContext(Dispatchers.Main) { iniciarSincronizacion(alFinalizar) }
                        return@launch
                    }
                }

                val pendientes = obtenerPendientes()
                if (pendientes.isEmpty()) {
                    withContext(Dispatchers.Main) { alFinalizar() }
                    return@launch
                }

                val pacaActual = pendientes.first()
                val respuesta  = RetrofitClient.apiService.registrarPallet(pacaActual)
                if (respuesta.isSuccessful) {
                    pendientes.removeAt(0)
                    guardarListaActualizada(pendientes)
                    withContext(Dispatchers.Main) {
                        actualizarHistorialVisual(pacaActual.cantidad_pacas)
                        iniciarSincronizacion(alFinalizar)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { alFinalizar() }
            }
        }
    }

    private fun actualizarHistorialVisual(cantidad: Int) {
        val regex = Regex("🟡 $cantidad pacas(.*?)(?:\\(PENDIENTE\\)|\\(Creando turno...\\))")
        tvHistorialPacas.text = tvHistorialPacas.text.toString()
            .replaceFirst(regex, "🟢 $cantidad pacas\$1(Sincronizado)")
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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val solicitud = IniciarParoRequest(sesion_id = sesionActualId!!, motivo = motivo)
                val respuesta = RetrofitClient.apiService.iniciarParo(solicitud)
                withContext(Dispatchers.Main) {
                    if (respuesta.isSuccessful) {
                        paroActivo = true
                        btnParo.text = "REANUDAR OPERACIÓN"
                        btnParo.setBackgroundColor(Color.parseColor("#4CAF50"))
                        btnEnviarPallet.isEnabled = false
                        btnEnviarPallet.setBackgroundColor(Color.GRAY)
                        Toast.makeText(this@MainActivity, "Paro Registrado", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Error del servidor al registrar paro", Toast.LENGTH_SHORT).show()
                    }
                    btnParo.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error de conexión. Intente de nuevo.", Toast.LENGTH_SHORT).show()
                    btnParo.isEnabled = true
                }
            }
        }
    }

    private fun llamarApiFinalizarParo() {
        btnParo.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val solicitud = FinalizarParoRequest(sesion_id = sesionActualId!!)
                val respuesta = RetrofitClient.apiService.finalizarParo(solicitud)
                withContext(Dispatchers.Main) {
                    if (respuesta.isSuccessful) {
                        paroActivo = false
                        btnParo.text = "REGISTRAR PARO"
                        btnParo.setBackgroundColor(Color.parseColor("#FA8400"))
                        btnEnviarPallet.isEnabled = true
                        btnEnviarPallet.setBackgroundColor(Color.parseColor("#2196F3"))
                        Toast.makeText(this@MainActivity, "Operación Reanudada", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Error del servidor al reanudar", Toast.LENGTH_SHORT).show()
                    }
                    btnParo.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error de conexión. Intente de nuevo.", Toast.LENGTH_SHORT).show()
                    btnParo.isEnabled = true
                }
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
                    val pendientesDeEstaSesion = obtenerPendientes().filter { it.sesion_id == idSesion }
                    for (p in pendientesDeEstaSesion) {
                        val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        texto += "🟡 ${p.cantidad_pacas} pacas - $hora (PENDIENTE)\n"
                    }
                    if (respuesta.isSuccessful && respuesta.body() != null) {
                        for (p in respuesta.body()!!) {
                            texto += "🟢 ${p.cantidad_pacas} pacas - ${p.hora}\n"
                        }
                    }
                    tvHistorialPacas.text = if (texto.isEmpty()) "No hay registros aún." else texto
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val pendientes = obtenerPendientes().filter { it.sesion_id == idSesion }
                    tvHistorialPacas.text = if (pendientes.isNotEmpty()) {
                        "Sin red:\n" + pendientes.joinToString("") { "🟡 ${it.cantidad_pacas} pacas (PENDIENTE)\n" }
                    } else {
                        "Sin red para cargar historial anterior."
                    }
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
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error de red. Intente de nuevo.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
