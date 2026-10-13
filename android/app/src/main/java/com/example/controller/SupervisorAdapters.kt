package com.example.controller

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Máquinas con turno abierto. Pulsar una abre el detalle de su turno.
 *
 * El `onClick` llega como parámetro con valor por defecto para no tocar las dos
 * llamadas que ya existían: una tarjeta sin acción asociada sigue siendo válida.
 */
class MaquinaStatusAdapter(
    private var lista: List<EstadoMaquinaRealTime>,
    private val onClick: ((EstadoMaquinaRealTime) -> Unit)? = null
) : RecyclerView.Adapter<MaquinaStatusAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNombre: TextView = view.findViewById(R.id.tvNombreMaquina)
        val tvEstado: TextView = view.findViewById(R.id.tvEstadoMaquina)
        val tvOperador: TextView = view.findViewById(R.id.tvNombreOperador)
        val tvProducto: TextView = view.findViewById(R.id.tvDetalleProducto)
        val tvHora: TextView = view.findViewById(R.id.tvHoraInicio)
        val tvPacas: TextView = view.findViewById(R.id.tvTotalPacas)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_maquina_status, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = lista[position]
        holder.tvNombre.text = item.maquina
        holder.tvEstado.text = item.estado
        holder.tvOperador.text = item.operador
        holder.tvProducto.text = item.producto
        holder.tvHora.text = item.tiempo_inicio
        holder.tvPacas.text = item.pacas_totales.toString()

        // Los colores reflejan el estado operativo de la máquina según el estándar de planta.
        when (item.estado.uppercase()) {
            "ACTIVA" -> holder.tvEstado.setTextColor(Color.parseColor("#22C55E"))
            "PARO"   -> holder.tvEstado.setTextColor(Color.parseColor("#EF4444"))
            else     -> holder.tvEstado.setTextColor(Color.GRAY)
        }

        // Solo se deja pulsar si hay id de sesión: contra un servidor anterior llega
        // -1 y abrir el detalle daría un 404 sin que el supervisor entienda por qué.
        if (onClick != null && item.sesion_id > 0) {
            holder.itemView.isClickable = true
            holder.itemView.setOnClickListener { onClick.invoke(item) }
        } else {
            holder.itemView.isClickable = false
            holder.itemView.setOnClickListener(null)
        }
    }

    override fun getItemCount() = lista.size

    fun actualizarLista(nuevaLista: List<EstadoMaquinaRealTime>) {
        lista = nuevaLista
        notifyDataSetChanged()
    }
}

/**
 * Insumistas con sesión abierta. Pulsar uno abre su conversación.
 *
 * Reaprovecha el layout de las máquinas en vez de crear otro: son la misma tarjeta
 * (un título, un estado y cuatro cifras) y mantener dos XML casi iguales solo garantiza
 * que uno se quede atrás cuando se retoque el otro.
 */
class InsumistaAdapter(
    private var lista: List<InsumistaTurno>,
    private val onClick: ((InsumistaTurno) -> Unit)? = null
) : RecyclerView.Adapter<InsumistaAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNombre: TextView = view.findViewById(R.id.tvNombreMaquina)
        val tvEstado: TextView = view.findViewById(R.id.tvEstadoMaquina)
        val tvOperador: TextView = view.findViewById(R.id.tvNombreOperador)
        val tvProducto: TextView = view.findViewById(R.id.tvDetalleProducto)
        val tvHora: TextView = view.findViewById(R.id.tvHoraInicio)
        val tvPacas: TextView = view.findViewById(R.id.tvTotalPacas)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_maquina_status, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = lista[position]
        holder.tvNombre.text = item.nombre
        holder.tvOperador.text = item.rol
        holder.tvProducto.text = item.tablet ?: "—"
        holder.tvHora.text = item.desde ?: "—"
        holder.tvPacas.text = item.entregas_hoy.toString()

        // Los 10 minutos no son un umbral técnico: es el tiempo que un insumista tarda
        // en cruzar la planta y volver. Por encima, lo más probable es que la tablet
        // esté encendida encima de una caja y no en su mano.
        val inactivo = item.inactivo_min >= 10
        holder.tvEstado.text = when {
            inactivo -> "SIN SEÑAL ${item.inactivo_min}m"
            item.pedidos_activos > 0 -> "OCUPADO (${item.pedidos_activos})"
            else -> "LIBRE"
        }
        holder.tvEstado.setTextColor(
            when {
                inactivo -> Color.parseColor("#EF4444")
                item.pedidos_activos > 0 -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#22C55E")
            }
        )

        holder.itemView.setOnClickListener { onClick?.invoke(item) }
    }

    override fun getItemCount() = lista.size

    fun actualizarLista(nuevaLista: List<InsumistaTurno>) {
        lista = nuevaLista
        notifyDataSetChanged()
    }
}

