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
import com.example.controller.util.versionNameInstalada
import kotlinx.coroutines.flow.collectLatest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private var sesionActualId: Int? = null
    private lateinit var btnSolicitarInsumo: Button
    private lateinit var btnParo: Button
    private lateinit var btnAlmuerzo: Button
    private lateinit var bannerAlmuerzo: View
    private lateinit var chronoAlmuerzo: android.widget.Chronometer
    private lateinit var btnComentarioTurno: Button
    private lateinit var btnReporteApp: Button
    private lateinit var colaRepo: ColaSyncRepository
    private lateinit var connectivityObserver: ConnectivityObserver
    private lateinit var tabletsWsManager: TabletsWebSocketManager
    private lateinit var tvBadge: android.widget.TextView

    private lateinit var btnVelocidad: Button

    private var paroActivo = false

    // Id del paro abierto, devuelto por POST /api/paro/iniciar. Se persiste junto al
    // flag para poder adjuntarle la foto y para reconocerlo al consultar el estado
    // real al servidor. null = paro abierto sin red (todavía sin id) o sin paro.
    private var paroActivoId: Int? = null

    // true mientras viaja un iniciar/finalizar paro. Sin esto, una revalidación del
    // radar que salió ANTES de abrir el paro puede volver con "no hay paro" justo
    // después de abrirlo y desbloquear las pacas con la máquina parada.
    private var operacionParoEnCurso = false

    // Se incrementa en cada cambio local del estado de paro. La consulta al servidor
    // guarda su valor antes de salir y solo aplica la respuesta si nadie tocó el paro
    // mientras viajaba: si no, una respuesta lenta pisa lo que el operario acaba de hacer.
    private var generacionParo = 0

    // Foto opcional del paro en curso de captura (misma mecánica que la evidencia de
    // las entregas de insumo: FileProvider + TakePicture).
    private var fotoParoUri: Uri? = null
    private var fotoParoFile: File? = null
    private var tvFotoParoEstado: TextView? = null

    // Lado largo al que se redimensiona la foto antes de subirla. Una foto de tablet
    // moderna pasa de los 5 MB que acepta el server (400) — a ~1600 px y JPEG 80 quedan
    // 200-400 KB, de sobra para ver una pieza rota.
    private val LADO_MAX_FOTO = 1600
    private val CALIDAD_JPEG_FOTO = 80
    private val PERMISO_CAMARA_PARO = 301

    // PARO ALMUERZO: reusa el mecanismo de paro (motivo fijo) con contador en pantalla.
    private var almuerzoActivo = false
    private val MOTIVO_ALMUERZO = "ALMUERZO"

    // Cada cuántos ciclos del radar (5 s) se revalida el estado del paro contra el
    // servidor: ~30 s. El paro cerrado desde la web se detecta solo, sin que el
    // operario tenga que tocar nada, y sin triplicar el tráfico del radar.
    private val CICLOS_RADAR_POR_CHEQUEO_PARO = 6
    private lateinit var seccionConfiguracion: LinearLayout
    private lateinit var seccionProduccion: LinearLayout
    private lateinit var etCantidadPacas: EditText
    private lateinit var btnIniciarTurno: Button
    private lateinit var btnEnviarPallet: Button
    private lateinit var tvHistorialPacas: TextView
    private lateinit var tvTotalPacas: TextView
    // Total de pacas del turno, para no tener que sumar línea por línea el historial.
    private var totalPacasTurno = 0
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

    // ===== Gates del checklist de mantenimiento (ENTRADA / SALIDA) =====
    // Turno pendiente de iniciar mientras el operario llena el checklist de ENTRADA.
    private var turnoPendiente: IniciarTurnoRequest? = null

    // ENTRADA: si el checklist vuelve OK, se procede a iniciar el turno.
    private val entradaLauncher = registerForActivityResult(
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

    // SALIDA: si el checklist vuelve OK, se procede a cerrar el turno.
    private val salidaLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && sesionActualId != null) {
            llamarApiFinalizarTurno(FinalizarTurnoRequest(sesionActualId!!))
        } else {
            btnTerminarTurno.isEnabled = true
        }
    }

    // Cámara para la evidencia del paro. La foto es OPCIONAL: si el operario cancela,
    // el paro se registra igual (el dato que no se puede perder es el tiempo parado).
    private val launcherCamaraParo = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) {
            tvFotoParoEstado?.text = "📷 Foto capturada"
        } else {
            fotoParoFile?.delete()
            fotoParoUri = null
            fotoParoFile = null
            tvFotoParoEstado?.text = "Sin foto adjunta"
        }
    }

    val tipos = arrayOf("-- Seleccione Tipo --", "Sólido", "Líquido")

    // YA NO es la fuente de las fragancias: ahora vienen de api/fragancias por
    // combinación máquina+marca. Se conserva como fallback de último recurso (mismo
    // papel que listaMaquinasLocal / marcasPorMaquinaLocal) para la tablet recién
    // instalada que aún no tiene caché y arranca sin red. Sin esto el spinner se
    // quedaría solo con el placeholder y el guard de INICIAR TURNO —que exige
    // selectedItemPosition != 0— haría imposible abrir el turno.
    // El texto viaja tal cual al servidor: no tocar los acentos.
    val fragancias = arrayOf("-- Seleccione Fragancia --", "Limón", "Floral")

    private var listaOperadoresServidor: List<String> = emptyList()
    private var listaMaquinasServidor: List<String> = emptyList()

    // Operarios por línea ("SOLIDO"/"LIQUIDO"), descargados con api/operadores?tipo=
    // y cacheados. Cada lista incluye el placeholder inicial. Al elegir la máquina se
    // usa su tipo para poblar el spinner de operador (CAMBIO operarios por línea).
    // Si un tipo está presente aquí (aunque sea solo el placeholder) se respeta tal cual;
    // solo si el tipo NO está (caché vieja / sin red aún) se cae a la lista completa.
    private var operadoresPorTipo: Map<String, List<String>> = emptyMap()

    // Tipo de línea por máquina ("SOLIDO"/"LIQUIDO"), descargado con api/maquinas y
    // cacheado. Al elegir Sólido/Líquido el spinner de máquina se filtra por esto
    // (ver CAMBIO 2). Si una máquina no aparece aquí, se asume SOLIDO.
    private var tipoPorMaquina: Map<String, String> = emptyMap()

    // Jerarquía dinámica descargada con la lista de máquinas (marcas/presentaciones
    // POR máquina desde api/maquinas). Reemplaza a los mapas locales cuando llega.
    private var jerarquiaMaquinas: Map<String, List<MarcaResponse>> = emptyMap()

    // Fragancias por combinación, clave "máquina|marca" (mismo formato que
    // presentacionesPorMaquinaMarcaLocal). Se llena desde api/fragancias al elegir
    // marca y se cachea, para que la siguiente vez salga al instante y sin red.
    private var fraganciasPorMaquinaMarca: Map<String, List<String>> = emptyMap()

    // Versión del esquema de caché de la jerarquía. Subir este número invalida
    // cachés viejas. v2 descarta el catálogo GLOBAL erróneo que guardaban versiones
    // previas (mismo para todas las máquinas) y cae al fallback local hasta re-descargar.
    private val cacheVersionJerarquia = 2

    // Versión del esquema de caché de fragancias, independiente de la jerarquía para
    // poder invalidar una sin tirar la otra. Subirlo descarta las cachés anteriores.
    private val cacheVersionFragancias = 1

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
        tvTotalPacas        = findViewById(R.id.tvTotalPacas)
        btnVerEstadoPedidos = findViewById(R.id.btnVerEstadoPedidos)
        tvBadge             = findViewById(R.id.tvBadgePendientes)
        vincularVistas()
        // Versión leída del paquete instalado, no de BuildConfig: esa constante se
        // incrusta en el .class y se queda vieja si el archivo no se recompila
        // (ver VersionApp.kt — pasó de verdad en MenuRoles/Splash con la 1.5.1).
        findViewById<TextView>(R.id.textViewDevelopedBy)?.text =
            "Desarrollado por John Alexander Garcia | DETCUADOR S.A. | Versión ${versionNameInstalada()}"

        rvPendientesConfirmacion.layoutManager = LinearLayoutManager(this)
        adapterPendientesConfirmacion = PedidoAdapter(emptyList(), esAsignado = false, esConfirmacion = true) { pedido ->
            pedirCantidadYConfirmar(pedido)
        }
        rvPendientesConfirmacion.adapter = adapterPendientesConfirmacion
        rvPendientesConfirmacion.isNestedScrollingEnabled = false

        colaRepo = ColaSyncRepository(applicationContext)
        val tabletPrefs = PrefsRepository(applicationContext)
        connectivityObserver = ConnectivityObserver(applicationContext) {
            // Al recuperar conexión re-descargamos catálogos + jerarquía para que
            // la tablet quede sincronizada con MySQL sin esperar a reiniciar la app.
            descargarCatalogosDesdeServidor()
            refrescarFraganciasDeSeleccionActual()
            // Volvió la red: es el momento de comprobar si el paro que la tablet cree
            // abierto sigue abierto de verdad.
            sincronizarEstadoParoDesdeServidor()
        }
        tabletsWsManager = TabletsWebSocketManager(
            tabletPrefs.deviceId,
            WorkManager.getInstance(applicationContext)
        ) { id, texto ->
            // Mensaje del admin web (CAMBIO 4): popup sobre la pantalla de producción.
            MensajesAdmin.mostrar(this, id, texto)
        }

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

        // El operario NUNCA debe recibir la alarma de pedidos de insumo. Si esta
        // tablet se usó antes como insumista, el pref de tipo y el token FCM podían
        // quedar asociados a Granel/Empaque. Lo corregimos al entrar al panel.
        asegurarIdentidadOperarioFcm()

        val sesionGuardada  = prefs.getInt("sesion_activa", -1)
        val timestampInicio = prefs.getLong("hora_inicio_ms", 0L)

        if (sesionGuardada != -1) {
            val horasTranscurridas = (System.currentTimeMillis() - timestampInicio) / (1000 * 60 * 60)

            if (horasTranscurridas >= 12) {
                // Límite de 12h: evita que un turno del día anterior quede activo indefinidamente
                // si el operario olvidó cerrarlo al final de su jornada.
                forzarReinicioPorError("Turno anterior caducado (límite de 12h). Inicie uno nuevo.")
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
                        restaurarEstadoAlmuerzo()
                        restaurarEstadoParo()
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
                restaurarEstadoAlmuerzo()
                // El botón de paro nace de lo persistido y se corrige enseguida con el
                // estado real del servidor (onStart / radar), no de lo que había en memoria.
                restaurarEstadoParo()
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
            var ciclo = 0
            while (isActive) {
                // Revalidación periódica del paro: el caso de la Máquina 7 pasó con la
                // app en primer plano todo el rato, así que no basta con mirar al
                // arrancar o al volver de segundo plano.
                if (ciclo % CICLOS_RADAR_POR_CHEQUEO_PARO == 0) {
                    sincronizarEstadoParoDesdeServidor()
                }
                ciclo++
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

    // Pregunta cuánto recibió realmente el operario (precargado con lo que el insumista
    // dijo que entregó, o lo solicitado) para poder comparar entregado vs recibido.
    private fun pedirCantidadYConfirmar(pedido: PedidoPendienteResponse) {
        val sugerido = pedido.cantidad_entregada ?: pedido.cantidad
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(sugerido.toString())
            setSelection(text.length)
        }
        val entregadaTxt = pedido.cantidad_entregada?.let { "Insumista entregó: $it\n" } ?: ""
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Cantidad recibida")
            .setMessage("Solicitado: ${pedido.cantidad} x ${pedido.detalle}\n$entregadaTxt\n¿Cuánto recibes realmente?")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Confirmar") { _, _ ->
                val cant = input.text.toString().toIntOrNull()
                if (cant == null || cant < 0) {
                    Toast.makeText(this, "Ingrese una cantidad válida", Toast.LENGTH_SHORT).show()
                    cargarPendientesConfirmacion(sesionActualId!!)
                } else {
                    confirmarRecepcion(pedido.id, cant)
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> cargarPendientesConfirmacion(sesionActualId!!) }
            .show()
    }

    private fun confirmarRecepcion(pedidoId: Int, cantidadRecibida: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.apiService.confirmarRecepcion(
                    ConfirmarRecepcionRequest(pedidoId, sesionActualId!!, cantidadRecibida)
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
        btnVelocidad         = findViewById(R.id.btnVelocidad)
        btnTerminarTurno     = findViewById(R.id.btnTerminarTurno)
        btnParo              = findViewById(R.id.btnParo)
        btnAlmuerzo          = findViewById(R.id.btnAlmuerzo)
        bannerAlmuerzo       = findViewById(R.id.bannerAlmuerzo)
        chronoAlmuerzo       = findViewById(R.id.chronoAlmuerzo)
        btnComentarioTurno   = findViewById(R.id.btnComentarioTurno)
        btnReporteApp        = findViewById(R.id.btnReporteApp)
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
                    // Filtra las máquinas por el tipo de línea elegido (Sólido/Líquido).
                    val maquinas = maquinasParaTipo(tipos[position])
                    if (maquinas.size > 1) {
                        spinnerMaquina.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, maquinas)
                        spinnerMaquina.isEnabled = true
                    } else {
                        spinnerMaquina.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, arrayOf("-- Sin máquinas de este tipo --"))
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

                    // Operarios de la línea de esta máquina (su tipo es la fuente de verdad).
                    val tipoMaquina = tipoPorMaquina[maquinaSeleccionada] ?: "SOLIDO"
                    val operadores = operadoresParaTipo(tipoMaquina)
                    spinnerOperador.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, operadores)
                    spinnerOperador.isEnabled = true

                    // La fragancia depende de máquina+marca (api/fragancias), así que
                    // aquí solo se bloquea: se puebla al elegir marca, igual que presentación.
                    spinnerFragancia.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item,
                        arrayOf("-- Seleccione primero la Marca --"))
                    spinnerFragancia.isEnabled = false

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

                    // Fragancias de esta combinación: primero lo que ya tenemos (caché o
                    // fallback) de forma síncrona para no dejar la UI esperando, y en
                    // paralelo el refresco contra el servidor.
                    val clave = "$maquinaActual|$marcaActual"
                    aplicarFragancias(fraganciasParaClave(clave))
                    refrescarFraganciasDesdeServidor(maquinaActual, marcaActual, clave)
                } else {
                    spinnerPresentacion.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, arrayOf("-- Seleccione primero la Marca --"))
                    spinnerPresentacion.isEnabled = false
                    spinnerFragancia.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, arrayOf("-- Seleccione primero la Marca --"))
                    spinnerFragancia.isEnabled = false
                    btnIniciarTurno.isEnabled = false
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // Fragancias a mostrar para una combinación "máquina|marca", sin tocar la red:
    // 1) lo cacheado/descargado para esa combinación, 2) el fallback en fuente.
    // Devuelve solo las opciones reales, sin placeholder.
    private fun fraganciasParaClave(clave: String): List<String> =
        fraganciasPorMaquinaMarca[clave]
            ?.takeIf { it.isNotEmpty() }
            ?: fragancias.drop(1)

    // Pone las fragancias en el spinner con el placeholder delante y lo habilita.
    private fun aplicarFragancias(opciones: List<String>) {
        // El spinner NUNCA puede quedar solo con el placeholder: el guard de
        // INICIAR TURNO exige selectedItemPosition != 0 y el turno sería imposible
        // de abrir. Ante una lista vacía se cae igualmente al fallback en fuente.
        val lista = opciones.takeIf { it.isNotEmpty() } ?: fragancias.drop(1)
        val items = listOf("-- Seleccione Fragancia --") + lista

        // Si ya está puesta exactamente esta lista no reasignamos el adapter:
        // hacerlo resetea la selección y le borraría al operario la fragancia que
        // ya había elegido (el refresco de red suele llegar después de que eligió).
        val actual = spinnerFragancia.adapter?.let { ad ->
            (0 until ad.count).map { ad.getItem(it)?.toString() }
        }
        if (actual != items) {
            spinnerFragancia.adapter = ArrayAdapter(this, R.layout.spinner_item, items)
        }
        spinnerFragancia.isEnabled = true
    }

    // Refresco de fragancias contra el servidor, por detrás de la UI. Nunca bloquea:
    // el spinner ya quedó poblado con caché/fallback antes de entrar aquí.
    private fun refrescarFraganciasDesdeServidor(maquina: String, marca: String, clave: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp  = RetrofitClient.apiService.obtenerFragancias(maquina, marca)
                val lista = resp.body().orEmpty()
                // El server nunca devuelve vacío; si llega vacío o falla, conservamos
                // la caché previa en vez de degradar lo que el operario ya ve.
                if (!resp.isSuccessful || lista.isEmpty()) return@launch
                withContext(Dispatchers.Main) {
                    fraganciasPorMaquinaMarca = fraganciasPorMaquinaMarca + (clave to lista)
                    guardarFraganciasEnCache()
                    // Todo setAdapter dispara onItemSelected(position = 0) de forma
                    // asíncrona, así que el operario puede haber cambiado de marca
                    // mientras llegaba esta respuesta: solo se aplica si sigue vigente.
                    val sigueVigente = spinnerMaquina.selectedItem?.toString() == maquina &&
                                       spinnerMarca.selectedItem?.toString() == marca
                    if (sigueVigente) aplicarFragancias(lista)
                }
            } catch (e: Exception) {
                ErrorReporter.report("Fragancias", "No se pudo actualizar fragancias — usando caché local", e)
            }
        }
    }

    // Al recuperar la red, revalida solo la combinación que el operario tiene elegida
    // en ese momento. No prefetcheamos las ~17 combinaciones: serían 17 llamadas para
    // datos que probablemente no se usen en este turno.
    // Ojo: el callback de ConnectivityObserver corre en hilo de binder, y los spinners
    // solo se pueden leer desde el hilo principal.
    private fun refrescarFraganciasDeSeleccionActual() {
        runOnUiThread {
            if (spinnerMaquina.selectedItemPosition <= 0 || spinnerMarca.selectedItemPosition <= 0) return@runOnUiThread
            val maquina = spinnerMaquina.selectedItem?.toString() ?: return@runOnUiThread
            val marca   = spinnerMarca.selectedItem?.toString() ?: return@runOnUiThread
            refrescarFraganciasDesdeServidor(maquina, marca, "$maquina|$marca")
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

                // Gate de ENTRADA: el checklist de mantenimiento es obligatorio
                // antes de pasar a la pantalla de producción.
                gateEntradaYContinuar(
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
                // Gate de SALIDA: el checklist de limpieza es obligatorio antes de cerrar.
                btnTerminarTurno.isEnabled = false
                gateSalidaYFinalizar()
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

                // Foto opcional (reusa la misma mecánica que la evidencia de insumos).
                agregarSelectorFotoParo(contenedor)

                // El diálogo va en un ScrollView: con el bloque de foto ya no entra en
                // la pantalla de la tablet con el teclado abierto.
                val scroll = android.widget.ScrollView(this).apply { addView(contenedor) }

                AlertDialog.Builder(this)
                    .setTitle("Registrar Tiempo Muerto")
                    .setView(scroll)
                    .setPositiveButton("Confirmar Paro") { _, _ ->
                        val detalle = inputMotivo.text.toString().trim()
                        if (detalle.isNotEmpty()) {
                            // Concatenamos categoría y detalle en un solo campo
                            // para no modificar el esquema de base de datos existente.
                            llamarApiIniciarParo("[${spinnerTipoParo.selectedItem}] - $detalle", fotoParoUri)
                        } else {
                            Toast.makeText(this, "Debe escribir el motivo detallado", Toast.LENGTH_SHORT).show()
                            descartarFotoParoPendiente()
                        }
                    }
                    .setNegativeButton("Cancelar") { _, _ -> descartarFotoParoPendiente() }
                    .setOnDismissListener { tvFotoParoEstado = null }
                    .show()
            } else {
                llamarApiFinalizarParo()
            }
        }

        // Salida manual por si todo falla: pulsación larga sobre el botón de paro.
        btnParo.setOnLongClickListener {
            if (!paroActivo) return@setOnLongClickListener false
            mostrarOpcionesParo()
            true
        }

        // VELOCIDAD MAQ — lectura puntual en fundas/min. Offline-first (cola).
        btnVelocidad.setOnClickListener {
            if (sesionActualId == null) {
                Toast.makeText(this, "Inicie un turno para registrar la velocidad.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pedirVelocidadMaquina()
        }

        // PARO ALMUERZO — un toque inicia (motivo fijo), otro reanuda. Offline-first.
        btnAlmuerzo.setOnClickListener {
            if (!almuerzoActivo) iniciarAlmuerzo() else finalizarAlmuerzo()
        }

        // CAMBIO 5 — Comentarios del turno (requiere turno activo).
        btnComentarioTurno.setOnClickListener {
            if (sesionActualId == null) {
                Toast.makeText(this, "Inicia un turno para dejar comentarios.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            mostrarDialogoTexto(
                "Comentarios del turno",
                "Ej: Se trabaja a velocidad lenta por lámina en mal estado"
            ) { texto ->
                val prefs = getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE)
                lifecycleScope.launch {
                    colaRepo.enqueueComentarioTurno(
                        ComentarioTurnoRequest(
                            session_id = sesionActualId,
                            maquina    = prefs.getString("turno_maquina", null) ?: spinnerMaquina.selectedItem?.toString(),
                            operador   = prefs.getString("turno_operador", null) ?: spinnerOperador.selectedItem?.toString(),
                            texto      = texto
                        )
                    )
                    Toast.makeText(this@MainActivity, "Comentario guardado", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // CAMBIO 5 — Reportar un problema con la app (disponible siempre, con o sin turno).
        btnReporteApp.setOnClickListener {
            mostrarDialogoTexto(
                "Reportar un problema con la app",
                "Describe la falla (qué hacías y qué pasó)…"
            ) { texto ->
                val prefs = getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE)
                lifecycleScope.launch {
                    colaRepo.enqueueReporteApp(
                        ReporteAppRequest(
                            session_id = sesionActualId,
                            maquina    = prefs.getString("turno_maquina", null),
                            operador   = prefs.getString("turno_operador", null),
                            texto      = texto
                        )
                    )
                    Toast.makeText(this@MainActivity, "Reporte enviado. ¡Gracias!", Toast.LENGTH_SHORT).show()
                }
            }
        }

    }

    // Diálogo simple con un campo de texto multilínea (CAMBIO 5). Llama a [onEnviar]
    // con el texto ya validado (no vacío) al confirmar.
    private fun mostrarDialogoTexto(titulo: String, hint: String, onEnviar: (String) -> Unit) {
        val contenedor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }
        val input = EditText(this).apply {
            this.hint = hint
            minLines = 3
            maxLines = 6
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        contenedor.addView(input)
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setView(contenedor)
            .setPositiveButton("Enviar") { _, _ ->
                val texto = input.text.toString().trim()
                if (texto.isEmpty()) {
                    Toast.makeText(this, "Escribe un texto antes de enviar", Toast.LENGTH_SHORT).show()
                } else {
                    onEnviar(texto.take(1000))
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
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

    // Filtra la lista de máquinas por el tipo de línea elegido (Sólido/Líquido).
    // El tipo de la sesión ("Sólido"/"Líquido") se mapea al canónico de máquina
    // ("SOLIDO"/"LIQUIDO"). Conserva el placeholder inicial. Máquina sin tipo => SOLIDO.
    private fun maquinasParaTipo(tipoSesion: String): List<String> {
        val base = if (listaMaquinasServidor.size > 1) listaMaquinasServidor else listaMaquinasLocal
        if (base.isEmpty()) return base
        val canon = if (tipoSesion.startsWith("L", ignoreCase = true)) "LIQUIDO" else "SOLIDO"
        val placeholder = base.first()
        val filtradas = base.drop(1).filter { nombre ->
            (tipoPorMaquina[nombre] ?: "SOLIDO").equals(canon, ignoreCase = true)
        }
        return listOf(placeholder) + filtradas
    }

    private fun descargarCatalogosDesdeServidor() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Descargamos los operarios por línea (SOLIDO/LIQUIDO) para que el
                // spinner se filtre por el tipo de la máquina, incluso offline luego.
                val respOpsSolido  = RetrofitClient.apiService.obtenerOperadores("SOLIDO")
                val respOpsLiquido = RetrofitClient.apiService.obtenerOperadores("LIQUIDO")
                val respMaqs       = RetrofitClient.apiService.obtenerMaquinas()

                if (respMaqs.isSuccessful) {
                    val maquinas   = listOf("-- Seleccione Máquina --") + (respMaqs.body()?.map { it.nombre } ?: emptyList())

                    // Operarios por tipo: solo poblamos el tipo cuya llamada tuvo éxito,
                    // así una respuesta fallida no borra la caché previa de ese tipo.
                    val opsPorTipo = operadoresPorTipo.toMutableMap()
                    if (respOpsSolido.isSuccessful) {
                        opsPorTipo["SOLIDO"] = listOf("-- Seleccione Operador --") +
                            (respOpsSolido.body()?.map { it.nombre } ?: emptyList())
                    }
                    if (respOpsLiquido.isSuccessful) {
                        opsPorTipo["LIQUIDO"] = listOf("-- Seleccione Operador --") +
                            (respOpsLiquido.body()?.map { it.nombre } ?: emptyList())
                    }
                    // Lista combinada (unión) como fallback/retro-compatibilidad de caché.
                    val todos = (opsPorTipo["SOLIDO"].orEmpty().drop(1) +
                                 opsPorTipo["LIQUIDO"].orEmpty().drop(1)).distinct()
                    val operadores = if (todos.isNotEmpty()) listOf("-- Seleccione Operador --") + todos
                                     else listaOperadoresServidor

                    // Extraemos la jerarquía si el backend ya la incluye en la respuesta.
                    // Si el campo `marcas` es null, el fallback local sigue activo.
                    // De paso recogemos el tipo de línea por máquina (default SOLIDO).
                    val jerarquia = mutableMapOf<String, List<MarcaResponse>>()
                    val tiposMap = mutableMapOf<String, String>()
                    respMaqs.body()?.forEach { maquina ->
                        if (!maquina.marcas.isNullOrEmpty()) {
                            jerarquia[maquina.nombre] = maquina.marcas
                        }
                        tiposMap[maquina.nombre] = (maquina.tipo ?: "SOLIDO").uppercase()
                    }

                    withContext(Dispatchers.Main) {
                        listaOperadoresServidor = operadores
                        listaMaquinasServidor   = maquinas
                        tipoPorMaquina          = tiposMap
                        operadoresPorTipo       = opsPorTipo
                        if (jerarquia.isNotEmpty()) jerarquiaMaquinas = jerarquia
                        guardarCatalogosEnCache(operadores, maquinas, jerarquia, tiposMap, opsPorTipo)
                        // Si ya hay un tipo elegido, refrescamos el spinner de máquina con el filtro vigente.
                        val pos = spinnerTipo.selectedItemPosition
                        if (pos > 0) {
                            val maquinasFiltradas = maquinasParaTipo(tipos[pos])
                            if (maquinasFiltradas.size > 1) {
                                spinnerMaquina.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, maquinasFiltradas)
                                spinnerMaquina.isEnabled = true
                            } else {
                                spinnerMaquina.adapter = ArrayAdapter(this@MainActivity, R.layout.spinner_item, arrayOf("-- Sin máquinas de este tipo --"))
                                spinnerMaquina.isEnabled = false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.report("Catalogos", "No se pudo actualizar el catálogo — usando caché local", e)
            }
        }
    }

    // Gate de ENTRADA: el checklist de mantenimiento SIEMPRE se exige antes de
    // iniciar el turno. Offline-first: el checklist se encola (no requiere red) y al
    // volver con RESULT_OK el inicio de turno sigue su propio camino online/cola.
    private fun gateEntradaYContinuar(req: IniciarTurnoRequest) {
        turnoPendiente = req
        val intent = Intent(this, com.example.controller.ui.MantenimientoChecklistActivity::class.java).apply {
            putExtra(com.example.controller.ui.MantenimientoChecklistActivity.EXTRA_MOMENTO, com.example.controller.MantenimientoMomento.ENTRADA)
            putExtra(com.example.controller.ui.MantenimientoChecklistActivity.EXTRA_MAQUINA, req.maquina)
            putExtra(com.example.controller.ui.MantenimientoChecklistActivity.EXTRA_OPERADOR, req.operador)
        }
        entradaLauncher.launch(intent)
    }

    // Gate de SALIDA: el checklist de limpieza SIEMPRE se exige antes de cerrar el
    // turno. máquina+operador se leen de prefs (persistidos al iniciar) con fallback al spinner.
    private fun gateSalidaYFinalizar() {
        val prefs = getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE)
        val maq = prefs.getString("turno_maquina", null) ?: spinnerMaquina.selectedItem?.toString().orEmpty()
        val op  = prefs.getString("turno_operador", null) ?: spinnerOperador.selectedItem?.toString().orEmpty()
        val intent = Intent(this, com.example.controller.ui.MantenimientoChecklistActivity::class.java).apply {
            putExtra(com.example.controller.ui.MantenimientoChecklistActivity.EXTRA_MOMENTO, com.example.controller.MantenimientoMomento.SALIDA)
            putExtra(com.example.controller.ui.MantenimientoChecklistActivity.EXTRA_MAQUINA, maq)
            putExtra(com.example.controller.ui.MantenimientoChecklistActivity.EXTRA_OPERADOR, op)
        }
        salidaLauncher.launch(intent)
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
                        putString("turno_maquina", datos.maquina)
                        putString("turno_operador", datos.operador)
                        apply()
                    }
                    seccionConfiguracion.visibility = View.GONE
                    seccionProduccion.visibility    = View.VISIBLE
                    totalPacasTurno = 0
                    actualizarTotalPacas()
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
                    putString("turno_maquina", datos.maquina)
                    putString("turno_operador", datos.operador)
                    apply()
                }
                seccionConfiguracion.visibility = View.GONE
                seccionProduccion.visibility    = View.VISIBLE
                totalPacasTurno = 0
                actualizarTotalPacas()
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
                sumarPacas(cantidad)
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
                    sumarPacas(cantidad)
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
                sumarPacas(cantidad)
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
                } else if (resp.code() == 404) {
                    // La sesión ya no existe en el servidor (p. ej. eliminada en la BD).
                    // No hay nada que cerrar: limpiamos el estado local y volvemos a la
                    // pantalla de inicio para que se pueda arrancar un turno nuevo.
                    tvHistorialPacas.text = ""
                    forzarReinicioPorError("La sesión ya no existe en el servidor — listo para iniciar un turno nuevo.")
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
        // Al arrancar y al volver de segundo plano (que es cuando más rato ha podido
        // pasar) el botón de paro se pinta con lo que sabe el servidor, no con lo que
        // recordaba la app.
        sincronizarEstadoParoDesdeServidor()
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
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val hasNetwork = cm.activeNetwork
                    ?.let { cm.getNetworkCapabilities(it) }
                    ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
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

    // Marca esta tablet como operario para el sistema de alertas de insumos:
    //  1) limpia "tipo_insumo_usuario" → SigepFirebaseMessagingService descarta los
    //     FCM SOLICITUD_INSUMO (el filtro exige que coincida con el tipo del usuario).
    //  2) reasigna el token FCM al usuario operario → el backend deja de incluir esta
    //     tablet al notificar a los insumistas (filtra por rol Granel/Empaque del token).
    private fun asegurarIdentidadOperarioFcm() {
        val prefs = getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE)
        prefs.edit().putString("tipo_insumo_usuario", "").apply()
        val usuarioId = prefs.getInt("usuario_id", -1)
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            RetrofitClient.apiService.registrarTokenFcm(
                                SolicitudTokenFcm(token = token, usuarioId = if (usuarioId == -1) null else usuarioId)
                            )
                        } catch (e: Exception) {
                            ErrorReporter.warn("MainActivity", "No se pudo reasignar token FCM al operario: ${e.message}")
                        }
                    }
                }
        } catch (e: Exception) {
            ErrorReporter.report("MainActivity", "Fallo al obtener token FCM (operario)", e)
        }
    }

    private fun forzarReinicioPorError(mensaje: String) {
        Toast.makeText(this@MainActivity, mensaje, Toast.LENGTH_LONG).show()
        sesionActualId = null
        getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE).edit().remove("sesion_activa").apply()
        etCantidadPacas.text.clear()
        totalPacasTurno = 0
        actualizarTotalPacas()
        spinnerTipo.setSelection(0)
        bloquearSpinnersDesdeMaquina()
        limpiarEstadoParo()
        limpiarEstadoAlmuerzo()
        // Re-habilitar el botón de cerrar turno: la rama de éxito de finalizar lo
        // deja deshabilitado, y sin esto el siguiente turno lo mostraría bloqueado.
        btnTerminarTurno.isEnabled = true
        seccionProduccion.visibility    = View.GONE
        seccionConfiguracion.visibility = View.VISIBLE
    }


    // ==========================================
    // CACHÉ LOCAL DE CATÁLOGOS
    // ==========================================

    private fun guardarCatalogosEnCache(
        operadores: List<String>,
        maquinas: List<String>,
        jerarquia: Map<String, List<MarcaResponse>>,
        tipos: Map<String, String>,
        operadoresTipo: Map<String, List<String>>
    ) {
        val gson = Gson()
        getSharedPreferences("SIGEP_CATALOGOS", MODE_PRIVATE).edit().apply {
            putInt("cache_version", cacheVersionJerarquia)
            putString("cache_operadores", gson.toJson(operadores))
            putString("cache_maquinas", gson.toJson(maquinas))
            if (jerarquia.isNotEmpty()) {
                putString("cache_jerarquia_maquinas", gson.toJson(jerarquia))
            }
            putString("cache_tipos_maquina", gson.toJson(tipos))
            if (operadoresTipo.isNotEmpty()) {
                putString("cache_operadores_por_tipo", gson.toJson(operadoresTipo))
            }
            apply()
        }
    }

    // Caché de fragancias por "máquina|marca". Se guarda aparte del resto del catálogo
    // porque se actualiza en otro momento (al elegir marca, no al descargar catálogos).
    private fun guardarFraganciasEnCache() {
        if (fraganciasPorMaquinaMarca.isEmpty()) return
        getSharedPreferences("SIGEP_CATALOGOS", MODE_PRIVATE).edit().apply {
            putInt("cache_version_fragancias", cacheVersionFragancias)
            putString("cache_fragancias", Gson().toJson(fraganciasPorMaquinaMarca))
            apply()
        }
    }

    // Operarios a mostrar para el tipo de línea de la máquina elegida. Si hay caché
    // por tipo (aunque solo tenga el placeholder) se respeta tal cual, para no mezclar
    // líneas. Solo cuando no hay dato por tipo (caché vieja / primera vez sin red) se
    // cae a la lista completa del servidor y, en último caso, al fallback local.
    private fun operadoresParaTipo(tipoMaquina: String): List<String> {
        val canon = if (tipoMaquina.startsWith("L", ignoreCase = true)) "LIQUIDO" else "SOLIDO"
        operadoresPorTipo[canon]?.let { return it }
        return if (listaOperadoresServidor.size > 1) listaOperadoresServidor else listaOperadoresLocal
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

        // Tipo de línea por máquina (para filtrar el spinner al elegir Sólido/Líquido).
        val jsonTipos = prefs.getString("cache_tipos_maquina", null)
        if (jsonTipos != null) {
            val tipoMapa = object : TypeToken<Map<String, String>>() {}.type
            tipoPorMaquina = gson.fromJson(jsonTipos, tipoMapa) ?: emptyMap()
        }

        // Operarios por línea. Si la caché es vieja y no lo tiene, queda vacío y el
        // spinner cae a la lista completa hasta la próxima descarga (retro-compatible).
        val jsonOpsTipo = prefs.getString("cache_operadores_por_tipo", null)
        if (jsonOpsTipo != null) {
            val opsTipoMapa = object : TypeToken<Map<String, List<String>>>() {}.type
            operadoresPorTipo = gson.fromJson(jsonOpsTipo, opsTipoMapa) ?: emptyMap()
        }

        // La jerarquía cacheada solo es válida si la escribió esta versión del esquema.
        // Cachés viejas (catálogo global erróneo) se ignoran → se usa el fallback local
        // correcto hasta que se re-descargue del servidor.
        val versionCache  = prefs.getInt("cache_version", 1)
        val jsonJerarquia = prefs.getString("cache_jerarquia_maquinas", null)
        if (versionCache >= cacheVersionJerarquia && jsonJerarquia != null) {
            val tipoJerarquia = object : TypeToken<Map<String, List<MarcaResponse>>>() {}.type
            jerarquiaMaquinas = gson.fromJson(jsonJerarquia, tipoJerarquia) ?: emptyMap()
        }

        // Fragancias por "máquina|marca", con su propio versionado: una caché escrita
        // por un esquema viejo se ignora y se cae al fallback hasta re-descargar.
        val versionFragancias = prefs.getInt("cache_version_fragancias", 0)
        val jsonFragancias    = prefs.getString("cache_fragancias", null)
        if (versionFragancias >= cacheVersionFragancias && jsonFragancias != null) {
            val tipoFragancias = object : TypeToken<Map<String, List<String>>>() {}.type
            fraganciasPorMaquinaMarca = gson.fromJson(jsonFragancias, tipoFragancias) ?: emptyMap()
        }
    }

    // ==========================================
    // PAROS
    // ==========================================

    private fun llamarApiIniciarParo(motivo: String, foto: Uri?) {
        btnParo.isEnabled = false
        operacionParoEnCurso = true
        val req = IniciarParoRequest(sesion_id = sesionActualId!!, motivo = motivo)

        lifecycleScope.launch {
            try {
                if (sesionActualId == -99) {
                    colaRepo.enqueueIniciarParo(req)
                    // Sin id real todavía: la foto viaja con paro_id = -99 y el SyncWorker
                    // le escribe el id que devuelva el INICIAR_PARO al sincronizarse.
                    aplicarParoActivoUi(null, "Paro en cola")
                    encolarFotoParo(foto, -99)
                    return@launch
                }

                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.iniciarParo(req)
                }
                if (resp.isSuccessful) {
                    val paroId = resp.body()?.paro_id
                    aplicarParoActivoUi(paroId, "Paro registrado")
                    if (paroId != null) {
                        encolarFotoParo(foto, paroId)
                    } else if (foto != null) {
                        // Server sin paro_id en la respuesta: el paro quedó registrado,
                        // pero no hay id al que adjuntar la evidencia.
                        descartarFotoParoPendiente()
                        Toast.makeText(this@MainActivity,
                            "Paro registrado, pero no se pudo adjuntar la foto", Toast.LENGTH_LONG).show()
                    }
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No se pudo registrar el paro")
                        .setMessage(extraerDetalleErrorHttp(resp))
                        .setPositiveButton("Entendido", null)
                        .show()
                    descartarFotoParoPendiente()
                }
            } catch (e: Exception) {
                ErrorReporter.warn("MainActivity", "Sin red al iniciar paro, encolando: ${e.message}")
                colaRepo.enqueueIniciarParo(req)
                aplicarParoActivoUi(null, "Sin red — paro guardado, se enviará al reconectar")
                encolarFotoParo(foto, -99)
            } finally {
                operacionParoEnCurso = false
                btnParo.isEnabled = true
            }
        }
    }

    private fun llamarApiFinalizarParo() {
        btnParo.isEnabled = false
        operacionParoEnCurso = true
        val req = FinalizarParoRequest(sesion_id = sesionActualId!!)

        lifecycleScope.launch {
            try {
                if (sesionActualId == -99) {
                    colaRepo.enqueueFinalizarParo(req)
                    aplicarReanudacionUi("Reanudación en cola")
                    return@launch
                }

                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.finalizarParo(req)
                }
                when {
                    resp.isSuccessful -> {
                        // ya_cerrado = lo cerró un supervisor desde la web. Es éxito:
                        // lo importante es que la tablet se desbloquee.
                        val yaCerrado = resp.body()?.ya_cerrado == true
                        aplicarReanudacionUi(
                            if (yaCerrado) "El paro ya estaba cerrado — producción reanudada"
                            else "Producción reanudada"
                        )
                    }
                    // Server anterior al fix del 2026-08-19: 404 "No hay paro activo"
                    // significa que ya estaba cerrado. Dejar el botón trabado por esto
                    // es lo que costó 1 h 34 min de producción en la Máquina 7.
                    resp.code() == 404 -> aplicarReanudacionUi("El paro ya estaba cerrado — producción reanudada")
                    else -> {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("No se pudo finalizar el paro")
                            .setMessage(extraerDetalleErrorHttp(resp))
                            .setPositiveButton("Entendido", null)
                            .show()
                        // El server rechazó por otro motivo: le preguntamos el estado
                        // real, así el botón no se queda trabado por un error puntual.
                        sincronizarEstadoParoDesdeServidor()
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.warn("MainActivity", "Sin red al finalizar paro, encolando: ${e.message}")
                colaRepo.enqueueFinalizarParo(req)
                aplicarReanudacionUi("Sin red — reanudación guardada, se enviará al reconectar")
            } finally {
                operacionParoEnCurso = false
                btnParo.isEnabled = true
            }
        }
    }

    // ===== Estado del paro: UI, persistencia y sincronización con el servidor =====

    // Enciende la UI de paro activo. [paroId] puede ser null (paro encolado sin red).
    private fun aplicarParoActivoUi(paroId: Int?, mensaje: String?) {
        paroActivo = true
        paroActivoId = paroId
        generacionParo++
        persistirEstadoParo(true, paroId)
        btnParo.text = "REANUDAR OPERACIÓN"
        btnParo.setBackgroundColor(Color.parseColor("#4CAF50"))
        btnEnviarPallet.isEnabled = false
        btnEnviarPallet.setBackgroundColor(Color.GRAY)
        btnAlmuerzo.isEnabled = false   // exclusión mutua: un solo paro activo por sesión
        mensaje?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }

    private fun aplicarReanudacionUi(mensaje: String?) {
        paroActivo = false
        paroActivoId = null
        generacionParo++
        persistirEstadoParo(false, null)
        btnParo.text = "REGISTRAR PARO"
        btnParo.setBackgroundColor(Color.parseColor("#FA8400"))
        btnEnviarPallet.isEnabled = true
        btnEnviarPallet.setBackgroundColor(Color.parseColor("#2196F3"))
        btnAlmuerzo.isEnabled = true
        mensaje?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }

    // El paro se persiste (como el almuerzo) para que un paro real no se pierda de vista
    // si la app se cierra: antes el flag vivía solo en memoria y la única salida de un
    // botón trabado era matar la app.
    private fun persistirEstadoParo(activo: Boolean, paroId: Int?) {
        getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE).edit().apply {
            putBoolean("paro_activo", activo)
            if (activo && paroId != null) putInt("paro_activo_id", paroId) else remove("paro_activo_id")
            apply()
        }
    }

    // Reconstruye el botón de paro al reabrir la app con un paro en curso. Es solo el
    // punto de partida: el estado real lo confirma el servidor en onStart / el radar.
    private fun restaurarEstadoParo() {
        val prefs = getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean("paro_activo", false)) return
        if (almuerzoActivo) return   // el almuerzo ya pinta su propio estado
        val id = prefs.getInt("paro_activo_id", -1).takeIf { it > 0 }
        aplicarParoActivoUi(id, "Paro en curso")
    }

    // Consulta GET /api/paro/estado y le CREE AL SERVIDOR. Es el arreglo del bloqueo:
    // el botón deja de depender de lo que la app recuerda.
    private fun sincronizarEstadoParoDesdeServidor() {
        // Puede llamarse desde el radar (IO) o desde el observador de red (hilo de
        // binder): el estado de la UI solo se lee y escribe en el hilo principal.
        runOnUiThread {
            val sesion = sesionActualId ?: return@runOnUiThread
            if (sesion <= 0) return@runOnUiThread   // -99: el server no conoce esta sesión
            val generacion = generacionParo

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val resp = RetrofitClient.apiService.obtenerEstadoParo(sesion)
                    if (resp.code() == 404) {
                        // Un 404 acá es ambiguo: puede ser "sesión borrada desde /admin"
                        // o "esta ruta todavía no existe en el servidor" (mientras no se
                        // reinicie el servicio). NO se saca al operario de su turno por
                        // algo que quizá solo signifique que el backend está desfasado;
                        // la sesión inexistente ya la detecta finalizar_turno.
                        ErrorReporter.warn("EstadoParo", "404 al consultar el estado del paro (sesión $sesion)")
                        return@launch
                    }
                    val estado = resp.body()
                    if (!resp.isSuccessful || estado == null) return@launch
                    withContext(Dispatchers.Main) {
                        // No aplica si el turno cambió mientras viajaba la respuesta, ni
                        // si el operario tiene un iniciar/finalizar paro en vuelo (esta
                        // respuesta podría ser anterior a ese cambio).
                        if (sesionActualId == sesion &&
                            !operacionParoEnCurso &&
                            generacionParo == generacion
                        ) {
                            aplicarEstadoParoServidor(estado)
                        }
                    }
                } catch (e: Exception) {
                    // SIN RED NO SE CAMBIA NADA: mejor un botón posiblemente desfasado
                    // que uno que se apaga porque se cayó el wifi — eso perdería el
                    // registro del tiempo parado. La corrección llega en la próxima
                    // consulta con red.
                    ErrorReporter.warn("EstadoParo", "No se pudo consultar el estado del paro: ${e.message}")
                }
            }
        }
    }

    private fun aplicarEstadoParoServidor(estado: EstadoParoResponse) {
        val abierto = estado.paro_abierto

        // paro_abierto = null NO es un error: significa que la máquina no está en paro.
        if (!estado.en_paro || abierto == null) {
            if (paroActivo) aplicarReanudacionUi("El paro ya estaba cerrado — producción reanudada")
            if (almuerzoActivo) aplicarAlmuerzoInactivo("El almuerzo ya estaba cerrado — producción reanudada")
            return
        }

        // El paro abierto es el del almuerzo (mismo mecanismo, motivo fijo): su UI la
        // maneja el contador del almuerzo, acá no se toca nada.
        if (abierto.motivo?.contains(MOTIVO_ALMUERZO, ignoreCase = true) == true) return

        // Paro real abierto en el servidor: el botón debe estar en REANUDAR y con el
        // paro_id que manda el server, aunque el local fuera otro (o no hubiera).
        if (!paroActivo || paroActivoId != abierto.paro_id) {
            aplicarParoActivoUi(
                abierto.paro_id,
                if (paroActivo) null else "Hay un paro abierto en esta máquina"
            )
        }
    }

    // Menú de la pulsación larga sobre el botón de paro: la salida manual por si todo
    // lo demás falla, y la posibilidad de rehacer la foto que salió desenfocada.
    private fun mostrarOpcionesParo() {
        val etiquetas = mutableListOf<String>()
        val acciones  = mutableListOf<() -> Unit>()

        if (paroActivoId != null) {
            etiquetas += "📷 Adjuntar o reemplazar la foto del paro"
            acciones  += { pedirFotoParoEnCurso() }
        }
        etiquetas += "⚠️ Forzar reanudación (el botón quedó trabado)"
        acciones  += { confirmarForzarReanudacion() }

        AlertDialog.Builder(this)
            .setTitle("Opciones del paro")
            .setItems(etiquetas.toTypedArray()) { _, i -> acciones[i]() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Desbloquea la pantalla en local y manda el cierre del paro por la cola. Es lo que
    // hoy se consigue matando la app, pero sin perder la sesión de trabajo.
    private fun confirmarForzarReanudacion() {
        AlertDialog.Builder(this)
            .setTitle("Forzar reanudación")
            .setMessage(
                "Úsalo solo si el botón quedó trabado y no puedes registrar pacas.\n\n" +
                "Se desbloqueará la pantalla y el cierre del paro se enviará al servidor " +
                "en cuanto haya red."
            )
            .setPositiveButton("FORZAR") { _, _ ->
                val sesion = sesionActualId
                lifecycleScope.launch {
                    if (sesion != null) colaRepo.enqueueFinalizarParo(FinalizarParoRequest(sesion))
                    aplicarReanudacionUi("Reanudación forzada — el cierre se enviará al servidor")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ===== Evidencia fotográfica del paro (opcional) =====

    // Añade al diálogo el bloque de foto: es opcional a propósito, registrar el paro sin
    // foto no debe tener ninguna fricción.
    private fun agregarSelectorFotoParo(contenedor: LinearLayout) {
        fotoParoUri = null
        fotoParoFile = null

        contenedor.addView(TextView(this).apply {
            text = "Evidencia fotográfica (opcional):"
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 20, 0, 0)
        })

        val btnCam = Button(this).apply {
            text = "📷 TOMAR FOTO"
            setBackgroundColor(Color.parseColor("#455A64"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        contenedor.addView(btnCam)

        val tvFoto = TextView(this).apply {
            text = "Sin foto adjunta"
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 0)
        }
        tvFotoParoEstado = tvFoto
        contenedor.addView(tvFoto)

        btnCam.setOnClickListener {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), PERMISO_CAMARA_PARO)
                Toast.makeText(this, "Concede el permiso y vuelve a tocar TOMAR FOTO", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val tmp = File.createTempFile(
                    "PARO_${System.currentTimeMillis()}", ".jpg",
                    getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                )
                fotoParoFile = tmp
                fotoParoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmp)
                launcherCamaraParo.launch(fotoParoUri!!)
            } catch (e: Exception) {
                ErrorReporter.report("FotoParo", "No se pudo abrir la cámara", e)
                Toast.makeText(this, "No se pudo abrir la cámara", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Foto para un paro YA abierto (repetir la que salió mal). El endpoint sustituye
    // la anterior, así que se puede rehacer tantas veces como haga falta.
    private fun pedirFotoParoEnCurso() {
        val paroId = paroActivoId ?: return
        val contenedor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }
        agregarSelectorFotoParo(contenedor)

        AlertDialog.Builder(this)
            .setTitle("Foto del paro")
            .setView(contenedor)
            .setPositiveButton("ADJUNTAR") { _, _ ->
                if (fotoParoUri == null) {
                    Toast.makeText(this, "No tomaste ninguna foto", Toast.LENGTH_SHORT).show()
                } else {
                    lifecycleScope.launch { encolarFotoParo(fotoParoUri, paroId) }
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> descartarFotoParoPendiente() }
            .setOnDismissListener { tvFotoParoEstado = null }
            .show()
    }

    // Comprime y encola la foto en su PROPIA operación: si no hay red el paro ya quedó
    // registrado y la foto sube después, nunca al revés.
    private suspend fun encolarFotoParo(origen: Uri?, paroId: Int) {
        val uri = origen ?: return
        val comprimida = withContext(Dispatchers.IO) { comprimirFotoParo(uri) }
        descartarFotoParoPendiente()   // el original ya no hace falta
        if (comprimida == null) {
            Toast.makeText(this, "No se pudo preparar la foto (el paro sí quedó registrado)", Toast.LENGTH_LONG).show()
            return
        }
        colaRepo.enqueueFotoParo(paroId, comprimida.absolutePath)
        Toast.makeText(this, "Foto adjuntada al paro", Toast.LENGTH_SHORT).show()
    }

    // Redimensiona a ~1600 px de lado largo y JPEG 80: el server rechaza con 400 lo que
    // pase de 5 MB, y una foto de tablet moderna los pasa de sobra.
    private fun comprimirFotoParo(uri: Uri): File? {
        return try {
            val medida = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, medida) }
            val ladoMayor = maxOf(medida.outWidth, medida.outHeight)
            if (ladoMayor <= 0) return null

            var escala = 1
            while (ladoMayor / (escala * 2) >= LADO_MAX_FOTO) escala *= 2

            val bitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = escala })
            } ?: return null

            // En filesDir (no en cacheDir): una foto encolada tiene que sobrevivir a que
            // el sistema limpie la caché antes de que vuelva la red.
            val dir = File(filesDir, "fotos_paro").apply { mkdirs() }
            val destino = File(dir, "paro_${System.currentTimeMillis()}.jpg")
            java.io.FileOutputStream(destino).use { salida ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, CALIDAD_JPEG_FOTO, salida)
            }
            bitmap.recycle()
            destino
        } catch (e: Exception) {
            ErrorReporter.report("FotoParo", "No se pudo comprimir la foto del paro", e)
            null
        }
    }

    private fun descartarFotoParoPendiente() {
        fotoParoFile?.delete()
        fotoParoFile = null
        fotoParoUri = null
    }

    private fun limpiarEstadoParo() {
        paroActivo = false
        paroActivoId = null
        generacionParo++
        persistirEstadoParo(false, null)
        btnParo.text = "REGISTRAR PARO"
        btnParo.setBackgroundColor(Color.parseColor("#FA8400"))
        btnParo.isEnabled = true
        btnEnviarPallet.isEnabled = true
        btnEnviarPallet.setBackgroundColor(Color.parseColor("#2196F3"))
        btnAlmuerzo.isEnabled = true
    }

    // ==========================================
    // VELOCIDAD DE MÁQUINA (fundas/min)
    // ==========================================

    // Lectura puntual de la velocidad a la que está trabajando la máquina. Se pueden
    // registrar varias por turno; no hay nada que "editar" desde la tablet.
    private fun pedirVelocidadMaquina() {
        val contenedor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }
        contenedor.addView(TextView(this).apply {
            // La unidad va rotulada: es lo único que evita que unos registren por minuto
            // y otros por hora y el histórico quede inservible.
            text = "Velocidad actual de la máquina, en FUNDAS/MIN:"
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        val input = EditText(this).apply {
            hint = "Ej: 45.5"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        contenedor.addView(input)
        contenedor.addView(TextView(this).apply {
            text = "Se permiten decimales (45.5). Rango válido: 0.1 – 1000 fundas/min."
            setTextColor(Color.GRAY)
            setPadding(0, 12, 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("⚡ Velocidad de máquina")
            .setView(contenedor)
            .setPositiveButton("REGISTRAR") { _, _ ->
                // Coma decimal: en la tablet el teclado numérico puede dar "45,5".
                val velocidad = input.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (velocidad == null || velocidad < 0.1 || velocidad > 1000) {
                    Toast.makeText(this, "Ingrese una velocidad entre 0.1 y 1000 fundas/min", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                registrarVelocidad(velocidad)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun registrarVelocidad(velocidad: Double) {
        val sesion = sesionActualId ?: return
        btnVelocidad.isEnabled = false
        // request_id desde acá para que el intento directo y el encolado sean la MISMA
        // lectura: sin él, un reintento entraría dos veces y sesgaría el promedio.
        val req = VelocidadRequest(
            sesion_id  = sesion,
            velocidad  = velocidad,
            request_id = java.util.UUID.randomUUID().toString()
        )

        lifecycleScope.launch {
            try {
                if (sesion == -99) {
                    // Turno todavía sin id real: solo cabe encolar (el SyncWorker
                    // reescribe el -99 con el id real del turno).
                    colaRepo.enqueueVelocidad(req)
                    Toast.makeText(this@MainActivity, "Velocidad guardada (pendiente de enviar)", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.registrarVelocidad(req)
                }
                if (resp.isSuccessful) {
                    Toast.makeText(this@MainActivity,
                        "Velocidad registrada: $velocidad fundas/min", Toast.LENGTH_SHORT).show()
                } else {
                    // 400/404: el server rechazó (errata del operario o turno cerrado).
                    // Encolarlo solo repetiría el mismo rechazo.
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No se pudo registrar la velocidad")
                        .setMessage(extraerDetalleErrorHttp(resp))
                        .setPositiveButton("Entendido", null)
                        .show()
                }
            } catch (e: Exception) {
                ErrorReporter.warn("MainActivity", "Sin red al registrar velocidad, encolando: ${e.message}")
                colaRepo.enqueueVelocidad(req)
                Toast.makeText(this@MainActivity,
                    "Sin red — velocidad guardada, se enviará al reconectar", Toast.LENGTH_LONG).show()
            } finally {
                btnVelocidad.isEnabled = true
            }
        }
    }

    // ==========================================
    // PARO ALMUERZO (contador en pantalla; reusa el mecanismo de paro con
    // motivo fijo "ALMUERZO" y la misma cola offline-first).
    // ==========================================

    private fun iniciarAlmuerzo() {
        val sesion = sesionActualId
        if (sesion == null) {
            Toast.makeText(this, "Debe tener un turno activo.", Toast.LENGTH_SHORT).show()
            return
        }
        if (paroActivo) {
            Toast.makeText(this, "Finalice el paro actual antes del almuerzo.", Toast.LENGTH_SHORT).show()
            return
        }
        btnAlmuerzo.isEnabled = false
        val inicioMs = System.currentTimeMillis()
        val req = IniciarParoRequest(sesion_id = sesion, motivo = MOTIVO_ALMUERZO)

        lifecycleScope.launch {
            // Sesión aún sin id real (turno encolado): encolamos y el SyncWorker
            // reescribe -99 con el id real. El contador arranca igual (es local).
            if (sesion == -99) {
                colaRepo.enqueueIniciarParo(req)
                aplicarAlmuerzoActivo(inicioMs, "Almuerzo iniciado (en cola)")
                btnAlmuerzo.isEnabled = true
                return@launch
            }

            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.iniciarParo(req)
                }
                // 409 = ya había un paro abierto en el server; igual reflejamos el
                // almuerzo localmente para poder reanudarlo (lo cerrará finalizar).
                if (resp.isSuccessful || resp.code() == 409) {
                    aplicarAlmuerzoActivo(inicioMs, "Almuerzo iniciado")
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No se pudo iniciar el almuerzo")
                        .setMessage(extraerDetalleErrorHttp(resp))
                        .setPositiveButton("Entendido", null)
                        .show()
                    btnAlmuerzo.isEnabled = true
                }
            } catch (e: Exception) {
                ErrorReporter.warn("MainActivity", "Sin red al iniciar almuerzo, encolando: ${e.message}")
                colaRepo.enqueueIniciarParo(req)
                aplicarAlmuerzoActivo(inicioMs, "Sin red — almuerzo guardado, se enviará al reconectar")
            }
        }
    }

    private fun finalizarAlmuerzo() {
        val sesion = sesionActualId ?: return
        btnAlmuerzo.isEnabled = false
        val req = FinalizarParoRequest(sesion_id = sesion)

        lifecycleScope.launch {
            if (sesion == -99) {
                colaRepo.enqueueFinalizarParo(req)
                aplicarAlmuerzoInactivo("Almuerzo finalizado (en cola)")
                return@launch
            }

            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.finalizarParo(req)
                }
                // 404 = no había paro abierto en el server (ya cerrado); limpiamos igual.
                if (resp.isSuccessful || resp.code() == 404) {
                    aplicarAlmuerzoInactivo("Almuerzo finalizado")
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No se pudo finalizar el almuerzo")
                        .setMessage(extraerDetalleErrorHttp(resp))
                        .setPositiveButton("Entendido", null)
                        .show()
                    btnAlmuerzo.isEnabled = true
                }
            } catch (e: Exception) {
                ErrorReporter.warn("MainActivity", "Sin red al finalizar almuerzo, encolando: ${e.message}")
                colaRepo.enqueueFinalizarParo(req)
                aplicarAlmuerzoInactivo("Sin red — reanudación guardada, se enviará al reconectar")
            }
        }
    }

    // Activa la UI del almuerzo: contador anclado a la hora de inicio (sobrevive
    // rotación/reinicio vía prefs), bloqueo de pacas y del paro general.
    private fun aplicarAlmuerzoActivo(inicioMs: Long, mensaje: String) {
        almuerzoActivo = true
        getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE).edit().apply {
            putBoolean("almuerzo_activo", true)
            putLong("almuerzo_inicio_ms", inicioMs)
            apply()
        }
        bannerAlmuerzo.visibility = View.VISIBLE
        val transcurrido = System.currentTimeMillis() - inicioMs
        chronoAlmuerzo.base = android.os.SystemClock.elapsedRealtime() - transcurrido
        chronoAlmuerzo.start()

        btnAlmuerzo.text = "REANUDAR (FIN ALMUERZO)"
        btnAlmuerzo.setBackgroundColor(Color.parseColor("#B71C1C"))
        btnAlmuerzo.isEnabled = true

        btnEnviarPallet.isEnabled = false
        btnEnviarPallet.setBackgroundColor(Color.GRAY)
        btnParo.isEnabled = false
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

    private fun aplicarAlmuerzoInactivo(mensaje: String) {
        limpiarEstadoAlmuerzo()
        btnEnviarPallet.isEnabled = true
        btnEnviarPallet.setBackgroundColor(Color.parseColor("#2196F3"))
        btnParo.isEnabled = true
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

    // Restaura el contador si la app se reabrió con un almuerzo en curso (se llama
    // al recuperar la sesión de trabajo).
    private fun restaurarEstadoAlmuerzo() {
        val prefs = getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean("almuerzo_activo", false)) {
            val inicioMs = prefs.getLong("almuerzo_inicio_ms", System.currentTimeMillis())
            aplicarAlmuerzoActivo(inicioMs, "Almuerzo en curso")
        }
    }

    // Apaga el contador y limpia el estado/persistencia del almuerzo (al reanudar
    // o al cerrar el turno). No toca botones ajenos al almuerzo.
    private fun limpiarEstadoAlmuerzo() {
        almuerzoActivo = false
        getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE).edit().apply {
            putBoolean("almuerzo_activo", false)
            remove("almuerzo_inicio_ms")
            apply()
        }
        chronoAlmuerzo.stop()
        bannerAlmuerzo.visibility = View.GONE
        btnAlmuerzo.text = "🍽️ PARO ALMUERZO"
        btnAlmuerzo.setBackgroundColor(Color.parseColor("#2E7D32"))
        btnAlmuerzo.isEnabled = true
    }

    // ==========================================
    // HISTORIAL Y MÓDULO DE INSUMOS
    // ==========================================

    // Total de pacas del turno: evita tener que sumar el historial línea por línea.
    private fun sumarPacas(cantidad: Int) {
        totalPacasTurno += cantidad
        actualizarTotalPacas()
    }

    private fun actualizarTotalPacas() {
        tvTotalPacas.text = "🧮 Total: $totalPacasTurno pacas"
    }

    private fun recuperarHistorial(idSesion: Int) {
        tvHistorialPacas.text = "Cargando historial..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val respuesta = RetrofitClient.apiService.obtenerHistorialSesion(idSesion)
                withContext(Dispatchers.Main) {
                    var texto = ""
                    var suma = 0
                    if (respuesta.isSuccessful && respuesta.body() != null) {
                        for (p in respuesta.body()!!) {
                            texto += "🟢 ${p.cantidad_pacas} pacas - ${p.hora}\n"
                            suma += p.cantidad_pacas
                        }
                    }
                    totalPacasTurno = suma
                    actualizarTotalPacas()
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
