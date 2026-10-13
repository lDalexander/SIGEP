package com.example.controller

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Un hilo del chat de bodega. Quién habla con quién lo decide el servidor por el token;
 * aquí solo se dice QUÉ hilo se quiere ver.
 *
 * - Supervisor: `insumistaId` = con quién; `difusion` = avisos a toda la bodega.
 * - Insumista: `supervisorId` = con qué supervisor (0 = «Supervisión», cualquiera);
 *   `difusion` = los avisos generales, que solo se leen.
 * - Sin nada de lo anterior: la vista antigua con todo junto (servidor previo al
 *   2026-10-13, que todavía no sabe separar conversaciones).
 */
data class HiloChat(
    val titulo: String,
    val subtitulo: String,
    val avatar: String,
    val insumistaId: Int? = null,
    val supervisorId: Int? = null,
    val difusion: Boolean = false,
    val puedeEscribir: Boolean = true
) {
    val esVistaAntigua: Boolean
        get() = insumistaId == null && supervisorId == null && !difusion
}

/**
 * Chat supervisor ↔ insumista al estilo WhatsApp: una ventana con la lista de chats y
 * otra, encima, con la conversación en burbujas.
 *
 * Antes eran dos AlertDialog con todo el historial pegado en un TextView, y el insumista
 * veía en un solo hilo lo de todos los supervisores. Ahora cada pareja tiene su chat.
 *
 * Lo comparten la torre de control y la bandeja del insumista; `esSupervisor` cambia
 * qué lista se pide y cuáles burbujas son «mías».
 *
 * Mientras una ventana está abierta se sondea cada pocos segundos: el chat no tiene
 * evento propio por WebSocket y un supervisor no recibe push de las respuestas.
 */
object ChatBodega {

    private const val SONDEO_MS = 4_000L

    // ────────────────────────────────────────────────────────────────────────
    // Lista de chats
    // ────────────────────────────────────────────────────────────────────────

    /** @param alCerrar para refrescar el contador de no leídos de quien la abrió. */
    fun abrirLista(
        activity: AppCompatActivity,
        esSupervisor: Boolean,
        alCerrar: () -> Unit = {}
    ) {
        val (dialogo, raiz) = crearDialogo(activity, R.layout.dialog_chat_lista)
        val rv = raiz.findViewById<RecyclerView>(R.id.rvListaChats)
        val tvVacio = raiz.findViewById<TextView>(R.id.tvVacioListaChats)
        raiz.findViewById<TextView>(R.id.tvSubtituloListaChats).text =
            if (esSupervisor) "Elige a quién escribir · arriba, quien te respondió"
            else "Tus conversaciones con supervisión"
        raiz.findViewById<ImageButton>(R.id.btnCerrarListaChats).setOnClickListener { dialogo.dismiss() }

        var sondeo: Job? = null

        lateinit var cargar: () -> Unit
        val adapter = ListaChatsAdapter { fila ->
            abrirConversacion(activity, fila.hilo, esSupervisor) { cargar() }
        }
        rv.layoutManager = LinearLayoutManager(activity)
        rv.adapter = adapter

        cargar = {
            activity.lifecycleScope.launch {
                val (filas, error) = withContext(Dispatchers.IO) { leerLista(esSupervisor) }
                if (!dialogo.isShowing) return@launch
                adapter.actualizar(filas)
                tvVacio.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
                tvVacio.text = error ?: "Todavía no hay conversaciones"
            }
        }

        dialogo.setOnShowListener {
            cargar()
            sondeo = activity.lifecycleScope.launch {
                while (isActive) {
                    delay(SONDEO_MS * 2)
                    cargar()
                }
            }
        }
        dialogo.setOnDismissListener {
            sondeo?.cancel()
            alCerrar()
        }
        dialogo.show()
        dimensionar(dialogo, activity)
    }

    /** -> (filas, mensaje de error para mostrar si no hay filas). */
    private suspend fun leerLista(esSupervisor: Boolean): Pair<List<FilaChat>, String?> {
        val resp = try {
            RetrofitClient.apiService.resumenChatBodega()
        } catch (e: Exception) {
            return emptyList<FilaChat>() to "Sin conexión con el servidor"
        }
        if (resp.code() == 401) return emptyList<FilaChat>() to "Vuelve a iniciar sesión para usar el chat"
        val r = resp.body()
        if (!resp.isSuccessful || r == null) return emptyList<FilaChat>() to "No se pudo cargar (HTTP ${resp.code()})"

        // Un dato inesperado del servidor deja la lista vacía con un aviso, nunca cierra
        // la app: esta ventana se abre a media jornada con la tablet en la mano.
        return try {
            (if (esSupervisor) filasSupervisor(r) else filasInsumista(r)) to null
        } catch (e: Exception) {
            ErrorReporter.report("ChatBodega", "Resumen de chats ilegible", e)
            emptyList<FilaChat>() to "No se pudo leer la lista de chats"
        }
    }

