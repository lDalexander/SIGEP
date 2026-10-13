package com.example.controller

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.controller.util.BarraInferior
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Torre de control del supervisor.
 *
 * Hasta la v1.7.0 esto era una lista de solo lectura: máquinas o historial de bodega, y
 * nada más. El supervisor veía el estado pero no podía hacer nada con él —ni mirar el
 * detalle de un turno, ni saber quién estaba de turno en bodega, ni cerrar un pedido que
 * se había quedado colgado—. Ahora las CINCO vistas son accionables: planta, plan del
 * día, insumistas, entregas por verificar y logística.
 *
 * LAS ACCIONES EXIGEN TOKEN (`RetrofitClient.tokenAdmin`), que ese login empezó a emitir
 * en la v1.7.0. Contra un servidor anterior el token llega nulo y las acciones se
 * esconden en vez de fallar con un 401 que el supervisor no sabría interpretar.
 */
class SupervisorDashboardActivity : AppCompatActivity() {

    private lateinit var rvDashboard: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var tvTitulo: TextView
    private lateinit var tvSubtitulo: TextView
    private lateinit var navVistas: BottomNavigationView
    private lateinit var btnSalir: android.widget.Button
    private lateinit var btnChats: android.widget.ImageButton
    private lateinit var tvBadgeChats: TextView

    private var adapterPlanta: MaquinaStatusAdapter? = null
    private var adapterBodega: PedidoSupervisorAdapter? = null
    private var adapterInsumistas: InsumistaAdapter? = null
    private var adapterEntregas: EntregaVerificarAdapter? = null
    private var adapterPlan: PlanAdapter? = null

    private var rolUsuario: String = ""
    private var nombreUsuario: String = ""
    private var vistaActual: String = ""
    private var jobRadar: Job? = null

    /** Sin token no se puede cerrar nada: los botones de acción se ocultan. */
    private val puedeActuar: Boolean
        get() = !RetrofitClient.tokenAdmin.isNullOrBlank()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.parseColor("#1E293B")
        setContentView(R.layout.activity_supervisor_dashboard)