/**
 * Entregas proactivas que el supervisor todavía no ha revisado.
 *
 * Reusa `item_maquina_status` como las otras dos: la tarjeta es la misma forma —título,
 * estado y cuatro cifras— y tener tres XML casi iguales solo asegura que dos se queden
 * atrás cuando se retoque el tercero.
 */
class EntregaVerificarAdapter(
    private var lista: List<EntregaPorVerificar>,
    private val onClick: ((EntregaPorVerificar) -> Unit)? = null
) : RecyclerView.Adapter<EntregaVerificarAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNombre: TextView = view.findViewById(R.id.tvNombreMaquina)
        val tvEstado: TextView = view.findViewById(R.id.tvEstadoMaquina)
        val tvOperador: TextView = view.findViewById(R.id.tvNombreOperador)
        val tvProducto: TextView = view.findViewById(R.id.tvDetalleProducto)
        val tvHora: TextView = view.findViewById(R.id.tvHoraInicio)
        val tvPacas: TextView = view.findViewById(R.id.tvTotalPacas)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_maquina_status, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = lista[position]
        holder.tvNombre.text = item.insumo ?: "Insumo"
        holder.tvOperador.text = item.insumista ?: "—"
        holder.tvProducto.text = buildString {
            append(item.maquina ?: "sin máquina")
            item.plan_id?.let { append("  ·  plan #$it") }
        }
        holder.tvHora.text = item.fecha_hora?.takeLast(5) ?: "—"
        holder.tvPacas.text = item.cantidad.toString()

        // La foto es la única prueba de dónde quedó el material, así que una entrega
        // sin ella se marca en rojo: es lo primero que el supervisor tiene que mirar.
        val sinFoto = item.foto_url.isNullOrBlank()
        holder.tvEstado.text = if (sinFoto) "SIN FOTO" else "POR REVISAR"
        holder.tvEstado.setTextColor(
            if (sinFoto) Color.parseColor("#EF4444") else Color.parseColor("#F59E0B")
        )

        holder.itemView.setOnClickListener { onClick?.invoke(item) }
    }

    override fun getItemCount() = lista.size

    fun actualizarLista(nuevaLista: List<EntregaPorVerificar>) {
        lista = nuevaLista
        notifyDataSetChanged()
    }
}

/**
 * Planes de producción del día con su cobertura de insumos.
 *
 * El número grande de la tarjeta es el PORCENTAJE CUBIERTO, no la cantidad planificada:
 * lo que el supervisor necesita decidir de un vistazo es si puede arrancar esa
 * producción, y para eso lo planificado no le dice nada por sí solo.
 */
