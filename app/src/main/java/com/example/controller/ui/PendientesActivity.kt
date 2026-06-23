package com.example.controller.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.controller.R
import com.example.controller.data.ColaSyncRepository
import com.example.controller.data.local.OperacionPendiente
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class PendientesActivity : AppCompatActivity() {

    private lateinit var repo: ColaSyncRepository
    private lateinit var adapter: PendientesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pendientes)

        repo = ColaSyncRepository(applicationContext)

        val rv = findViewById<RecyclerView>(R.id.rvPendientes)
        val tvVacio = findViewById<TextView>(R.id.tvVacio)
        val btnSync = findViewById<Button>(R.id.btnSyncAhora)
        val tvTitulo = findViewById<TextView>(R.id.tvTituloPendientes)

        // Acceso oculto a Ajustes: long-press sobre el título.
        // Patrón "easter egg" para que operarios no entren sin querer pero los técnicos sí.
        tvTitulo.setOnLongClickListener {
            startActivity(Intent(this, AjustesTabletActivity::class.java))
            true
        }

        adapter = PendientesAdapter(
            onReintentar = { op ->
                lifecycleScope.launch {
                    repo.desmarcarFatal(op.id)
                    repo.dispararSync()
                    Toast.makeText(this@PendientesActivity, "Reintentando...", Toast.LENGTH_SHORT).show()
                }
            },
            onDescartar = { op ->
                lifecycleScope.launch {
                    repo.descartar(op.id)
                }
            }
        )

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnSync.setOnClickListener {
            repo.dispararSync()
            Toast.makeText(this, "Sincronización iniciada", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            repo.operacionesFlow.collectLatest { lista ->
                adapter.submitList(lista)
                tvVacio.visibility = if (lista.isEmpty()) View.VISIBLE else View.GONE
                rv.visibility      = if (lista.isEmpty()) View.GONE  else View.VISIBLE
            }
        }
    }
}

class PendientesAdapter(
    private val onReintentar: (OperacionPendiente) -> Unit,
    private val onDescartar: (OperacionPendiente) -> Unit
) : RecyclerView.Adapter<PendientesAdapter.VH>() {

    private var items: List<OperacionPendiente> = emptyList()

    fun submitList(list: List<OperacionPendiente>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_operacion_pendiente, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvTipo      = v.findViewById<TextView>(R.id.tvTipo)
        private val tvHora      = v.findViewById<TextView>(R.id.tvHora)
        private val tvIntentos  = v.findViewById<TextView>(R.id.tvIntentos)
        private val tvError     = v.findViewById<TextView>(R.id.tvError)
        private val btnR        = v.findViewById<Button>(R.id.btnReintentar)
        private val btnD        = v.findViewById<Button>(R.id.btnDescartar)
        private val fmt         = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun bind(op: OperacionPendiente) {
            tvTipo.text     = op.tipo.replace("_", " ")
            tvHora.text     = fmt.format(Date(op.creadoEn))
            tvIntentos.text = "Intentos: ${op.intentos}"

            if (op.ultimoError.isNullOrBlank()) {
                tvError.visibility = View.GONE
            } else {
                tvError.visibility = View.VISIBLE
                tvError.text = op.ultimoError
            }

            if (op.fatal) {
                btnR.visibility = View.VISIBLE
                btnD.visibility = View.VISIBLE
            } else {
                btnR.visibility = View.GONE
                btnD.visibility = View.GONE
            }

            btnR.setOnClickListener { onReintentar(op) }
            btnD.setOnClickListener { onDescartar(op) }
        }
    }
}