    private fun filasSupervisor(r: ResumenChat): List<FilaChat> {
        val aviso = FilaChat(
            hilo = HiloChat(
                titulo = "Aviso a toda la bodega",
                subtitulo = "Lo reciben todos los insumistas de turno",
                avatar = "📢",
                difusion = true
            ),
            ultimo = r.difusion?.ultimo,
            previa = r.difusion?.ultimo_texto ?: "Escribe un aviso para todos",
            noLeidos = 0,
            deTurno = false,
            fija = true
        )
        val personas = r.conversaciones.orEmpty().map { c ->
            val tipo = c.rol ?: "Insumista"
            FilaChat(
                hilo = HiloChat(
                    titulo = c.nombre,
                    subtitulo = if (c.de_turno) "$tipo · de turno" else tipo,
                    avatar = iniciales(c.nombre),
                    insumistaId = c.insumista_id
                ),
                ultimo = c.ultimo,
                previa = when {
                    c.ultimo_texto != null && c.ultimo_emisor == "SUPERVISOR" -> "Tú: ${c.ultimo_texto}"
                    c.ultimo_texto != null -> c.ultimo_texto
                    c.de_turno -> "De turno · toca para escribir"
                    else -> "Sin mensajes"
                },
                noLeidos = c.sin_leer,
                deTurno = c.de_turno
            )
        }
        return listOf(aviso) + ordenar(personas)
    }

    private fun filasInsumista(r: ResumenChat): List<FilaChat> {
        // Servidor anterior a las conversaciones: un único hilo con todo, como antes.
        val chats = r.chats.orEmpty()
        if (chats.isEmpty()) {
            return listOf(
                FilaChat(
                    hilo = HiloChat("Supervisión", "Mensajes de supervisión", "👥"),
                    ultimo = null, previa = "Toca para ver los mensajes",
                    noLeidos = r.sin_leer, deTurno = false
                )
            )
        }
        val filas = chats.map { c ->
            val hilo = when (c.tipo) {
                "DIFUSION" -> HiloChat(c.nombre, "Avisos para todos · solo lectura", "📢",
                    difusion = true, puedeEscribir = false)
                "GENERAL" -> HiloChat(c.nombre, "Lo lee cualquier supervisor", "👥", supervisorId = 0)
                else -> HiloChat(c.nombre, "Supervisor", iniciales(c.nombre), supervisorId = c.supervisor_id)
            }
            FilaChat(
                hilo = hilo,
                ultimo = c.ultimo,
                previa = when {
                    c.ultimo_texto != null && c.ultimo_emisor == "INSUMISTA" -> "Tú: ${c.ultimo_texto}"
                    c.ultimo_texto != null -> c.ultimo_texto
                    c.tipo == "GENERAL" -> "Escribe a cualquier supervisor"
                    else -> "Sin mensajes"
                },
                noLeidos = c.sin_leer,
                deTurno = false
            )
        }
        return ordenar(filas)
    }

    /** Primero lo que tiene mensajes sin leer, luego lo más reciente: como WhatsApp. */
    private fun ordenar(filas: List<FilaChat>): List<FilaChat> =
        filas.sortedWith(
            compareByDescending<FilaChat> { it.noLeidos > 0 }
                .thenByDescending { it.ultimo ?: "" }
                .thenByDescending { it.deTurno }
                .thenBy { it.hilo.titulo.lowercase() }
        )

    data class FilaChat(
        val hilo: HiloChat,
        val ultimo: String?,
        val previa: String,
        val noLeidos: Int,
        val deTurno: Boolean,
        val fija: Boolean = false
    )