class PlanAdapter(
    private var lista: List<PlanProduccion>,
    private val onClick: ((PlanProduccion) -> Unit)? = null
) : RecyclerView.Adapter<PlanAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNombre: TextView = view.findViewById(R.id.tvNombreMaquina)
        val tvEstado: TextView = view.findViewById(R.id.tvEstadoMaquina)
        val tvOperador: TextView = view.findViewById(R.id.tvNombreOperador)
        val tvProducto: TextView = view.findViewById(R.id.tvDetalleProducto)
        val tvHora: TextView = view.findViewById(R.id.tvHoraInicio)
        val tvPacas: TextView = view.findViewById(R.id.tvTotalPacas)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_maquina_status, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = lista[position]

        holder.tvNombre.text = listOfNotNull(item.producto, item.marca, item.presentacion)
            .filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "Plan #${item.id}" }
        // «sin máquina» no es un dato que falte: es producción que se deja preparada
        // para que luego la tome cualquiera. Se escribe para que no parezca un olvido.
        holder.tvOperador.text = item.maquina ?: "sin máquina asignada"
        holder.tvProducto.text = buildString {
            append(item.estado ?: "Planificado")
            item.cantidad_planificada?.let { append("  ·  $it ${item.unidad ?: ""}".trimEnd()) }
        }
        holder.tvHora.text = item.turno ?: item.fecha?.takeLast(5) ?: "—"
        holder.tvPacas.text = item.cobertura?.let { "${it.toInt()}%" } ?: "—"

        val cobertura = item.cobertura
        holder.tvEstado.text = when {
            cobertura == null -> "SIN INSUMOS"
            item.hay_urgentes -> "URGENTE"
            cobertura >= 100.0 -> "COMPLETO"
            else -> "FALTAN ${item.insumos_incompletos}"
        }
        holder.tvEstado.setTextColor(
            when {
                cobertura == null -> Color.GRAY
                item.hay_urgentes -> Color.parseColor("#EF4444")
                cobertura >= 100.0 -> Color.parseColor("#22C55E")
                else -> Color.parseColor("#F59E0B")
            }
        )

        holder.itemView.setOnClickListener { onClick?.invoke(item) }
    }

    override fun getItemCount() = lista.size

    fun actualizarLista(nuevaLista: List<PlanProduccion>) {
        lista = nuevaLista
        notifyDataSetChanged()
    }
}

class PedidoSupervisorAdapter(private var lista: List<HistorialPedidoSupervisor>) :
    RecyclerView.Adapter<PedidoSupervisorAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvId: TextView = view.findViewById(R.id.tvIdPedido)
        val tvStatus: TextView = view.findViewById(R.id.tvStatusPedido)
        val tvDetalle: TextView = view.findViewById(R.id.tvDetallePedido)
        val tvOrigen: TextView = view.findViewById(R.id.tvOrigenDestino)
        val tvAtendido: TextView = view.findViewById(R.id.tvAtendidoPor)
        val tvTiempo: TextView = view.findViewById(R.id.tvTiempoRespuesta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pedido_supervisor, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = lista[position]
        holder.tvId.text = "#${item.id}"
        holder.tvStatus.text = item.status
        holder.tvDetalle.text = "${item.insumo} x ${item.cantidad}"
        holder.tvOrigen.text = "De: ${item.maquina} | Para: ${item.solicitado_a}"
        holder.tvAtendido.text = item.aceptado_por ?: "---"
        holder.tvTiempo.text = item.tiempo_respuesta ?: "---"

        // Los colores permiten al supervisor identificar el estado de cada pedido de un vistazo.
        when (item.status.uppercase()) {
            "NUEVO" -> {
                holder.tvStatus.setBackgroundColor(Color.parseColor("#DBEAFE"))
                holder.tvStatus.setTextColor(Color.parseColor("#1E40AF"))
            }
            "ACEPTADO" -> {
                holder.tvStatus.setBackgroundColor(Color.parseColor("#FEF3C7"))
                holder.tvStatus.setTextColor(Color.parseColor("#92400E"))
            }
            "ENTREGADO" -> {
                holder.tvStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
                holder.tvStatus.setTextColor(Color.parseColor("#166534"))
            }
        }
    }

    override fun getItemCount() = lista.size

    fun actualizarLista(nuevaLista: List<HistorialPedidoSupervisor>) {
        lista = nuevaLista
        notifyDataSetChanged()
    }
}
