package com.example.controller

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Una fila de «Mis entregas»: lo registrado sin pedido y los pedidos atendidos, juntos y
 * ordenados por hora. Son dos tablas en el servidor pero para el insumista es lo mismo:
 * material que dejó en algún sitio durante su turno.
 */
data class EntregaTurnoFila(
    val esPedido: Boolean,
    val titulo: String,
    val detalle: String,
    val fechaHora: String?,       // "yyyy-MM-dd HH:mm:ss", para ordenar y sacar la hora
    val estado: String,
    val colorEstado: String
) {
    companion object {
        fun deProactiva(e: EntregaDelTurno) = EntregaTurnoFila(
            esPedido = false,
            titulo = "${e.cantidad} x ${e.insumo ?: "insumo"}",
            detalle = listOfNotNull(
                e.maquina ?: "Sin máquina",
                "sin pedido",
                if (e.foto_path.isNullOrBlank()) "⚠️ sin foto" else null,
                e.observaciones?.takeIf { it.isNotBlank() }?.let { "«$it»" }
            ).joinToString(" · "),
            fechaHora = e.fecha_hora,
            estado = when (e.estado) {
                "Verificada" -> "Verificada"
                "Rechazada" -> "Rechazada"
                else -> "Registrada"
            },
            colorEstado = when (e.estado) {
                "Verificada" -> "#16A34A"
                "Rechazada" -> "#EF4444"
                else -> "#64748B"
            }
        )

        fun dePedido(p: PedidoDelTurno): EntregaTurnoFila {
            val cantidad = p.cantidad_entregada ?: p.cantidad_solicitada
            return EntregaTurnoFila(
                esPedido = true,
                titulo = listOfNotNull(cantidad?.let { "$it x" }, p.detalle ?: "Pedido #${p.id}")
                    .joinToString(" "),
                detalle = listOfNotNull(p.maquina ?: "Sin máquina", p.operador).joinToString(" · "),
                fechaHora = p.fecha_entrega,
                estado = when (p.estado) {
                    "Entregado" -> "Confirmado"
                    "Entregado_Insumista" -> "Por confirmar"
                    "Cerrado_Fin_Turno" -> "Cerrado"
                    else -> p.estado ?: "—"
                },
                colorEstado = when (p.estado) {
                    "Entregado" -> "#16A34A"
                    "Entregado_Insumista" -> "#F59E0B"
                    else -> "#64748B"
                }
            )
        }
    }
}

class EntregasTurnoAdapter : RecyclerView.Adapter<EntregasTurnoAdapter.VH>() {

    private var filas: List<EntregaTurnoFila> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icono: TextView = v.findViewById(R.id.tvIconoEntrega)
        val titulo: TextView = v.findViewById(R.id.tvTituloEntrega)
        val detalle: TextView = v.findViewById(R.id.tvDetalleEntrega)
        val hora: TextView = v.findViewById(R.id.tvHoraEntrega)
        val estado: TextView = v.findViewById(R.id.tvEstadoEntrega)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_entrega_turno, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val f = filas[position]
        h.icono.text = if (f.esPedido) "🛒" else "📦"
        h.icono.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (f.esPedido) "#DBEAFE" else "#DCFCE7")
        )
        h.titulo.text = f.titulo
        h.detalle.text = f.detalle
        h.hora.text = f.fechaHora?.drop(11)?.take(5) ?: ""
        h.estado.text = f.estado
        h.estado.backgroundTintList = ColorStateList.valueOf(Color.parseColor(f.colorEstado))
    }

    override fun getItemCount() = filas.size

    fun actualizar(nuevas: List<EntregaTurnoFila>) {
        filas = nuevas
        notifyDataSetChanged()
    }
}
