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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : AppCompatActivity() {

    private var sesionActualId: Int? = null
    private lateinit var btnSolicitarInsumo: Button
    private val PREFS_NAME = "DetcuadorPrefs"
    private lateinit var btnParo: Button
    // Layouts y Vistas
    private var isParoActivo = false
    private var tiempoInicioParo: Long = 0
    private lateinit var seccionConfiguracion: LinearLayout
    private lateinit var seccionProduccion: LinearLayout
    private lateinit var etCantidadPacas: EditText
    private lateinit var btnIniciarTurno: Button
    private lateinit var btnEnviarPallet: Button

    private lateinit var tvHistorialPacas: TextView
    private lateinit var btnTerminarTurno: Button

    // Spinners
    private lateinit var spinnerTipo: Spinner
    private lateinit var spinnerMaquina: Spinner
    private lateinit var spinnerOperador: Spinner
    private lateinit var spinnerMarca: Spinner
    private lateinit var spinnerPresentacion: Spinner
    private lateinit var spinnerFragancia: Spinner

    // --- DICCIONARIOS DE DATOS ---
    val tipos = arrayOf("-- Seleccione Tipo --", "Sólido", "Líquido")

    // Variables dinámicas (Se llenan desde MySQL)
    private var listaOperadoresServidor: List<String> = emptyList()
    private var listaMaquinasServidor: List<String> = emptyList()

    // Fragancias globales
    val fragancias = arrayOf("-- Seleccione Fragancia --", "Limón", "Floral")

    // Marcas según la Máquina
    private val marcasPorMaquina = mapOf(
        "Máquina 7" to arrayOf("-- Seleccione Marca --", "TORBELLINO", "ULTREX", "COMISARIATO"),
        "Máquina 7B" to arrayOf("-- Seleccione Marca --", "ULTREX", "PQP", "COMISARIATO", "HIT"),
        "Máquina 8" to arrayOf("-- Seleccione Marca --", "ULTREX", "HIT"),
        "Máquina 9" to arrayOf("-- Seleccione Marca --", "TORBELLINO", "ULTREX", "COMISARIATO"),
        "Máquina 16" to arrayOf("-- Seleccione Marca --", "Sin Asignar")
    )

    // Presentaciones según "Máquina + Marca"
    private val presentacionesPorMaquinaMarca = mapOf(
        "Máquina 7|ULTREX" to arrayOf("-- Seleccione Presentación --", "1 KG", "2 KG", "3 KG"),
        "Máquina 7|TORBELLINO" to arrayOf("-- Seleccione Presentación --", "1 KG"),
        "Máquina 7|COMISARIATO" to arrayOf("-- Seleccione Presentación --", "1.2 KG"),
        "Máquina 8|ULTREX" to arrayOf("-- Seleccione Presentación --", "100 GR", "250 GR", "500 GR"),
        "Máquina 8|HIT" to arrayOf("-- Seleccione Presentación --", "360 GR"),
        "Máquina 7B|PQP" to arrayOf("-- Seleccione Presentación --", "15 KG", "25 KG"),
        "Máquina 7B|HIT" to arrayOf("-- Seleccione Presentación --", "5 KG"),
        "Máquina 7B|ULTREX" to arrayOf("-- Seleccione Presentación --", "5 KG"),
        "Máquina 7B|COMISARIATO" to arrayOf("-- Seleccione Presentación --", "5 KG"),
        "Máquina 9|TORBELLINO" to arrayOf("-- Seleccione Presentación --", "1 KG"),
        "Máquina 9|ULTREX" to arrayOf("-- Seleccione Presentación --", "1 KG"),
        "Máquina 9|COMISARIATO" to arrayOf("-- Seleccione Presentación --", "1.2 KG")
    )
    private lateinit var tvStatusPedidos: TextView
    private lateinit var btnVerEstadoPedidos: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. CONECTAR LOS CABLES (Vincular vistas) ANTES QUE NADA
        tvHistorialPacas = findViewById(R.id.tvHistorialPacas)
        btnVerEstadoPedidos = findViewById(R.id.btnVerEstadoPedidos) // ✅ Movido arriba
        vincularVistas()

        // 2. CONFIGURACIONES
        cargarCatalogosDesdeCache()
        configurarFiltrosEnCascada()
        configurarBotones()

        // 3. 🔥 EL ESCUCHADOR DEL NUEVO BOTÓN (El que faltaba)
        btnVerEstadoPedidos.setOnClickListener {
            if (sesionActualId != null && sesionActualId != -1) {
                mostrarVentanaEstadoPedidos()
            } else {
                Toast.makeText(this, "⚠️ Debes iniciar turno primero", Toast.LENGTH_SHORT).show()
            }
        }

        // 4. LLAMADAS AL SERVIDOR
        descargarCatalogosDesdeServidor()

        // --- RECUPERAR SESIÓN Y VALIDAR TIEMPO (TTL) ---
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val sesionGuardada = prefs.getInt("sesion_activa", -1)
        val timestampInicio = prefs.getLong("hora_inicio_ms", 0L)

        if (sesionGuardada != -1) {
            val tiempoActual = System.currentTimeMillis()
            val diferenciaMs = tiempoActual - timestampInicio
            val horasTranscurridas = diferenciaMs / (1000 * 60 * 60)

            if (horasTranscurridas >= 9) {
                forzarReinicioPorError("Turno anterior caducado (Límite de 9h). Inicie uno nuevo.")
            } else {
                // EL TURNO ES VÁLIDO: Restauramos la UI
                sesionActualId = sesionGuardada
                seccionConfiguracion.visibility = View.GONE
                seccionProduccion.visibility = View.VISIBLE
                Toast.makeText(this, "Turno recuperado", Toast.LENGTH_SHORT).show()

                // ==========================================
                // RECUPERAMOS EL HISTORIAL VISUAL
                // ==========================================
                recuperarHistorial(sesionGuardada)
            }
        }
    }
    private fun mostrarVentanaEstadoPedidos() {
        // 1. Creamos la ventana emergente básica
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("📊 Estado de tus Pedidos")
        builder.setMessage("Consultando al servidor...")
        builder.setPositiveButton("CERRAR", null)

        val dialog = builder.create()
        dialog.show()

        // 2. Buscamos los datos en el servidor
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.apiService.obtenerEstadoPedidos(sesionActualId!!)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val datos = response.body()!!

                        if (datos.pendientes == 0 && datos.en_camino == 0 && datos.entregados == 0) {
                            dialog.setMessage("No has realizado ninguna solicitud en este turno.")
                        } else {
                            // Formateamos el texto para que se vea bonito en el popup
                            val mensaje = """
                            ⏳ Pendientes: ${datos.pendientes}
                            🚚 En Camino: ${datos.en_camino}
                            ✅ Entregados: ${datos.entregados}
                        """.trimIndent()

                            dialog.setMessage(mensaje)
                        }
                    } else {
                        dialog.setMessage("Error al obtener los datos.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.setMessage("Error de conexión. Revisa tu red.")
                }
            }
        }
    }
    private fun iniciarRadarStatusPedidos(sesionId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            // El radar se mantiene vivo mientras el Activity exista
            while (isActive) {
                try {
                    val response = RetrofitClient.apiService.obtenerEstadoPedidos(sesionId)
                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful && response.body() != null) {
                            val datos = response.body()!!

                            if (datos.pendientes == 0 && datos.en_camino == 0 && datos.entregados == 0) {
                                tvStatusPedidos.text = "Sin solicitudes aún"
                            } else {
                                // Actualización visual del tablero
                                tvStatusPedidos.text = "⏳ Pendientes: ${datos.pendientes}\n" +
                                        "🚚 En Camino: ${datos.en_camino}\n" +
                                        "✅ Entregados: ${datos.entregados}"
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Error silencioso para no interrumpir al operario
                }
                delay(5000) // Consulta cada 5 segundos
            }
        }
    }
    private fun vincularVistas() {
        btnSolicitarInsumo = findViewById(R.id.btnSolicitarInsumo)
        seccionConfiguracion = findViewById(R.id.seccionConfiguracion)
        seccionProduccion = findViewById(R.id.seccionProduccion)

        spinnerTipo = findViewById(R.id.spinnerTipo)
        spinnerMaquina = findViewById(R.id.spinnerMaquina)
        spinnerOperador = findViewById(R.id.spinnerOperador)
        spinnerMarca = findViewById(R.id.spinnerMarca)
        spinnerPresentacion = findViewById(R.id.spinnerPresentacion)
        spinnerFragancia = findViewById(R.id.spinnerFragancia)

        etCantidadPacas = findViewById(R.id.etCantidadPacas)
        btnIniciarTurno = findViewById(R.id.btnIniciarTurno)
        btnEnviarPallet = findViewById(R.id.btnEnviarPallet)
        btnTerminarTurno = findViewById(R.id.btnTerminarTurno)
        btnParo = findViewById(R.id.btnParo)
    }

    private fun configurarFiltrosEnCascada() {
        spinnerTipo.adapter = ArrayAdapter(this, R.layout.spinner_item, tipos)
        bloquearSpinnersDesdeMaquina()

        // EVENTO 1: Cuando cambian TIPO
        spinnerTipo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    val tipoSeleccionado = tipos[position]
                    if (tipoSeleccionado == "Sólido") {
                        // USAMOS LOS DATOS DEL SERVIDOR
                        if (listaMaquinasServidor.isEmpty()) {
                            Toast.makeText(this@MainActivity, "Aún descargando máquinas...", Toast.LENGTH_SHORT).show()
                        }
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

        // EVENTO 2: Cuando cambian MAQUINA
        spinnerMaquina.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    val maquinaSeleccionada = spinnerMaquina.selectedItem.toString()

                    // USAMOS LOS DATOS DEL SERVIDOR PARA OPERADORES
                    spinnerOperador.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, listaOperadoresServidor)
                    spinnerOperador.isEnabled = true

                    spinnerFragancia.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, fragancias)
                    spinnerFragancia.isEnabled = true

                    val marcas = marcasPorMaquina[maquinaSeleccionada] ?: arrayOf("-- Error --")
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

        // EVENTO 3: Cuando cambian MARCA
        spinnerMarca.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    val maquinaActual = spinnerMaquina.selectedItem.toString()
                    val marcaActual = spinnerMarca.selectedItem.toString()
                    val llaveBuscadora = "$maquinaActual|$marcaActual"
                    val presentaciones = presentacionesPorMaquinaMarca[llaveBuscadora] ?: arrayOf("-- Sin Configurar --")

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
        spinnerOperador.adapter = ArrayAdapter(this, R.layout.spinner_item, arrayOf("-- Bloqueado --"))
        spinnerOperador.isEnabled = false
        spinnerMarca.adapter = ArrayAdapter(this, R.layout.spinner_item, arrayOf("-- Bloqueado --"))
        spinnerMarca.isEnabled = false
        spinnerPresentacion.adapter = ArrayAdapter(this, R.layout.spinner_item, arrayOf("-- Bloqueado --"))
        spinnerPresentacion.isEnabled = false
        spinnerFragancia.adapter = ArrayAdapter(this, R.layout.spinner_item, arrayOf("-- Bloqueado --"))
        spinnerFragancia.isEnabled = false
        btnIniciarTurno.isEnabled = false
    }

    private fun configurarBotones() {
        btnIniciarTurno.setOnClickListener {
            if (spinnerMaquina.selectedItemPosition == 0 ||
                spinnerOperador.selectedItemPosition == 0 ||
                spinnerMarca.selectedItemPosition == 0 ||
                spinnerPresentacion.selectedItemPosition == 0 ||
                spinnerFragancia.selectedItemPosition == 0) {

                Toast.makeText(this, "⚠️ ERROR: Debe completar todos los campos obligatorios.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val request = IniciarTurnoRequest(
                tipo = spinnerTipo.selectedItem.toString(),
                maquina = spinnerMaquina.selectedItem.toString(),
                operador = spinnerOperador.selectedItem.toString(),
                marca = spinnerMarca.selectedItem.toString(),
                presentacion = spinnerPresentacion.selectedItem.toString(),
                fragancia = spinnerFragancia.selectedItem.toString()
            )
            llamarApiIniciarTurno(request)
        }

        btnEnviarPallet.setOnClickListener {
            val cantidadStr = etCantidadPacas.text.toString()
            if (cantidadStr.isEmpty() || cantidadStr.toInt() <= 0) {
                Toast.makeText(this, "Ingrese una cantidad válida", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (sesionActualId != null) {
                if (sesionActualId == -99) {
                    // --- MODO VIRTUAL (-99) ---
                    // 1. Prohibido mandar a la API. Lo metemos a la caja fuerte local.
                    val datosTemporales = RegistrarPalletRequest(sesionActualId!!, cantidadStr.toInt())
                    guardarPacaPendiente(datosTemporales)

                    // 2. Lo dibujamos en amarillo en el historial
                    val horaActual = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    val nuevaEntrada = "🟡 ${cantidadStr} pacas - $horaActual (Creando turno...)\n"
                    val historialPrevio = tvHistorialPacas.text.toString()
                    tvHistorialPacas.text = nuevaEntrada + historialPrevio
                    etCantidadPacas.text.clear()

                    // 3. ¡Despertamos al Sincronizador Maestro!
                    // Él creará el turno en el servidor, cambiará el -99 por el ID real y enviará esta paca.
                    Toast.makeText(this, "Reconectando y creando turno...", Toast.LENGTH_SHORT).show()
                    iniciarSincronizacion()

                } else {
                    // --- MODO NORMAL (Ya tenemos ID Real) ---
                    // Intentamos el envío directo al servidor
                    llamarApiRegistrarPallet(RegistrarPalletRequest(sesionActualId!!, cantidadStr.toInt()))
                }
            }
        }

        btnSolicitarInsumo.setOnClickListener {
            if (sesionActualId == null || sesionActualId == -99) {
                Toast.makeText(this, "Debe tener red para solicitar insumos.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            abrirModalInsumosInteligente() // <-- Llamamos a la nueva función
        }

        // --- AQUI ESTA LA MAGIA DE LA SINCRONIZACIÓN ---
        btnTerminarTurno.setOnClickListener {
            if (sesionActualId != null) {
                val pendientes = obtenerPendientes()

                if (pendientes.isNotEmpty()) {
                    // Hay pacas en memoria: Forzamos envío antes de cerrar
                    Toast.makeText(this, "Sincronizando pacas pendientes antes de cerrar...", Toast.LENGTH_LONG).show()
                    iniciarSincronizacion {
                        // Cuando termine de enviar todas, dispara tu función de cierre normal
                        llamarApiFinalizarTurno(FinalizarTurnoRequest(sesionActualId!!))
                    }
                } else {
                    // Todo está al día: Cierra de inmediato
                    llamarApiFinalizarTurno(FinalizarTurnoRequest(sesionActualId!!))
                }
            }
        }
        btnParo.setOnClickListener {
            // Protección: No podemos registrar paros si estamos en modo Offline (Virtual -99)
            if (sesionActualId == null || sesionActualId == -99) {
                Toast.makeText(this, "Debe tener red para registrar paros.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isParoActivo) {
                // --- MOSTRAR MODAL COMBINADO (SPINNER + TEXTO) ---
                val builder = AlertDialog.Builder(this)
                builder.setTitle("Registrar Tiempo Muerto")

                // Crear un contenedor visual
                val layout = LinearLayout(this)
                layout.orientation = LinearLayout.VERTICAL
                layout.setPadding(50, 40, 50, 20)

                // 1. Crear el Spinner (Lista de categorías)
                val tituloSpinner = TextView(this)
                tituloSpinner.text = "Categoría del paro:"
                tituloSpinner.setTypeface(null, android.graphics.Typeface.BOLD)
                layout.addView(tituloSpinner)

                val spinnerTipoParo = Spinner(this)
                val categoriasParo = arrayOf("Mantenimiento", "Bodega", "Planeación", "Aseo/Limpieza", "Otro")
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categoriasParo)
                spinnerTipoParo.adapter = adapter
                spinnerTipoParo.setPadding(0, 0, 0, 30)
                layout.addView(spinnerTipoParo)

                // 2. Crear el EditText (Razón detallada)
                val tituloTexto = TextView(this)
                tituloTexto.text = "Motivo detallado:"
                tituloTexto.setTypeface(null, android.graphics.Typeface.BOLD)
                tituloTexto.setPadding(0, 20, 0, 0)
                layout.addView(tituloTexto)

                val inputMotivo = EditText(this)
                inputMotivo.hint = "Ej: Por temas de arrastres..."
                layout.addView(inputMotivo)

                builder.setView(layout)

                builder.setPositiveButton("Confirmar Paro") { dialog, _ ->
                    val categoria = spinnerTipoParo.selectedItem.toString()
                    val detalle = inputMotivo.text.toString().trim()

                    if (detalle.isNotEmpty()) {
                        // TRUCO DE ANALISTA: Concatenamos los datos para no modificar la Base de Datos
                        val motivoCombinado = "[$categoria] - $detalle"
                        llamarApiIniciarParo(motivoCombinado)
                    } else {
                        Toast.makeText(this, "Debe escribir el motivo detallado", Toast.LENGTH_SHORT).show()
                    }
                }
                builder.setNegativeButton("Cancelar") { dialog, _ -> dialog.cancel() }
                builder.show()

            } else {
                // --- REANUDAR OPERACIÓN ---
                llamarApiFinalizarParo()
            }
        }
    }

    // --- LLAMADAS A LA API ---
    private fun descargarCatalogosDesdeServidor() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val respuestaOperadores = RetrofitClient.apiService.obtenerOperadores()
                val respuestaMaquinas = RetrofitClient.apiService.obtenerMaquinas()

                if (respuestaOperadores.isSuccessful && respuestaMaquinas.isSuccessful) {
                    val ops = listOf("-- Seleccione Operador --") + (respuestaOperadores.body()?.map { it.nombre } ?: emptyList())
                    val maqs = listOf("-- Seleccione Máquina --") + (respuestaMaquinas.body()?.map { it.nombre } ?: emptyList())

                    withContext(Dispatchers.Main) {
                        listaOperadoresServidor = ops
                        listaMaquinasServidor = maqs
                        // --- GUARDAMOS PARA LA PRÓXIMA VEZ QUE NO HAYA INTERNET ---
                        guardarCatalogosEnCache(ops, maqs)
                    }
                }
            } catch (e: Exception) {
                // Si falla, no hacemos nada, porque onCreate ya cargó el cache
                Log.e("SIGEP", "No se pudo actualizar el catálogo, usando cache.")
            }
        }
    }

    private fun llamarApiIniciarTurno(datos: IniciarTurnoRequest) {
        btnIniciarTurno.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.apiService.iniciarTurno(datos)
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val idRecibido = response.body()?.sesion_id
                        val horaInicioActual = System.currentTimeMillis()

                        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        prefs.edit().apply {
                            putInt("sesion_activa", idRecibido!!)
                            putLong("hora_inicio_ms", horaInicioActual)
                            apply()
                        }

                        sesionActualId = idRecibido
                        Toast.makeText(this@MainActivity, "Turno Iniciado", Toast.LENGTH_SHORT).show()
                        seccionConfiguracion.visibility = View.GONE
                        seccionProduccion.visibility = View.VISIBLE
                    } else {
                        Toast.makeText(this@MainActivity, "Error al iniciar en servidor", Toast.LENGTH_LONG).show()
                    }
                    btnIniciarTurno.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    guardarTurnoOffline(datos) // Guardamos para después
                    sesionActualId = -99       // ID Virtual temporal

                    seccionConfiguracion.visibility = View.GONE
                    seccionProduccion.visibility = View.VISIBLE
                    Toast.makeText(this@MainActivity, "Modo Offline: Turno iniciado localmente", Toast.LENGTH_LONG).show()
                    btnIniciarTurno.isEnabled = true
                }
            }
        }
    }

    private fun llamarApiRegistrarPallet(datos: RegistrarPalletRequest) {
        btnEnviarPallet.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.apiService.registrarPallet(datos)
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@MainActivity, "Registro exitoso", Toast.LENGTH_SHORT).show()
                        etCantidadPacas.text.clear()

                        // --- NUEVO ORDEN: ÚLTIMO REGISTRO ARRIBA ---
                        val horaActual = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

                        // 1. Creamos la nueva línea con el círculo verde
                        val nuevaEntrada = "🟢 ${datos.cantidad_pacas} pacas - $horaActual\n"

                        // 2. Rescatamos lo que ya estaba escrito antes
                        val historialPrevio = tvHistorialPacas.text.toString()

                        // 3. Pegamos lo nuevo ARRIBA de lo viejo
                        tvHistorialPacas.text = nuevaEntrada + historialPrevio
                        // ------------------------------------------
                        iniciarSincronizacion()
                        // 4. Intenta mandar lo atrasado

                    } else {
                        forzarReinicioPorError("Sesión inválida en el servidor.")
                    }
                    btnEnviarPallet.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // ¡Se cayó la red o el servidor Linux está apagado!

                    // 1. Guardamos los datos en la memoria de la tablet
                    guardarPacaPendiente(datos)

                    // 2. Le avisamos a la UI que quedó encolado (Círculo Amarillo 🟡)
                    val horaActual = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    val nuevaEntrada = "🟡 ${datos.cantidad_pacas} pacas - $horaActual (PENDIENTE)\n"

                    val historialPrevio = tvHistorialPacas.text.toString()
                    tvHistorialPacas.text = nuevaEntrada + historialPrevio

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
                val response = RetrofitClient.apiService.finalizarTurno(datos)
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        tvHistorialPacas.text = ""
                        Toast.makeText(this@MainActivity, "Turno cerrado.", Toast.LENGTH_LONG).show()
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
        Toast.makeText(this@MainActivity, "⚠️ $mensaje", Toast.LENGTH_LONG).show()
        sesionActualId = null
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().remove("sesion_activa").apply()
        etCantidadPacas.text.clear()
        spinnerTipo.setSelection(0)
        bloquearSpinnersDesdeMaquina()
        seccionProduccion.visibility = View.GONE
        seccionConfiguracion.visibility = View.VISIBLE
        limpiarEstadoParo()
        seccionProduccion.visibility = View.GONE
        seccionConfiguracion.visibility = View.VISIBLE
    }
    private fun guardarPacaPendiente(datos: RegistrarPalletRequest) {
        val prefs = getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE)
        val gson = Gson()

        // Leemos lo que ya estaba en la caja fuerte (si no hay nada, trae una lista vacía "[]")
        val jsonActual = prefs.getString("cola_pendientes", "[]")
        val type = object : TypeToken<MutableList<RegistrarPalletRequest>>() {}.type
        val listaPendientes: MutableList<RegistrarPalletRequest> = gson.fromJson(jsonActual, type)

        // Agregamos la nueva paca a la fila
        listaPendientes.add(datos)

        // Volvemos a cerrar la caja fuerte
        prefs.edit().putString("cola_pendientes", gson.toJson(listaPendientes)).apply()
    }
    private fun intentarSincronizarPendientes() {
        val pendientes = obtenerPendientes()
        if (pendientes.isEmpty()) return // Si no hay nada pendiente, no hacemos nada

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Para simplificar, intentamos enviar la primera paca de la lista
                val pacaAEnviar = pendientes.first()
                val response = RetrofitClient.apiService.registrarPallet(pacaAEnviar)

                if (response.isSuccessful) {
                    // ¡Se envió con éxito! La quitamos de la fila
                    pendientes.removeAt(0)

                    // Guardamos la fila actualizada
                    val prefs = getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE)
                    prefs.edit().putString("cola_pendientes", Gson().toJson(pendientes)).apply()

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Sincronización exitosa. Quedan ${pendientes.size}", Toast.LENGTH_SHORT).show()
                        // Si aún quedan, se vuelve a llamar a sí misma
                        if (pendientes.isNotEmpty()) {
                            intentarSincronizarPendientes()
                        }
                    }
                }
            } catch (e: Exception) {
                // Sigue sin haber red, lo dejamos para después. Falla silenciosamente.
            }
        }
    }


    // 2. Extrae la lista para intentar enviarla después
    private fun obtenerPendientes(): MutableList<RegistrarPalletRequest> {
        val prefs = getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE)
        val jsonActual = prefs.getString("cola_pendientes", "[]")
        val type = object : TypeToken<MutableList<RegistrarPalletRequest>>() {}.type
        return Gson().fromJson(jsonActual, type) ?: mutableListOf()
    }

    // 3. Limpia la caja fuerte una vez que el servidor confirma que recibió todo
    private fun limpiarPendientes() {
        val prefs = getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE)
        prefs.edit().remove("cola_pendientes").apply()
    }
    private fun actualizarInterfazPendientes() {}

    // Proceso de sincronización mejorado
    private fun iniciarSincronizacion(alFinalizar: () -> Unit = {}) {
        val turnoPendiente = obtenerTurnoPendiente()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // PASO 1: ¿Hay un turno que no se ha creado en el servidor?
                if (turnoPendiente != null) {
                    val resTurno = RetrofitClient.apiService.iniciarTurno(turnoPendiente)
                    if (resTurno.isSuccessful) {
                        val nuevoIdReal = resTurno.body()?.sesion_id ?: -1
                        sesionActualId = nuevoIdReal // Actualizamos la sesión activa

                        // Limpiamos el turno pendiente del cache
                        getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE).edit().remove("turno_pendiente_crear").apply()

                        // IMPORTANTE: Actualizar todos los pallets pendientes con el nuevo ID Real
                        actualizarIdEnPalletsPendientes(nuevoIdReal)

                        // Ahora que el "Padre" existe, procedemos con los "Hijos" (Recursión)
                        withContext(Dispatchers.Main) { iniciarSincronizacion(alFinalizar) }
                        return@launch
                    }
                }

                // PASO 2: Si no hay turnos pendientes, seguimos con los pallets (tu lógica actual)
                val pendientes = obtenerPendientes()
                if (pendientes.isEmpty()) {
                    withContext(Dispatchers.Main) { alFinalizar() }
                    return@launch
                }

                val pacaActual = pendientes.first()
                val response = RetrofitClient.apiService.registrarPallet(pacaActual)

                if (response.isSuccessful) {
                    pendientes.removeAt(0)
                    guardarListaActualizada(pendientes)
                    withContext(Dispatchers.Main) {
                        actualizarInterfazPendientes()

                        // --- AQUÍ ESTÁ LA NUEVA LÍNEA ---
                        // Cambia el texto a verde justo después de actualizar el contador
                        actualizarHistorialVisual(pacaActual.cantidad_pacas)
                        // --------------------------------

                        iniciarSincronizacion(alFinalizar)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { alFinalizar() }
            }
        }
    }
    private fun actualizarIdEnPalletsPendientes(nuevoId: Int) {
        val lista = obtenerPendientes()
        lista.forEach { it.sesion_id = nuevoId } // Actualizamos el ID en cada objeto
        guardarListaActualizada(lista)
    }

    // Función auxiliar para guardar la lista tras borrar un elemento
    private fun guardarListaActualizada(lista: List<RegistrarPalletRequest>) {
        val prefs = getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE)
        prefs.edit().putString("cola_pendientes", Gson().toJson(lista)).apply()
    }
    private fun guardarCatalogosEnCache(operadores: List<String>, maquinas: List<String>) {
        val prefs = getSharedPreferences("SIGEP_CATALOGOS", MODE_PRIVATE)
        val gson = Gson()
        prefs.edit().apply {
            putString("cache_operadores", gson.toJson(operadores))
            putString("cache_maquinas", gson.toJson(maquinas))
            apply()
        }
    }
    private fun cargarCatalogosDesdeCache() {
        val prefs = getSharedPreferences("SIGEP_CATALOGOS", MODE_PRIVATE)
        val gson = Gson()
        val type = object : TypeToken<List<String>>() {}.type

        val jsonOp = prefs.getString("cache_operadores", null)
        val jsonMaq = prefs.getString("cache_maquinas", null)

        if (jsonOp != null && jsonMaq != null) {
            listaOperadoresServidor = gson.fromJson(jsonOp, type)
            listaMaquinasServidor = gson.fromJson(jsonMaq, type)
            // OJO: Como ya tenemos datos, desbloqueamos la UI de una vez
            Log.d("SIGEP", "Catálogos cargados desde CACHE local.")
        }
    }
    // Guarda el inicio de turno si no hay red
    private fun guardarTurnoOffline(request: IniciarTurnoRequest) {
        val prefs = getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE)
        val gson = Gson()
        prefs.edit().putString("turno_pendiente_crear", gson.toJson(request)).apply()
    }

    // Recupera el turno pendiente
    private fun obtenerTurnoPendiente(): IniciarTurnoRequest? {
        val prefs = getSharedPreferences("SIGEP_OFFLINE", MODE_PRIVATE)
        val json = prefs.getString("turno_pendiente_crear", null) ?: return null
        return Gson().fromJson(json, IniciarTurnoRequest::class.java)
    }
    private fun actualizarHistorialVisual(cantidad: Int) {
        val textoActual = tvHistorialPacas.text.toString()

        // Usamos Regex (Expresiones Regulares) para buscar la línea exacta.
        // Esto busca: 🟡 + la cantidad exacta de pacas + cualquier hora + (PENDIENTE) o (Creando turno...)
        val regex = Regex("🟡 $cantidad pacas(.*?)(?:\\(PENDIENTE\\)|\\(Creando turno...\\))")

        // Reemplazamos esa línea exacta por el círculo verde y la marca de Sincronizado
        val textoNuevo = textoActual.replaceFirst(regex, "🟢 $cantidad pacas$1(Sincronizado)")

        tvHistorialPacas.text = textoNuevo
    }
    private fun llamarApiIniciarParo(motivo: String) {
        btnParo.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = IniciarParoRequest(sesion_id = sesionActualId!!, motivo = motivo)
                val response = RetrofitClient.apiService.iniciarParo(request)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        isParoActivo = true

                        // Cambiamos el botón a Verde (Reanudar)
                        btnParo.text = "REANUDAR OPERACIÓN"
                        btnParo.setBackgroundColor(Color.parseColor("#4CAF50"))

                        // BLOQUEAMOS EL ENVÍO DE PACAS
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
                val request = FinalizarParoRequest(sesion_id = sesionActualId!!)
                val response = RetrofitClient.apiService.finalizarParo(request)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        isParoActivo = false

                        // Regresamos el botón a Rojo (Registrar Paro)
                        btnParo.text = "REGISTRAR PARO"
                        btnParo.setBackgroundColor(Color.parseColor("#FA8400"))

                        // DESBLOQUEAMOS EL ENVÍO DE PACAS
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
        isParoActivo = false

        // Resetear el botón de Paro a Rojo
        btnParo.text = "REGISTRAR PARO"
        btnParo.setBackgroundColor(Color.parseColor("#FA8400"))
        btnParo.isEnabled = true

        // Desbloquear botón de Pallets a Azul
        btnEnviarPallet.isEnabled = true
        btnEnviarPallet.setBackgroundColor(Color.parseColor("#2196F3"))

    }
    private fun recuperarHistorial(idSesion: Int) {
        // Ponemos un mensaje temporal mientras carga
        tvHistorialPacas.text = "Cargando historial..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Pedimos la verdad absoluta al servidor
                val response = RetrofitClient.apiService.obtenerHistorialSesion(idSesion)

                withContext(Dispatchers.Main) {
                    var textoHistorial = ""

                    // 2. Primero, revisamos si hay pacas huérfanas/offline de esta sesión
                    val pendientes = obtenerPendientes()
                    val pendientesDeEstaSesion = pendientes.filter { it.sesion_id == idSesion }

                    for (p in pendientesDeEstaSesion) {
                        // Como no tienen hora de servidor aún, les ponemos la actual
                        val horaActual = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        textoHistorial += "🟡 ${p.cantidad_pacas} pacas - $horaActual (PENDIENTE)\n"
                    }

                    // 3. Luego, agregamos las pacas confirmadas por el servidor
                    if (response.isSuccessful && response.body() != null) {
                        val listaServidor = response.body()!!
                        for (p in listaServidor) {
                            textoHistorial += "🟢 ${p.cantidad_pacas} pacas - ${p.hora}\n"
                        }
                    }

                    // 4. Mostramos todo en pantalla
                    if (textoHistorial.isEmpty()) {
                        tvHistorialPacas.text = "No hay registros aún."
                    } else {
                        tvHistorialPacas.text = textoHistorial
                    }
                }
            } catch (e: Exception) {
                // Si no hay red, al menos mostramos lo offline y dejamos el resto en blanco
                withContext(Dispatchers.Main) {
                    val pendientes = obtenerPendientes().filter { it.sesion_id == idSesion }
                    if (pendientes.isNotEmpty()) {
                        var textoOffline = "⚠️ Modo Sin Red:\n"
                        for (p in pendientes) {
                            textoOffline += "🟡 ${p.cantidad_pacas} pacas (PENDIENTE)\n"
                        }
                        tvHistorialPacas.text = textoOffline
                    } else {
                        tvHistorialPacas.text = "Sin red para cargar historial anterior."
                    }
                }
            }
        }
    }
    // ==========================================
    //  MÓDULO DE INSUMOS (DINÁMICO)
    // ==========================================
    private fun abrirModalInsumosInteligente() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("📦 Solicitar a Bodega")

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 20)

        // 1. Selector de Insumos (Opciones simples para el operario)
        val tituloSpinner = TextView(this)
        tituloSpinner.text = "Insumo Requerido:"
        tituloSpinner.setTypeface(null, android.graphics.Typeface.BOLD)
        layout.addView(tituloSpinner)

        val spinnerInsumos = Spinner(this)
        val maquinaActual = spinnerMaquina.selectedItem.toString()

        // --- REGLAS DE NEGOCIO ---
        val opcionesInsumo = mutableListOf("Materia Prima (Granel)")
        if (maquinaActual == "Máquina 7B") {
            opcionesInsumo.add(0, "Sacos Tejidos") // 7B usa sacos
        } else {
            opcionesInsumo.add(0, "Lámina (Rollo)") // Las demás usan Lámina
        }

        spinnerInsumos.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opcionesInsumo)
        spinnerInsumos.setPadding(0, 0, 0, 30)
        layout.addView(spinnerInsumos)

        // 2. Campo de Cantidad
        val tituloCantidad = TextView(this)
        tituloCantidad.text = "Cantidad a solicitar:"
        tituloCantidad.setTypeface(null, android.graphics.Typeface.BOLD)
        tituloCantidad.setPadding(0, 20, 0, 0)
        layout.addView(tituloCantidad)

        val inputCantidad = EditText(this)
        inputCantidad.hint = "Ej: 2"
        inputCantidad.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        layout.addView(inputCantidad)

        builder.setView(layout)

        builder.setPositiveButton("Enviar Pedido") { dialog, _ ->
            val cantidadStr = inputCantidad.text.toString()
            if (cantidadStr.isNotEmpty() && cantidadStr.toInt() > 0) {

                // --- ARMADOR DEL TEXTO PARA EL INSUMISTA ---
                val seleccionOperario = spinnerInsumos.selectedItem.toString()
                val marca = spinnerMarca.selectedItem.toString()
                val fragancia = spinnerFragancia.selectedItem.toString()
                val presentacion = spinnerPresentacion.selectedItem.toString()

                var textoArmado = ""
                if (seleccionOperario.contains("Lámina") || seleccionOperario.contains("Sacos")) {
                    // Empaque requiere Marca + Fragancia + Presentación
                    textoArmado = "$seleccionOperario - $marca - $fragancia - $presentacion"
                } else {
                    // Granel requiere PRETEN + Marca + Fragancia (No requiere presentación)
                    textoArmado = "Preten $marca $fragancia (Granel)"
                }

                // Aquí ya no mandamos un ID numérico, mandamos el texto que acabamos de armar
                val request = SolicitarInsumoRequest(sesionActualId!!, textoArmado, cantidadStr.toInt())
                enviarPeticionInsumo(request)

            } else {
                Toast.makeText(this, "Debe ingresar una cantidad válida", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancelar") { dialog, _ -> dialog.cancel() }
        builder.show()
    }
    private fun enviarPeticionInsumo(request: SolicitarInsumoRequest) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.apiService.solicitarInsumo(request)
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@MainActivity, "✅ Pedido enviado al Insumista", Toast.LENGTH_LONG).show()
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
