package com.example.controller

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MaquinaStatusAdapter(private var lista: List<EstadoMaquinaRealTime>) :
    RecyclerView.Adapter<MaquinaStatusAdapter.ViewHolder>() {

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
    }

    override fun getItemCount() = lista.size

    fun actualizarLista(nuevaLista: List<EstadoMaquinaRealTime>) {
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
