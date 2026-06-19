package com.example.controller.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
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
import com.example.controller.data.local.AppDatabase
import com.example.controller.util.MantenimientoItems
import com.example.controller.util.TurnoUtil
import com.google.gson.Gson
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
    private val checkboxes = mutableListOf<CheckBox>()

    private val colaRepo by lazy { ColaSyncRepository(applicationContext) }
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mantenimiento_checklist)

        momento  = intent.getStringExtra(EXTRA_MOMENTO) ?: MantenimientoMomento.ENTRADA
        maquina  = intent.getStringExtra(EXTRA_MAQUINA) ?: ""
        operador = intent.getStringExtra(EXTRA_OPERADOR) ?: ""

        contenedor = findViewById(R.id.contenedorItemsMtto)
        btnGuardar = findViewById(R.id.btnGuardarMtto)
        progress   = findViewById(R.id.progressMtto)

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
            }
            checkboxes.add(cb)
            contenedor.addView(cb)
        }
    }

    private fun guardar() {
        progress.visibility = View.VISIBLE
        btnGuardar.isEnabled = false

        val items = checkboxes.map { MantenimientoItemEstado(it.text.toString(), it.isChecked) }
        val ahora = Date()
        val fecha = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(ahora)
        val hora  = SimpleDateFormat("HH:mm", Locale.getDefault()).format(ahora)

        val req = MantenimientoChecklistRequest(
            requestId = "",   // ColaSyncRepository asigna el UUID real al encolar
            maquina   = maquina,
            operador  = operador,
            momento   = momento,
            fecha     = fecha,
            hora      = hora,
            items     = items
        )

        lifecycleScope.launch {
            try {
                colaRepo.enqueueMantenimiento(req)

                // Guard local para no volver a pedir este momento en el mismo turno.
                val turno = TurnoUtil.actual()
                AppDatabase.getInstance(applicationContext).mantenimientoDao().marcarCompletado(
                    maquina, operador, turno.fechaTurno, turno.codigo, momento, gson.toJson(items)
                )

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