    private class ListaChatsAdapter(
        private val alTocar: (FilaChat) -> Unit
    ) : RecyclerView.Adapter<ListaChatsAdapter.VH>() {

        private var filas: List<FilaChat> = emptyList()

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar: TextView = v.findViewById(R.id.tvAvatarChat)
            val deTurno: View = v.findViewById(R.id.vDeTurnoChat)
            val nombre: TextView = v.findViewById(R.id.tvNombreChat)
            val hora: TextView = v.findViewById(R.id.tvHoraChat)
            val previa: TextView = v.findViewById(R.id.tvPreviaChat)
            val noLeidos: TextView = v.findViewById(R.id.tvNoLeidosChat)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_chat_lista, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val f = filas[position]
            pintarAvatar(h.avatar, f.hilo)
            h.deTurno.visibility = if (f.deTurno) View.VISIBLE else View.GONE
            h.nombre.text = f.hilo.titulo
            h.hora.text = horaCorta(f.ultimo)
            h.previa.text = f.previa
            val hay = f.noLeidos > 0
            h.noLeidos.visibility = if (hay) View.VISIBLE else View.GONE
            h.noLeidos.text = if (f.noLeidos > 99) "99+" else f.noLeidos.toString()
            // Como WhatsApp: con mensajes nuevos, la hora y la vista previa resaltan.
            h.hora.setTextColor(Color.parseColor(if (hay) "#16A34A" else "#64748B"))
            h.previa.setTextColor(Color.parseColor(if (hay) "#0F172A" else "#64748B"))
            h.itemView.setOnClickListener { alTocar(f) }
        }

        override fun getItemCount() = filas.size

