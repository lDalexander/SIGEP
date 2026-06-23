package com.example.controller.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.controller.MantenimientoChecklistRequest
import com.example.controller.MantenimientoItemEstado
import com.example.controller.MantenimientoMomento
import com.example.controller.R
import com.example.controller.data.ColaSyncRepository
import com.example.controller.util.MantenimientoItems
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Checklist de mantenimiento y limpieza atado al turno. Se abre en dos momentos:
 *  - ENTRADA: antes de iniciar turno (gate de inicio).
 *  - SALIDA:  antes de cerrar turno (gate de cierre).
 *
 * Ítems fijos (MantenimientoItems). El llenado es offline-first: se encola con
 * ColaSyncRepository y lo envía el SyncWorker. Devuelve RESULT_OK cuando quedó
 * encolado (y registrado en el guard local), o RESULT_CANCELED si el operario sale.
 */
class MantenimientoChecklistActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MOMENTO  = "extra_momento"
        const val EXTRA_MAQUINA  = "extra_maquina"
        const val EXTRA_OPERADOR = "extra_operador"
    }

    private lateinit var momento: String
    private lateinit var maquina: String
    private lateinit var operador: String

    private lateinit var contenedor: LinearLayout
    private lateinit var btnGuardar: Button
    private lateinit var progress: ProgressBar
    private lateinit var etSupervisor: EditText
    private lateinit var etComentarios: EditText
    private val checkboxes = mutableListOf<CheckBox>()

    private val colaRepo by lazy { ColaSyncRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mantenimiento_checklist)

        momento  = intent.getStringExtra(EXTRA_MOMENTO) ?: MantenimientoMomento.ENTRADA
        maquina  = intent.getStringExtra(EXTRA_MAQUINA) ?: ""
        operador = intent.getStringExtra(EXTRA_OPERADOR) ?: ""

        contenedor   = findViewById(R.id.contenedorItemsMtto)
        btnGuardar   = findViewById(R.id.btnGuardarMtto)
        progress     = findViewById(R.id.progressMtto)
        etSupervisor = findViewById(R.id.etSupervisorTurno)
        etComentarios = findViewById(R.id.etComentarios)

        val titulo = if (momento == MantenimientoMomento.SALIDA)
            "Checklist de SALIDA (limpieza)" else "Checklist de ENTRADA (mantenimiento)"
        findViewById<TextView>(R.id.tvMttoTitulo).text = titulo
        findViewById<TextView>(R.id.tvMttoSubtitulo).text = "$maquina  ·  $operador"

        construirItems()

        btnGuardar.setOnClickListener { guardar() }
    }

    private fun construirItems() {
        MantenimientoItems.paraMomento(momento).forEach { etiqueta ->
            val cb = CheckBox(this).apply {
                text = etiqueta
                textSize = 16f
                setPadding(8, 24, 8, 24)
                // Al cambiar cualquier ítem, refrescamos el aviso de comentarios.
                setOnCheckedChangeListener { _, _ -> actualizarHintComentarios() }
            }
            checkboxes.add(cb)
            contenedor.addView(cb)
        }
        actualizarHintComentarios()
    }

    /**
     * Marcar los ítems es opcional, pero los comentarios son obligatorios cuando
     * queda al menos uno sin marcar. Reflejamos esa regla en el hint en vivo para
     * que el operario sepa qué se espera antes de pulsar Guardar.
     */
    private fun actualizarHintComentarios() {
        val sinMarcar = checkboxes.count { !it.isChecked }
        etComentarios.hint = if (sinMarcar > 0)
            "Obligatorio: explique por qué $sinMarcar ítem(s) sin marcar"
        else
            "Comentarios (opcional)"
        if (sinMarcar > 0) etComentarios.error = null
    }

    private fun guardar() {
        // El supervisor de turno es obligatorio.
        val supervisor = etSupervisor.text.toString().trim()
        if (supervisor.isEmpty()) {
            etSupervisor.error = "Indique el supervisor de turno"
            etSupervisor.requestFocus()
            return
        }

        // Marcar los ítems NO es obligatorio. Pero si queda alguno sin marcar,
        // el operario debe explicar en los comentarios por qué no lo marcó.
        val sinMarcar = checkboxes.count { !it.isChecked }
        val comentarios = etComentarios.text.toString().trim()
        if (sinMarcar > 0 && comentarios.isEmpty()) {
            etComentarios.error = "Explique por qué $sinMarcar ítem(s) quedaron sin marcar"
            etComentarios.requestFocus()
            Toast.makeText(
                this,
                "Hay $sinMarcar ítem(s) sin marcar: explique en comentarios por qué.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        progress.visibility = View.VISIBLE
        btnGuardar.isEnabled = false

        val items = checkboxes.map { MantenimientoItemEstado(it.text.toString(), it.isChecked) }
        val ahora = Date()
        val fecha = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(ahora)
        val hora  = SimpleDateFormat("HH:mm", Locale.getDefault()).format(ahora)

        val req = MantenimientoChecklistRequest(
            requestId   = "",   // ColaSyncRepository asigna el UUID real al encolar
            maquina     = maquina,
            operador    = operador,
            momento     = momento,
            fecha       = fecha,
            hora        = hora,
            supervisor  = supervisor,
            comentarios = comentarios,
            items       = items
        )

        lifecycleScope.launch {
            try {
                colaRepo.enqueueMantenimiento(req)
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                progress.visibility = View.GONE
                btnGuardar.isEnabled = true
                Toast.makeText(
                    this@MantenimientoChecklistActivity,
                    "No se pudo guardar el checklist: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
