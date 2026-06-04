package com.example.controller.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.controller.ChecklistCampo
import com.example.controller.ChecklistPlantilla
import com.example.controller.ChecklistResponderRequest
import com.example.controller.ChecklistRespuestaValor
import com.example.controller.ChecklistTipoCampo
import com.example.controller.ErrorReporter
import com.example.controller.R
import com.example.controller.RetrofitClient
import com.example.controller.data.ColaSyncRepository
import com.example.controller.data.local.AppDatabase
import com.example.controller.util.TurnoUtil
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Formulario dinámico de "Estado de equipo" que el operario debe llenar al
 * recibir la máquina, antes de iniciar turno. Renderiza los campos definidos
 * por el admin (constructor de formularios). Estrategia online-primero con
 * fallback a cola offline, igual que INICIAR_TURNO.
 *
 * Devuelve RESULT_OK cuando el checklist quedó registrado (o encolado), o
 * cuando no hay formulario configurado (no se debe bloquear la producción).
 */
class ChecklistEquipoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MAQUINA = "extra_maquina"
        const val EXTRA_OPERADOR = "extra_operador"
    }

    private lateinit var contenedor: LinearLayout
    private lateinit var btnGuardar: Button
    private lateinit var progress: ProgressBar
    private lateinit var colaRepo: ColaSyncRepository

    private val gson = Gson()
    private val binders = mutableListOf<CampoBinder>()
    private var plantilla: ChecklistPlantilla? = null

    private lateinit var maquina: String
    private lateinit var operador: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_checklist_equipo)

        maquina = intent.getStringExtra(EXTRA_MAQUINA) ?: ""
        operador = intent.getStringExtra(EXTRA_OPERADOR) ?: ""
        colaRepo = ColaSyncRepository(applicationContext)

        contenedor = findViewById(R.id.contenedorCampos)
        btnGuardar = findViewById(R.id.btnGuardarChecklist)
        progress = findViewById(R.id.progressChecklist)
        findViewById<TextView>(R.id.tvChecklistSubtitulo).text =
            "Máquina: $maquina  •  $operador"

        btnGuardar.setOnClickListener { guardar() }
        btnGuardar.isEnabled = false

        cargarPlantilla()
    }

    private fun cargarPlantilla() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val checklistDao = AppDatabase.getInstance(applicationContext).checklistDao()
            // 1) Intentar servidor; 2) caché local; 3) sin formulario → no bloquear.
            var p: ChecklistPlantilla? = try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.obtenerPlantillaChecklist()
                }
                if (resp.isSuccessful) resp.body()?.also {
                    checklistDao.guardarPlantilla(gson.toJson(it))
                } else null
            } catch (e: Exception) {
                ErrorReporter.warn("Checklist", "Sin red al cargar plantilla: ${e.message}")
                null
            }

            if (p == null) {
                p = withContext(Dispatchers.IO) {
                    checklistDao.leerPlantilla()?.let {
                        runCatching { gson.fromJson(it, ChecklistPlantilla::class.java) }.getOrNull()
                    }
                }
            }

            progress.visibility = View.GONE

            if (p == null || p.campos.isEmpty()) {
                // No hay formulario configurado: permitir continuar sin bloquear.
                Toast.makeText(
                    this@ChecklistEquipoActivity,
                    "No hay formulario de estado de equipo configurado.",
                    Toast.LENGTH_LONG
                ).show()
                setResult(RESULT_OK)
                finish()
                return@launch
            }

            plantilla = p
            render(p)
            btnGuardar.isEnabled = true
        }
    }

    private fun render(p: ChecklistPlantilla) {
        contenedor.removeAllViews()
        binders.clear()
        p.campos.sortedBy { it.orden }.forEach { campo ->
            val binder = construirCampo(campo)
            binders.add(binder)
        }
    }

    // ==========================================
    // CONSTRUCCIÓN DINÁMICA DE CAMPOS
    // ==========================================

    private interface CampoBinder {
        val campo: ChecklistCampo
        fun valor(): String
        fun esValido(): Boolean
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun bloque(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(20) }
    }

    private fun etiqueta(campo: ChecklistCampo): TextView = TextView(this).apply {
        text = campo.etiqueta + if (campo.requerido) "  *" else ""
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(0xFF1E293B.toInt())
        setPadding(0, 0, 0, dp(6))
    }

    private fun construirCampo(campo: ChecklistCampo): CampoBinder {
        val block = bloque()
        contenedor.addView(block)

        return when (campo.tipo) {
            ChecklistTipoCampo.CHECKBOX -> {
                val cb = CheckBox(this).apply {
                    text = campo.etiqueta + if (campo.requerido) "  *" else ""
                    textSize = 15f
                    setTextColor(0xFF1E293B.toInt())
                }
                block.addView(cb)
                object : CampoBinder {
                    override val campo = campo
                    override fun valor() = if (cb.isChecked) "true" else "false"
                    override fun esValido() = true // booleano siempre respondido
                }
            }

            ChecklistTipoCampo.SELECCION_UNICA -> {
                block.addView(etiqueta(campo))
                val grupo = RadioGroup(this)
                val opciones = campo.opciones ?: emptyList()
                opciones.forEachIndexed { i, op ->
                    grupo.addView(RadioButton(this).apply {
                        id = View.generateViewId()
                        text = op
                        textSize = 15f
                        setTextColor(0xFF333333.toInt())
                        tag = i
                    })
                }
                block.addView(grupo)
                object : CampoBinder {
                    override val campo = campo
                    override fun valor(): String {
                        val sel = grupo.checkedRadioButtonId
                        if (sel == -1) return ""
                        return grupo.findViewById<RadioButton>(sel).text.toString()
                    }
                    override fun esValido() = !campo.requerido || valor().isNotBlank()
                }
            }

            ChecklistTipoCampo.SELECCION_MULTIPLE -> {
                block.addView(etiqueta(campo))
                val opciones = campo.opciones ?: emptyList()
                val checks = opciones.map { op ->
                    CheckBox(this).apply {
                        text = op
                        textSize = 15f
                        setTextColor(0xFF333333.toInt())
                    }.also { block.addView(it) }
                }
                object : CampoBinder {
                    override val campo = campo
                    override fun valor(): String {
                        val seleccion = checks.filter { it.isChecked }.map { it.text.toString() }
                        return gson.toJson(seleccion)
                    }
                    override fun esValido(): Boolean =
                        !campo.requerido || checks.any { it.isChecked }
                }
            }

            else -> {
                // TEXTO_CORTO, TEXTO_LARGO, NUMERO
                block.addView(etiqueta(campo))
                val et = EditText(this).apply {
                    setTextColor(0xFF000000.toInt())
                    setHintTextColor(0xFF757575.toInt())
                    when (campo.tipo) {
                        ChecklistTipoCampo.TEXTO_LARGO -> {
                            inputType = InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            minLines = 3
                            gravity = android.view.Gravity.TOP or android.view.Gravity.START
                            hint = "Escriba aquí…"
                        }
                        ChecklistTipoCampo.NUMERO -> {
                            inputType = InputType.TYPE_CLASS_NUMBER or
                                InputType.TYPE_NUMBER_FLAG_DECIMAL
                            hint = "0"
                        }
                        else -> {
                            inputType = InputType.TYPE_CLASS_TEXT
                            hint = "Escriba aquí…"
                        }
                    }
                }
                block.addView(et)
                object : CampoBinder {
                    override val campo = campo
                    override fun valor() = et.text.toString().trim()
                    override fun esValido() = !campo.requerido || valor().isNotBlank()
                }
            }
        }
    }

    // ==========================================
    // GUARDAR
    // ==========================================

    private fun guardar() {
        val invalido = binders.firstOrNull { !it.esValido() }
        if (invalido != null) {
            Toast.makeText(
                this,
                "Complete el campo obligatorio: ${invalido.campo.etiqueta}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val p = plantilla ?: return
        val respuestas = binders.map {
            ChecklistRespuestaValor(campoId = it.campo.id, valor = it.valor())
        }
        val req = ChecklistResponderRequest(
            requestId = UUID.randomUUID().toString(),
            plantillaId = p.id,
            plantillaVersion = p.version,
            maquina = maquina,
            operador = operador,
            respuestas = respuestas
        )

        btnGuardar.isEnabled = false
        btnGuardar.text = "Guardando…"

        lifecycleScope.launch {
            // Online-primero: si el server responde, el checklist queda registrado
            // ANTES de que MainActivity intente iniciar turno (que lo valida).
            // 409 = ya existía para este turno → también es éxito.
            var continuar = true
            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.responderChecklist(req)
                }
                if (!resp.isSuccessful && resp.code() != 409) {
                    val detalle = try {
                        resp.errorBody()?.string()?.take(300)?.replace("\n", " ")?.trim()
                    } catch (_: Exception) { null } ?: "Error HTTP ${resp.code()}"
                    Toast.makeText(this@ChecklistEquipoActivity, detalle, Toast.LENGTH_LONG).show()
                    btnGuardar.isEnabled = true
                    btnGuardar.text = "GUARDAR Y CONTINUAR"
                    continuar = false
                }
            } catch (e: Exception) {
                // Sin red: encolar (se sincroniza antes que el INICIAR_TURNO por FIFO).
                ErrorReporter.warn("Checklist", "Sin red al responder, encolando: ${e.message}")
                colaRepo.enqueueChecklist(req)
            }

            if (continuar) {
                val turno = TurnoUtil.actual()
                AppDatabase.getInstance(applicationContext).checklistDao()
                    .marcarCompletado(maquina, operador, turno.fechaTurno, turno.codigo)
                setResult(RESULT_OK)
                finish()
            }
        }
    }
}