        fun actualizar(nuevas: List<FilaChat>) {
            if (nuevas == filas) return
            filas = nuevas
            notifyDataSetChanged()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Conversación
    // ────────────────────────────────────────────────────────────────────────

    fun abrirConversacion(
        activity: AppCompatActivity,
        hilo: HiloChat,
        esSupervisor: Boolean,
        alCerrar: () -> Unit = {}
    ) {
        val (dialogo, raiz) = crearDialogo(activity, R.layout.dialog_chat_conversacion)
        val rv = raiz.findViewById<RecyclerView>(R.id.rvMensajesChat)
        val tvVacio = raiz.findViewById<TextView>(R.id.tvVacioChat)
        val etTexto = raiz.findViewById<EditText>(R.id.etMensajeChat)
        val btnEnviar = raiz.findViewById<ImageButton>(R.id.btnEnviarChat)

        pintarAvatar(raiz.findViewById(R.id.tvAvatarConversacion), hilo)
        raiz.findViewById<TextView>(R.id.tvNombreConversacion).text = hilo.titulo
        raiz.findViewById<TextView>(R.id.tvEstadoConversacion).text = hilo.subtitulo
        raiz.findViewById<ImageButton>(R.id.btnAtrasChat).setOnClickListener { dialogo.dismiss() }

        if (!hilo.puedeEscribir) {
            raiz.findViewById<View>(R.id.barraEscribirChat).visibility = View.GONE
            raiz.findViewById<View>(R.id.tvSoloLecturaChat).visibility = View.VISIBLE
        }
        if (hilo.difusion && esSupervisor) etTexto.hint = "Aviso para toda la bodega"

        val miId = activity.getSharedPreferences("DetcuadorPrefs", AppCompatActivity.MODE_PRIVATE)
            .getInt("usuario_id", -1)
        val adapter = BurbujasAdapter()
        val lm = LinearLayoutManager(activity).apply { stackFromEnd = true }
        rv.layoutManager = lm
        rv.adapter = adapter

        var sondeo: Job? = null
        var ultimoId = -1

        fun esPropio(m: MensajeBodega): Boolean =
            if (esSupervisor) m.emisor == "SUPERVISOR" && (m.supervisor_id == null || m.supervisor_id == miId)
            else m.emisor == "INSUMISTA"

        fun cargar(bajarAlFinal: Boolean = false) {
            activity.lifecycleScope.launch {
                val resultado = withContext(Dispatchers.IO) {
                    try {
                        val r = RetrofitClient.apiService.leerChatBodega(
                            hilo.insumistaId, hilo.supervisorId, if (hilo.difusion) true else null
                        )
                        when {
                            r.code() == 401 -> null to "Vuelve a iniciar sesión para usar el chat"
                            !r.isSuccessful -> null to "No se pudo cargar (HTTP ${r.code()})"
                            else -> r.body()?.mensajes.orEmpty() to null
                        }
                    } catch (e: Exception) {
                        null to "Sin conexión con el servidor"
                    }
                }
                if (!dialogo.isShowing) return@launch
                val (mensajes, error) = resultado
                if (mensajes == null) {
                    // Un fallo del sondeo no borra lo que ya se ve: solo avisa si no hay nada.
                    if (adapter.itemCount == 0) {
                        tvVacio.visibility = View.VISIBLE
                        tvVacio.text = error
                    }
                    return@launch
                }
                tvVacio.visibility = if (mensajes.isEmpty()) View.VISIBLE else View.GONE
                tvVacio.text = if (hilo.difusion && !esSupervisor) "Todavía no hay avisos"
                               else "Escribe el primer mensaje"

                val nuevoUltimo = mensajes.lastOrNull()?.id ?: -1
                val estabaAbajo = !rv.canScrollVertically(1)
                adapter.actualizar(construirItems(mensajes, hilo, ::esPropio))
                if (bajarAlFinal || (nuevoUltimo != ultimoId && estabaAbajo)) {
                    if (adapter.itemCount > 0) rv.scrollToPosition(adapter.itemCount - 1)
                }

                // Lo que me escribieron y aún figura sin leer se marca al verlo.
                if (mensajes.any { !it.leido && !esPropio(it) }) {
                    withContext(Dispatchers.IO) {
                        try {
                            RetrofitClient.apiService.marcarChatLeido(
                                hilo.insumistaId, hilo.supervisorId, if (hilo.difusion) true else null
                            )
                        } catch (_: Exception) { /* best-effort: no impide leer */ }
                    }
                }
                ultimoId = nuevoUltimo
            }
        }

        btnEnviar.setOnClickListener {
            val texto = etTexto.text.toString().trim()
            if (texto.isEmpty()) return@setOnClickListener
            etTexto.setText("")
            btnEnviar.isEnabled = false
            activity.lifecycleScope.launch {
                val error = withContext(Dispatchers.IO) {
                    try {
                        val req = if (esSupervisor) EnviarMensajeRequest(texto, insumista_id = hilo.insumistaId)
                                  else EnviarMensajeRequest(texto, supervisor_id = hilo.supervisorId?.takeIf { it > 0 })
                        val r = RetrofitClient.apiService.enviarMensajeBodega(req)
                        when {
                            r.isSuccessful -> null
                            r.code() == 401 -> "Vuelve a iniciar sesión para poder escribir"
                            else -> "No se pudo enviar (HTTP ${r.code()})"
                        }
                    } catch (e: Exception) {
                        "Sin conexión: el mensaje no se envió"
                    }
                }
                btnEnviar.isEnabled = true
                if (error != null) {
                    // Lo escrito vuelve al cuadro: perderlo obligaría a teclearlo otra
                    // vez con la tablet en una mano.
                    if (etTexto.text.isNullOrEmpty()) etTexto.setText(texto)
                    etTexto.setSelection(etTexto.text.length)
                    Toast.makeText(activity, error, Toast.LENGTH_SHORT).show()
                } else {
                    cargar(bajarAlFinal = true)
                }
            }
        }

        dialogo.setOnShowListener {
            cargar(bajarAlFinal = true)
            sondeo = activity.lifecycleScope.launch {
                while (isActive) {
                    delay(SONDEO_MS)
                    cargar()
                }
            }
        }
        dialogo.setOnDismissListener {
            sondeo?.cancel()
            alCerrar()
        }
        dialogo.show()
        dimensionar(dialogo, activity)
    }

    private sealed class ItemChat {
        data class Fecha(val texto: String) : ItemChat()
        data class Mensaje(val m: MensajeBodega, val propio: Boolean, val autor: String?) : ItemChat()
    }

    private fun construirItems(
        mensajes: List<MensajeBodega>,
        hilo: HiloChat,
        esPropio: (MensajeBodega) -> Boolean
    ): List<ItemChat> {
        val items = mutableListOf<ItemChat>()
        var diaAnterior: String? = null
        // Solo donde escribe más de uno del otro lado hace falta decir quién habla.
        val conAutor = hilo.difusion || hilo.esVistaAntigua
        for (m in mensajes) {
            val dia = m.creado_en?.take(10)
            if (dia != null && dia != diaAnterior) {
                items += ItemChat.Fecha(etiquetaDia(dia))
                diaAnterior = dia
            }
            val propio = esPropio(m)
            items += ItemChat.Mensaje(m, propio, if (!propio && conAutor) m.de else null)
        }
        return items
    }

    private class BurbujasAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<ItemChat> = emptyList()

        class FechaVH(v: View) : RecyclerView.ViewHolder(v) {
            val texto: TextView = v.findViewById(R.id.tvFechaChat)
        }

        class BurbujaVH(v: View) : RecyclerView.ViewHolder(v) {
            val autor: TextView? = v.findViewById(R.id.tvAutorBurbuja)
            val texto: TextView = v.findViewById(R.id.tvTextoBurbuja)
            val hora: TextView = v.findViewById(R.id.tvHoraBurbuja)
        }

        override fun getItemViewType(position: Int) = when (val i = items[position]) {
            is ItemChat.Fecha -> 0
            is ItemChat.Mensaje -> if (i.propio) 1 else 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> FechaVH(inf.inflate(R.layout.item_chat_fecha, parent, false))
                1 -> BurbujaVH(inf.inflate(R.layout.item_chat_burbuja_propia, parent, false))
                else -> BurbujaVH(inf.inflate(R.layout.item_chat_burbuja_ajena, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val i = items[position]) {
                is ItemChat.Fecha -> (holder as FechaVH).texto.text = i.texto
                is ItemChat.Mensaje -> {
                    val h = holder as BurbujaVH
                    h.texto.text = i.m.texto
                    h.autor?.apply {
                        visibility = if (i.autor != null) View.VISIBLE else View.GONE
                        text = i.autor
                    }
                    h.hora.text = if (i.propio) horaConPalomitas(i.m) else (i.m.hora ?: "")
                }
            }
        }

        override fun getItemCount() = items.size

        fun actualizar(nuevos: List<ItemChat>) {
            if (nuevos == items) return
            items = nuevos
            notifyDataSetChanged()
        }
    }

    /** «10:42 ✓✓»: una palomita gris si aún no lo leyó, dos azules si ya. */
    private fun horaConPalomitas(m: MensajeBodega): CharSequence {
        val sb = SpannableStringBuilder(m.hora ?: "")
        val inicio = sb.length
        sb.append(if (m.leido) "  ✓✓" else "  ✓")
        sb.setSpan(
            ForegroundColorSpan(Color.parseColor(if (m.leido) "#0EA5E9" else "#94A3B8")),
            inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return sb
    }

    // ────────────────────────────────────────────────────────────────────────
    // Utilidades
    // ────────────────────────────────────────────────────────────────────────

    /**
     * -> (diálogo, vista raíz). Se infla con el inflador de la activity, que es el de
     * AppCompat: solo ese entiende el `app:tint` de los íconos, y sin él saldrían negros
     * sobre la cabecera oscura.
     */
    private fun crearDialogo(activity: AppCompatActivity, layout: Int): Pair<AppCompatDialog, View> {
        val raiz = activity.layoutInflater.inflate(layout, null)
        // Esquinas redondeadas también para el contenido, no solo para el fondo.
        raiz.clipToOutline = true
        val dialogo = AppCompatDialog(activity).apply {
            supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(raiz)
            // El fondo redondeado lo pone el layout; el de la ventana se quita para que
            // no asome un rectángulo detrás de las esquinas.
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return dialogo to raiz
    }

    /** Casi toda la pantalla, pero sin pasar de un ancho de teléfono en las tablets. */
    private fun dimensionar(dialogo: AppCompatDialog, activity: AppCompatActivity) {
        val m = activity.resources.displayMetrics
        val ancho = min((m.widthPixels * 0.96).toInt(), (640 * m.density).toInt())
        val alto = (m.heightPixels * 0.9).toInt()
        dialogo.window?.setLayout(ancho, alto)
    }

    private val COLORES_AVATAR = listOf(
        "#0EA5E9", "#8B5CF6", "#F59E0B", "#10B981", "#EF4444", "#EC4899", "#14B8A6", "#6366F1"
    )

    private fun pintarAvatar(tv: TextView, hilo: HiloChat) {
        tv.text = hilo.avatar
        val color = when {
            hilo.difusion -> "#F97316"
            hilo.supervisorId == 0 || hilo.esVistaAntigua -> "#475569"
            else -> COLORES_AVATAR[abs(hilo.titulo.hashCode()) % COLORES_AVATAR.size]
        }
        tv.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
    }

    fun iniciales(nombre: String): String =
        nombre.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

    private fun hoyYAyer(): Pair<String, String> {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance()
        val hoy = f.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        return hoy to f.format(cal.time)
    }

    /** "2026-10-13" -> «Hoy», «Ayer» o «10/10/2026». */
    private fun etiquetaDia(dia: String): String {
        val (hoy, ayer) = hoyYAyer()
        return when (dia) {
            hoy -> "Hoy"
            ayer -> "Ayer"
            else -> try {
                SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dia) ?: Date()
                )
            } catch (e: Exception) { dia }
        }
    }

    /** "2026-10-13 10:42" -> «10:42» si es de hoy, «Ayer» o «10/10». */
    private fun horaCorta(ultimo: String?): String {
        if (ultimo.isNullOrBlank() || ultimo.length < 16) return ""
        val (hoy, ayer) = hoyYAyer()
        val dia = ultimo.take(10)
        return when (dia) {
            hoy -> ultimo.substring(11, 16)
            ayer -> "Ayer"
            else -> "${dia.substring(8, 10)}/${dia.substring(5, 7)}"
        }
    }
}