        val prefs = getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE)
        nombreUsuario = prefs.getString("usuario_nombre", "Supervisor") ?: "Supervisor"
        rolUsuario = (prefs.getString("usuario_rol", "") ?: "").uppercase()
        // El proceso pudo reiniciarse entre el login y esta pantalla.
        if (RetrofitClient.tokenAdmin.isNullOrBlank()) {
            RetrofitClient.tokenAdmin = prefs.getString("admin_token", null)
        }

        rvDashboard  = findViewById(R.id.rvDashboard)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        tvTitulo     = findViewById(R.id.tvTituloDashboard)
        tvSubtitulo  = findViewById(R.id.tvSubtituloDashboard)
        navVistas    = findViewById(R.id.navVistas)
        btnSalir     = findViewById(R.id.btnSalirSupervisor)
        btnChats     = findViewById(R.id.btnChatsSupervisor)
        tvBadgeChats = findViewById(R.id.tvBadgeChats)

        rvDashboard.layoutManager = LinearLayoutManager(this)
        BarraInferior.pegarAlBorde(this, navVistas)

        configurarSegunRol()
        cargarDatos()

        // Las cinco vistas están en la barra inferior para todos los niveles: cada uno
        // arranca en la suya y puede mirar el resto. Quien no pueda ACTUAR seguirá sin
        // ver los botones de acción, que es donde está el permiso de verdad.
        // La pestaña inicial se marca ANTES de poner el listener: si no, marcarla
        // dispararía una segunda carga encima de la de arriba.
        navVistas.selectedItemId = idDeVista(vistaActual)
        navVistas.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.vista_planta      -> activarVistaPlanta()
                R.id.vista_plan        -> activarVistaPlan()
                R.id.vista_insumistas  -> activarVistaInsumistas()
                R.id.vista_entregas    -> activarVistaEntregas()
                R.id.vista_bodega      -> activarVistaBodega()
                else -> return@setOnItemSelectedListener false
            }
            cargarDatos()
            true
        }
        // Tocar la pestaña en la que ya se está la recarga, como el gesto de arrastrar.
        navVistas.setOnItemReselectedListener { cargarDatos() }

        btnSalir.setOnClickListener { cerrarSesion() }
        btnChats.setOnClickListener {
            if (!puedeActuar) { avisarSinPermiso(); return@setOnClickListener }
            ChatBodega.abrirLista(this, esSupervisor = true) { refrescarBadgeChats() }
        }
        swipeRefresh.setOnRefreshListener { cargarDatos() }
    }

    private fun configurarSegunRol() {
        if (rolUsuario == "SUPERVISOR_BODEGA" || rolUsuario == "ADMINBODEGA") activarVistaInsumistas()
        else activarVistaPlanta()
    }

    private fun idDeVista(vista: String): Int = when (vista) {
        "PLAN"       -> R.id.vista_plan
        "INSUMISTAS" -> R.id.vista_insumistas
        "ENTREGAS"   -> R.id.vista_entregas
        "BODEGA"     -> R.id.vista_bodega
        else         -> R.id.vista_planta
    }

    private fun limpiarAdaptadores() {
        adapterPlanta = null
        adapterBodega = null
        adapterInsumistas = null
        adapterEntregas = null
        adapterPlan = null
    }

    private fun activarVistaPlan() {
        vistaActual = "PLAN"
        tvTitulo.text = "PLAN DE HOY - ¿ESTÁ CUBIERTO?"
        tvSubtitulo.text = "Supervisor: $nombreUsuario | Toca un plan para ver qué falta"
        limpiarAdaptadores()
        adapterPlan = PlanAdapter(emptyList()) { plan -> mostrarInsumosDelPlan(plan) }
        rvDashboard.adapter = adapterPlan
    }

    private fun activarVistaPlanta() {
        vistaActual = "PLANTA"
        tvTitulo.text = "TORRE DE CONTROL - PLANTA"
        tvSubtitulo.text = "Supervisor: $nombreUsuario | Toca una máquina para ver su turno"
        limpiarAdaptadores()
        adapterPlanta = MaquinaStatusAdapter(emptyList()) { maquina ->
            mostrarDetalleDeTurno(maquina.sesion_id)
        }
        rvDashboard.adapter = adapterPlanta
    }

    private fun activarVistaInsumistas() {
        vistaActual = "INSUMISTAS"
        tvTitulo.text = "BODEGA - QUIÉN ESTÁ DE TURNO"
        tvSubtitulo.text = "Supervisor: $nombreUsuario | Toca a alguien para escribirle"
        limpiarAdaptadores()
        adapterInsumistas = InsumistaAdapter(emptyList()) { insumista ->
            if (puedeActuar) abrirChatCon(insumista) else avisarSinPermiso()
        }
        rvDashboard.adapter = adapterInsumistas
    }

    private fun abrirChatCon(insumista: InsumistaTurno) {
        val tipo = if (insumista.bodega.equals("GRANEL", true)) "Insumista de Granel"
                   else "Insumista de Empaque"
        ChatBodega.abrirConversacion(
            this,
            HiloChat(
                titulo = insumista.nombre,
                subtitulo = "$tipo · de turno",
                avatar = ChatBodega.iniciales(insumista.nombre),
                insumistaId = insumista.usuario_id
            ),
            esSupervisor = true
        ) { refrescarBadgeChats() }
    }

    private fun activarVistaEntregas() {
        vistaActual = "ENTREGAS"
        tvTitulo.text = "ENTREGAS POR VERIFICAR"
        tvSubtitulo.text = "Supervisor: $nombreUsuario | Últimas 24 h sin revisar"
        limpiarAdaptadores()
        adapterEntregas = EntregaVerificarAdapter(emptyList()) { entrega ->
            if (puedeActuar) revisarEntrega(entrega) else avisarSinPermiso()
        }
        rvDashboard.adapter = adapterEntregas
    }

    private fun activarVistaBodega() {
        vistaActual = "BODEGA"
        tvTitulo.text = "MONITOREO DE LOGÍSTICA"
        tvSubtitulo.text = "Supervisor: $nombreUsuario | Historial 24h"
        limpiarAdaptadores()
        adapterBodega = PedidoSupervisorAdapter(emptyList())
        rvDashboard.adapter = adapterBodega
    }

    private fun avisarSinPermiso() {
        Toast.makeText(
            this,
            "Vuelve a iniciar sesión para poder confirmar o verificar (el servidor se reinició).",
            Toast.LENGTH_LONG
        ).show()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Detalle de un turno
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Ventana con todo lo del turno. Viene de UNA sola llamada al servidor: encadenar
     * peticiones aquí se notaría como una pantalla que se llena a trozos bajo el dedo.
     */
    private fun mostrarDetalleDeTurno(sesionId: Int) {
        if (sesionId <= 0) return
        val dialogo = AlertDialog.Builder(this)
            .setTitle("Turno")
            .setMessage("Consultando…")
            .setPositiveButton("Cerrar", null)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.apiService.obtenerDetalleSesion(sesionId)
                val d = resp.body()
                withContext(Dispatchers.Main) {
                    if (!resp.isSuccessful || d == null) {
                        dialogo.setMessage("No se pudo cargar el turno (HTTP ${resp.code()}).")
                        return@withContext
                    }
                    dialogo.dismiss()
                    pintarDetalle(d)
                }
            } catch (e: Exception) {
                ErrorReporter.warn("SupervisorDetalle", "Fallo al cargar el turno: ${e.message}")
                withContext(Dispatchers.Main) { dialogo.setMessage("Sin conexión.") }
            }
        }
    }

    private fun pintarDetalle(d: DetalleSesion) {
        val producto = listOfNotNull(d.producto, d.marca, d.presentacion, d.fragancia)
            .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Producto sin definir" }

        val cuerpo = buildString {
            append("$producto\n\n")
            append("Operario: ${d.operador ?: "—"}\n")
            append("Inicio: ${d.inicio ?: "—"}${if (!d.activa) "   Fin: ${d.fin ?: "—"}" else ""}\n")
            append("Pacas: ${d.pacas}")
            d.velocidad?.let { append("   ·   Velocidad: $it fundas/min") }
            append("\n")
            if (d.en_paro) append("⛔ EN PARO: ${d.motivo_paro ?: "sin motivo"}\n")
            append("Paros: ${d.paros} (${d.minutos_parado} min)\n")
            if (!d.observaciones.isNullOrBlank()) append("\nObservaciones: ${d.observaciones}\n")

            if (d.comentarios.isNotEmpty()) {
                append("\n— Comentarios del operario —\n")
                d.comentarios.forEach { append("  ${it.hora ?: ""}  ${it.texto}\n") }
            }

            append("\n— Pedidos del turno (${d.pedidos.size}) —\n")
            if (d.pedidos.isEmpty()) append("  Sin pedidos\n")
            d.pedidos.forEach { p ->
                append("  [${p.estado}] ${p.cantidad} x ${p.insumo}")
                p.insumista?.let { append("  ·  $it") }
                append("\n")
            }
        }

        val porConfirmar = d.pedidos.filter { it.por_confirmar }
        val constructor = AlertDialog.Builder(this)
            .setTitle("${d.maquina ?: "Máquina"}  ·  ${if (d.activa) "turno abierto" else "turno cerrado"}")
            .setMessage(cuerpo)
            .setNegativeButton("Cerrar", null)

        // Confirmar en lugar del operario es la acción que desatasca el flujo: si el
        // operario no está en la máquina, el pedido se queda colgado en la bandeja del
        // insumista y los tiempos del dashboard no cierran nunca.
        if (porConfirmar.isNotEmpty() && puedeActuar) {
            constructor.setPositiveButton("Confirmar recepción (${porConfirmar.size})") { _, _ ->
                elegirPedidoAConfirmar(porConfirmar, d.sesion_id)
            }
        } else if (porConfirmar.isNotEmpty()) {
            constructor.setPositiveButton("Confirmar…") { _, _ -> avisarSinPermiso() }
        }
        constructor.show()
    }

    private fun elegirPedidoAConfirmar(pedidos: List<PedidoDeTurno>, sesionId: Int) {
        val etiquetas = pedidos.map { "${it.cantidad} x ${it.insumo}  (${it.insumista ?: "—"})" }
        AlertDialog.Builder(this)
            .setTitle("¿Cuál confirmas?")
            .setItems(etiquetas.toTypedArray()) { _, i -> confirmarRecepcion(pedidos[i], sesionId) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmarRecepcion(pedido: PedidoDeTurno, sesionId: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((pedido.entregada ?: pedido.cantidad).toString())
        }
        AlertDialog.Builder(this)
            .setTitle("Confirmar en lugar del operario")
            .setMessage(
                "${pedido.cantidad} x ${pedido.insumo}\n" +
                "El insumista entregó: ${pedido.entregada ?: "—"}\n\n" +
                "¿Cuánto hay realmente en la máquina?\n\n" +
                "Quedará registrado que lo confirmaste tú, no el operario."
            )
            .setView(input)
            .setPositiveButton("Confirmar") { _, _ ->
                val cant = input.text.toString().toIntOrNull()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val resp = RetrofitClient.apiService.confirmarComoSupervisor(
                            pedido.id, ConfirmarSupervisorRequest(cant)
                        )
                        withContext(Dispatchers.Main) {
                            if (resp.isSuccessful) {
                                Toast.makeText(this@SupervisorDashboardActivity,
                                    "Recepción confirmada", Toast.LENGTH_SHORT).show()
                                mostrarDetalleDeTurno(sesionId)
                            } else if (resp.code() == 401) {
                                avisarSinPermiso()
                            } else {
                                Toast.makeText(this@SupervisorDashboardActivity,
                                    "No se pudo confirmar (HTTP ${resp.code()})", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        ErrorReporter.warn("SupervisorConfirmar", "Fallo: ${e.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SupervisorDashboardActivity,
                                "Sin conexión", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Qué lleva el plan y cuánto falta de cada cosa.
     *
     * Lo que falta va PRIMERO: con quince insumos en la lista, lo que está completo
     * solo estorba para encontrar lo que no.
     */
    private fun mostrarInsumosDelPlan(plan: PlanProduccion) {
        val que = listOfNotNull(plan.producto, plan.marca, plan.presentacion, plan.fragancia)
            .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Plan #${plan.id}" }

        val ordenados = plan.insumos.sortedWith(
            compareBy({ it.completo }, { it.prioridad == "Normal" }, { it.insumo })
        )
        if (ordenados.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(que)
                .setMessage("Este plan no tiene insumos configurados.\n\nSe cargan desde " +
                            "Admin → Planificación, o se corrige la receta en Jerarquía.")
                .setPositiveButton("Cerrar", null)
                .show()
            return
        }

        val etiquetas = ordenados.map { i ->
            val marca = when {
                i.completo -> "✅"
                i.prioridad == "Urgente" -> "🔴"
                i.prioridad == "Alta" -> "🟠"
                else -> "⬜"
            }
            val unidad = i.unidad.orEmpty()
            if (i.completo) "$marca ${i.insumo} — completo"
            else "$marca ${i.insumo} — faltan ${i.falta} $unidad".trimEnd()
        }

        AlertDialog.Builder(this)
            .setTitle("$que  ·  ${plan.cobertura?.toInt() ?: 0}%")
            .setItems(etiquetas.toTypedArray()) { _, i ->
                if (puedeActuar) elegirPrioridad(ordenados[i]) else avisarSinPermiso()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun elegirPrioridad(linea: PlanInsumoResumen) {
        val opciones = arrayOf("Normal", "Alta", "Urgente")
        AlertDialog.Builder(this)
            .setTitle(linea.insumo)
            .setMessage(
                "Faltan ${linea.falta} ${linea.unidad.orEmpty()}\n" +
                "Prioridad actual: ${linea.prioridad}\n\n" +
                "Marcarlo urgente lo destaca para bodega y en el panel."
            )
            .setItems(opciones) { _, i -> enviarPrioridad(linea, opciones[i]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarPrioridad(linea: PlanInsumoResumen, prioridad: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.apiService.marcarPrioridadInsumo(
                    linea.id, PrioridadRequest(prioridad)
                )
                withContext(Dispatchers.Main) {
                    if (resp.isSuccessful) {
                        Toast.makeText(this@SupervisorDashboardActivity,
                            "${linea.insumo}: $prioridad", Toast.LENGTH_SHORT).show()
                        cargarDatos()
                    } else if (resp.code() == 401) {
                        avisarSinPermiso()
                    } else {
                        Toast.makeText(this@SupervisorDashboardActivity,
                            "No se pudo guardar (HTTP ${resp.code()})", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.warn("SupervisorPrioridad", "Fallo: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SupervisorDashboardActivity, "Sin conexión", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Verificación de entregas proactivas
    // ────────────────────────────────────────────────────────────────────────

    private fun revisarEntrega(entrega: EntregaPorVerificar) {
        val cuerpo = buildString {
            append("${entrega.cantidad} x ${entrega.insumo ?: "—"}\n")
            append("Máquina: ${entrega.maquina ?: "—"}\n")
            append("Insumista: ${entrega.insumista ?: "—"}\n")
            append("Cuándo: ${entrega.fecha_hora ?: "—"}\n")
            entrega.plan_id?.let { append("Plan de producción: #$it\n") }
            if (!entrega.observaciones.isNullOrBlank()) append("\n«${entrega.observaciones}»\n")
            append(
                if (entrega.foto_url.isNullOrBlank()) "\n⚠️ Sin foto de evidencia."
                else "\nFoto: ${RetrofitClient.BASE_URL.trimEnd('/')}${entrega.foto_url}"
            )
        }
        AlertDialog.Builder(this)
            .setTitle("Revisar entrega")
            .setMessage(cuerpo)
            // Rechazar no es solo una etiqueta: una entrega rechazada deja de contar
            // para la cobertura del plan, así que el plan vuelve a aparecer incompleto.
            .setNeutralButton("Rechazar") { _, _ -> enviarVerificacion(entrega, "Rechazada") }
            .setPositiveButton("Verificar") { _, _ -> enviarVerificacion(entrega, "Verificada") }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarVerificacion(entrega: EntregaPorVerificar, estado: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.apiService.verificarEntrega(
                    entrega.id, VerificarEntregaRequest(estado)
                )
                withContext(Dispatchers.Main) {
                    if (resp.isSuccessful) {
                        Toast.makeText(this@SupervisorDashboardActivity,
                            "Entrega $estado", Toast.LENGTH_SHORT).show()
                        cargarDatos()
                    } else if (resp.code() == 401) {
                        avisarSinPermiso()
                    } else {
                        Toast.makeText(this@SupervisorDashboardActivity,
                            "No se pudo guardar (HTTP ${resp.code()})", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.warn("SupervisorVerificar", "Fallo: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SupervisorDashboardActivity, "Sin conexión", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Chat de bodega
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Conversación con un insumista. `insumistaId` null = difusión a todos los de turno.
     *
     * Se refresca al abrir y al enviar, sin sondeo: una conversación de trabajo son dos
     * o tres mensajes por turno, y dejar un hilo golpeando la API cada pocos segundos
     * por si acaso no compensa.
     */
    /**
     * Globo rojo del ícono de chats: lo que los insumistas te escribieron y no has leído.
     * El supervisor no recibe push de las respuestas, así que este globo es el aviso.
     */
    private fun refrescarBadgeChats() {
        if (!puedeActuar) { tvBadgeChats.visibility = View.GONE; return }
        lifecycleScope.launch {
            val sinLeer = withContext(Dispatchers.IO) {
                try {
                    RetrofitClient.apiService.resumenChatBodega().body()?.sin_leer
                } catch (e: Exception) { null }
            } ?: return@launch
            tvBadgeChats.visibility = if (sinLeer > 0) View.VISIBLE else View.GONE
            tvBadgeChats.text = if (sinLeer > 99) "99+" else sinLeer.toString()
        }
    }

    // ────────────────────────────────────────────────────────────────────────

    private fun cerrarSesion() {
        RetrofitClient.tokenAdmin = null
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
        // Con la barra se cambia de vista de un toque, así que la respuesta de la vista
        // anterior puede llegar ya en la nueva. Los adaptadores viejos están a null y no
        // pintan nada, pero el subtítulo sí: se descarta lo que no sea de la vista vigente.
        val vista = vistaActual
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                when (vista) {
                    "PLANTA" -> {
                        val r = RetrofitClient.apiService.obtenerEstadoPlanta()
                        withContext(Dispatchers.Main) {
                            if (vistaActual != vista) return@withContext
                            r.body()?.takeIf { r.isSuccessful }?.let { adapterPlanta?.actualizarLista(it) }
                        }
                    }
                    "INSUMISTAS" -> {
                        val r = RetrofitClient.apiService.obtenerInsumistasDeTurno()
                        withContext(Dispatchers.Main) {
                            if (vistaActual != vista) return@withContext
                            val lista = r.body().orEmpty()
                            adapterInsumistas?.actualizarLista(lista)
                            // Que no haya NADIE de turno en bodega es justo el dato que
                            // el supervisor necesita ver, y una lista vacía no lo dice.
                            tvSubtitulo.text = if (lista.isEmpty())
                                "⚠️ No hay ningún insumista con sesión abierta"
                            else
                                "Supervisor: $nombreUsuario | ${lista.size} de turno · toca para escribir"
                        }
                    }
                    "PLAN" -> {
                        val hoy = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        val r = RetrofitClient.apiService.obtenerPlanes(hoy, hoy)
                        withContext(Dispatchers.Main) {
                            if (vistaActual != vista) return@withContext
                            val lista = r.body()?.planes.orEmpty()
                            adapterPlan?.actualizarLista(lista)
                            val sinCubrir = lista.count { (it.cobertura ?: 0.0) < 100.0 }
                            tvSubtitulo.text = when {
                                lista.isEmpty() -> "No hay producción planificada para hoy"
                                sinCubrir == 0 -> "✅ Todo el plan de hoy está cubierto"
                                else -> "$sinCubrir de ${lista.size} plan(es) sin cubrir · toca para ver qué falta"
                            }
                        }
                    }
                    "ENTREGAS" -> {
                        val r = RetrofitClient.apiService.obtenerEntregasPorVerificar()
                        withContext(Dispatchers.Main) {
                            if (vistaActual != vista) return@withContext
                            val lista = r.body().orEmpty()
                            adapterEntregas?.actualizarLista(lista)
                            tvSubtitulo.text = if (lista.isEmpty())
                                "Nada pendiente de revisar"
                            else
                                "${lista.size} entrega(s) sin verificar · toca una para revisarla"
                        }
                    }
                    else -> {
                        val r = RetrofitClient.apiService.obtenerHistorialPedidosSupervisor()
                        withContext(Dispatchers.Main) {
                            if (vistaActual != vista) return@withContext
                            r.body()?.takeIf { r.isSuccessful }?.let { adapterBodega?.actualizarLista(it) }
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
        // Un solo job de sondeo vivo: sin cancelar el anterior se acumularían al entrar
        // y salir de la pantalla.
        jobRadar?.cancel()
        // 5 s: equilibrio entre frescura del dato y consumo de red en la tablet.
        jobRadar = lifecycleScope.launch {
            var vuelta = 0
            refrescarBadgeChats()
            while (true) {
                delay(5000)
                cargarDatos(esSilencioso = true)
                // El globo de chats cada 15 s: el resumen es una consulta más pesada que
                // la de la vista y no hace falta tanta frescura para un contador.
                if (++vuelta % 3 == 0) refrescarBadgeChats()
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
