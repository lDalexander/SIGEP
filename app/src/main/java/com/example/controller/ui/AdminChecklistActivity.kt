package com.example.controller.ui

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.controller.ChecklistCampo
import com.example.controller.ChecklistCampoRequest
import com.example.controller.ChecklistPlantilla
import com.example.controller.ChecklistPlantillaCabeceraRequest
import com.example.controller.ChecklistTipoCampo
import com.example.controller.ErrorReporter
import com.example.controller.R
import com.example.controller.RetrofitClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Constructor de formularios (tipo Scan-it-to-Office) para el checklist de
 * "Estado de equipo". Acceso restringido: sólo se llega tras validar PIN de
 * admin en MainActivity. Permite crear la plantilla y agregar / editar /
 * eliminar campos. Todo va al backend vía los endpoints admin de checklist.
 */
class AdminChecklistActivity : AppCompatActivity() {

    private lateinit var contenedor: LinearLayout
    private lateinit var btnAgregar: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvInfo: TextView

    private var plantilla: ChecklistPlantilla? = null

    // Etiquetas amigables ↔ valores del enum del backend.
    private val tiposLabels = listOf(
        "Texto corto", "Texto largo", "Casilla (Sí/No)",
        "Selección única", "Selección múltiple", "Número"
    )
    private val tiposValores = listOf(
        ChecklistTipoCampo.TEXTO_CORTO, ChecklistTipoCampo.TEXTO_LARGO,
        ChecklistTipoCampo.CHECKBOX, ChecklistTipoCampo.SELECCION_UNICA,
        ChecklistTipoCampo.SELECCION_MULTIPLE, ChecklistTipoCampo.NUMERO
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_checklist)

        contenedor = findViewById(R.id.contenedorCamposAdmin)
        btnAgregar = findViewById(R.id.btnAgregarCampo)
        progress = findViewById(R.id.progressAdmin)
        tvInfo = findViewById(R.id.tvPlantillaInfo)

        btnAgregar.setOnClickListener {
            val p = plantilla
            if (p == null) crearPlantilla() else dialogoCampo(null)
        }

