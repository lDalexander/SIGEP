package com.example.controller

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PedidoAdapter(
    private var listaPedidos: List<PedidoPendienteResponse>,
    private val esAsignado: Boolean, // ✅ LA NUEVA VARIABLE MÁGICA
    private val onBotonClick: (PedidoPendienteResponse) -> Unit
) : RecyclerView.Adapter<PedidoAdapter.PedidoViewHolder>() {

    class PedidoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvHora: TextView = view.findViewById(R.id.tvHoraPedido)
        val tvMaquina: TextView = view.findViewById(R.id.tvMaquinaOperador)
        val tvDetalle: TextView = view.findViewById(R.id.tvDetalleInsumo)
        val btnAccion: Button = view.findViewById(R.id.btnAceptarPedido)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PedidoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pedido_card, parent, false)
        return PedidoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PedidoViewHolder, position: Int) {
        val pedido = listaPedidos[position]

        holder.tvHora.text = pedido.hora
        holder.tvMaquina.text = "${pedido.maquina} - ${pedido.operador}"
        holder.tvDetalle.text = "${pedido.cantidad} x ${pedido.detalle}"

        // 🔥 ESTA ES LA LÍNEA SALVAVIDAS (Resetea el botón de la tarjeta reciclada)
        holder.btnAccion.isEnabled = true

        // LÓGICA DE PANTALLA DIVIDIDA
        if (esAsignado) {
            holder.btnAccion.text = "✅ MARCAR ENTREGADO"
            holder.btnAccion.setBackgroundColor(Color.parseColor("#1976D2")) // Azul
        } else {
            holder.btnAccion.text = "✋ ACEPTAR PEDIDO"
            holder.btnAccion.setBackgroundColor(Color.parseColor("#4CAF50")) // Verde
        }

        holder.btnAccion.setOnClickListener {
            // Aquí lo apagamos temporalmente para evitar doble clic de red
            holder.btnAccion.isEnabled = false
            holder.btnAccion.text = if (esAsignado) "Entregando..." else "Asignando..."
            onBotonClick(pedido)
        }
    }

    override fun getItemCount(): Int = listaPedidos.size

    fun actualizarLista(nuevaLista: List<PedidoPendienteResponse>) {
        this.listaPedidos = nuevaLista
        // 🔥 EL GRITO: Sin esta línea, la lista jamás se moverá aunque tenga nuevos datos
        notifyDataSetChanged()
    }
}