        cargar()
    }

    private fun cargar() {
        progress.visibility = View.VISIBLE
        btnAgregar.isEnabled = false
        lifecycleScope.launch {
            val resultado = try {
                withContext(Dispatchers.IO) { RetrofitClient.apiService.adminListarPlantillas() }
            } catch (e: Exception) {
                ErrorReporter.warn("AdminChecklist", "Error listando plantillas: ${e.message}")
                null
            }
            progress.visibility = View.GONE
            btnAgregar.isEnabled = true

            if (resultado == null || !resultado.isSuccessful) {
                Toast.makeText(this@AdminChecklistActivity, "No se pudo cargar (revise conexión)", Toast.LENGTH_LONG).show()
                return@launch
            }
            // Plantilla activa (o la primera disponible).
            plantilla = resultado.body()?.let { lista ->
                lista.firstOrNull { it.activa } ?: lista.firstOrNull()
            }
            render()
        }
    }

    private fun render() {
        contenedor.removeAllViews()
        val p = plantilla
        if (p == null) {
            tvInfo.text = "Sin formulario creado"
            btnAgregar.text = "CREAR FORMULARIO"
            agregarTextoVacio("Aún no existe un formulario.\nToque \"CREAR FORMULARIO\" para empezar.")
            return
        }

        tvInfo.text = "${p.nombre}  •  v${p.version}"
        btnAgregar.text = "+ AGREGAR CAMPO"

        if (p.campos.isEmpty()) {
            agregarTextoVacio("Este formulario no tiene campos todavía.")
            return
        }

        p.campos.sortedBy { it.orden }.forEach { campo ->
            contenedor.addView(filaCampo(campo))
        }
    }

    private fun agregarTextoVacio(msg: String) {
        contenedor.addView(TextView(this).apply {
            text = msg
            textSize = 15f
            setTextColor(0xFF607D8B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, 0)
        })
    }

    private fun filaCampo(campo: ChecklistCampo): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        card.addView(TextView(this).apply {
            text = campo.etiqueta + if (campo.requerido) "  *" else ""
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
        })

        val sub = StringBuilder(etiquetaTipo(campo.tipo))
        if (!campo.opciones.isNullOrEmpty()) sub.append("  ·  ${campo.opciones.joinToString(", ")}")
        card.addView(TextView(this).apply {
            text = sub.toString()
            textSize = 13f
            setTextColor(0xFF607D8B.toInt())
            setPadding(0, dp(2), 0, dp(8))
        })

        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila.addView(Button(this).apply {
            text = "Editar"
            setOnClickListener { dialogoCampo(campo) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        fila.addView(Button(this).apply {
            text = "Eliminar"
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFEF4444.toInt())
            setOnClickListener { confirmarEliminar(campo) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) }
        })
        card.addView(fila)
        return card
    }

    private fun etiquetaTipo(valor: String): String {
        val i = tiposValores.indexOf(valor)
        return if (i >= 0) tiposLabels[i] else valor
    }

    // ==========================================
    // CREAR PLANTILLA
    // ==========================================
    private fun crearPlantilla() {
        val input = EditText(this).apply {
            hint = "Nombre del formulario"
            setText("Estado de Equipo")
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Crear formulario")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Crear") { _, _ ->
                val nombre = input.text.toString().trim().ifBlank { "Estado de Equipo" }
                lifecycleScope.launch {
                    val ok = llamarApi {
                        RetrofitClient.apiService.adminCrearPlantilla(
                            ChecklistPlantillaCabeceraRequest(nombre = nombre)
                        )
                    }
                    if (ok) cargar()
                }
            }
            .show()
    }

    // ==========================================
    // DIÁLOGO DE CAMPO (AGREGAR / EDITAR)
    // ==========================================
    private fun dialogoCampo(existente: ChecklistCampo?) {
        val p = plantilla ?: return

        val cont = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
        }

        cont.addView(label("Etiqueta (lo que ve el operario)"))
        val etEtiqueta = EditText(this).apply {
            hint = "Ej: Estado general / ¿Tapas abiertas?"
            setText(existente?.etiqueta ?: "")
        }
        cont.addView(etEtiqueta)

        cont.addView(label("Tipo de campo"))
        val spTipo = Spinner(this).apply {
            adapter = ArrayAdapter(this@AdminChecklistActivity,
                android.R.layout.simple_spinner_dropdown_item, tiposLabels)
            existente?.let { setSelection(tiposValores.indexOf(it.tipo).coerceAtLeast(0)) }
        }
        cont.addView(spTipo)

        cont.addView(label("Opciones (sólo selección única/múltiple, separadas por coma)"))
        val etOpciones = EditText(this).apply {
            hint = "Limpio, Sucio, Regular"
            setText(existente?.opciones?.joinToString(", ") ?: "")
        }
        cont.addView(etOpciones)

        cont.addView(label("Orden (posición en el formulario)"))
        val etOrden = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((existente?.orden ?: ((p.campos.maxOfOrNull { it.orden } ?: 0) + 1)).toString())
        }
        cont.addView(etOrden)

        val cbRequerido = CheckBox(this).apply {
            text = "Obligatorio"
            isChecked = existente?.requerido ?: true
            setPadding(0, dp(12), 0, 0)
        }
        cont.addView(cbRequerido)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existente == null) "Agregar campo" else "Editar campo")
            .setView(cont)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar", null)
            .create()
            .also { dlg ->
                dlg.show()
                dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val etiqueta = etEtiqueta.text.toString().trim()
                    if (etiqueta.isEmpty()) {
                        etEtiqueta.error = "Requerido"; return@setOnClickListener
                    }
                    val tipo = tiposValores[spTipo.selectedItemPosition]
                    val opciones = etOpciones.text.toString()
                        .split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
                    val esSeleccion = tipo == ChecklistTipoCampo.SELECCION_UNICA ||
                        tipo == ChecklistTipoCampo.SELECCION_MULTIPLE
                    if (esSeleccion && opciones.isEmpty()) {
                        etOpciones.error = "Agregue al menos una opción"; return@setOnClickListener
                    }
                    val orden = etOrden.text.toString().toIntOrNull() ?: 0
                    val req = ChecklistCampoRequest(
                        etiqueta = etiqueta,
                        tipo = tipo,
                        requerido = cbRequerido.isChecked,
                        opciones = if (esSeleccion) opciones else null,
                        orden = orden
                    )
                    lifecycleScope.launch {
                        val ok = llamarApi {
                            if (existente == null)
                                RetrofitClient.apiService.adminAgregarCampo(p.id, req)
                            else
                                RetrofitClient.apiService.adminEditarCampo(existente.id, req)
                        }
                        if (ok) { dlg.dismiss(); cargar() }
                    }
                }
            }
    }

    private fun confirmarEliminar(campo: ChecklistCampo) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar campo")
            .setMessage("¿Eliminar \"${campo.etiqueta}\" del formulario?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch {
                    val ok = llamarApi { RetrofitClient.apiService.adminEliminarCampo(campo.id) }
                    if (ok) cargar()
                }
            }
            .show()
    }

    // Ejecuta una llamada Retrofit en IO y avisa en caso de error. Devuelve true si fue exitosa.
    private suspend fun <T> llamarApi(bloque: suspend () -> retrofit2.Response<T>): Boolean {
        return try {
            val resp = withContext(Dispatchers.IO) { bloque() }
            if (resp.isSuccessful) {
                true
            } else {
                val detalle = try {
                    resp.errorBody()?.string()?.take(200)?.replace("\n", " ")?.trim()
                } catch (_: Exception) { null } ?: "Error HTTP ${resp.code()}"
                Toast.makeText(this, detalle, Toast.LENGTH_LONG).show()
                false
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error de conexión: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun label(txt: String) = TextView(this).apply {
        text = txt
        textSize = 13f
        setTextColor(0xFF607D8B.toInt())
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
