package com.example.controller.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.controller.BuildConfig
import com.example.controller.ErrorReporter
import com.example.controller.PrefsRepository
import com.example.controller.R
import com.example.controller.SigepNotificationManager

class AjustesTabletActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ajustes_tablet)

        val prefs     = PrefsRepository(applicationContext)
        val tvId      = findViewById<TextView>(R.id.tvDeviceId)
        val etNombre  = findViewById<EditText>(R.id.etNombre)
        val etMaquina = findViewById<EditText>(R.id.etMaquina)
        val btnCopiar = findViewById<Button>(R.id.btnCopiarId)
        val btnGuard  = findViewById<Button>(R.id.btnGuardar)

        tvId.text        = prefs.deviceId
        etNombre.setText(prefs.tabletNombre ?: "")
        etMaquina.setText(prefs.tabletMaquina ?: "")

        btnCopiar.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("device_id", prefs.deviceId))
            Toast.makeText(this, "Device ID copiado", Toast.LENGTH_SHORT).show()
        }

        btnGuard.setOnClickListener {
            prefs.tabletNombre  = etNombre.text.toString().trim().ifBlank { null }
            prefs.tabletMaquina = etMaquina.text.toString().trim().ifBlank { null }
            Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
        }

        configurarDiagnosticoFcm()
        configurarBotonesDebugCrashlytics()
    }

    // Muestra en pantalla el tipo_insumo_usuario y el token FCM que tiene la
    // tablet. Útil para verificar que coincide con dispositivos_fcm en el
    // backend — si los tokens no concuerdan, el FCM nunca llegará.
    private fun configurarDiagnosticoFcm() {
        val tvTipoInsumo = findViewById<TextView>(R.id.tvTipoInsumo)
        val tvFcmToken   = findViewById<TextView>(R.id.tvFcmToken)
        val btnCopiar    = findViewById<Button>(R.id.btnCopiarToken)

        val prefsRaw = getSharedPreferences("DetcuadorPrefs", Context.MODE_PRIVATE)
        val tipo  = prefsRaw.getString("tipo_insumo_usuario", null)
        val token = prefsRaw.getString("fcm_token", null)

        tvTipoInsumo.text = tipo ?: "(no seteado — el filtro descartará todos los FCM)"
        tvFcmToken.text   = token ?: "(no obtenido aún — abrir Bandeja Insumos para forzar)"

        btnCopiar.setOnClickListener {
            val t = prefsRaw.getString("fcm_token", null)
            if (t.isNullOrBlank()) {
                Toast.makeText(this, "Sin token todavía", Toast.LENGTH_SHORT).show()
            } else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("fcm_token", t))
                Toast.makeText(this, "Token copiado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Solo se exponen en debug — en release la sección queda oculta y los handlers
    // ni se montan. Cuando confirmemos que Crashlytics funciona en producción,
    // se puede quitar todo este bloque.
    private fun configurarBotonesDebugCrashlytics() {
        if (!BuildConfig.DEBUG) return

        findViewById<LinearLayout>(R.id.seccionDebugCrashlytics).visibility = View.VISIBLE

        findViewById<Button>(R.id.btnTestNonFatal).setOnClickListener {
            ErrorReporter.report(
                tag = "TestCrashlytics",
                message = "Reporte no-fatal de prueba desde Ajustes",
                throwable = RuntimeException("Non-fatal test ${System.currentTimeMillis()}")
            )
            Toast.makeText(
                this,
                "Reporte enviado. Espera 5–15 min en Firebase Console.",
                Toast.LENGTH_LONG
            ).show()
        }

        findViewById<Button>(R.id.btnTestCrash).setOnClickListener {
            Toast.makeText(this, "Crash en 1s — reabre la app después", Toast.LENGTH_SHORT).show()
            window.decorView.postDelayed({
                throw RuntimeException("Crash de prueba desde Ajustes")
            }, 1000)
        }

        // Dispara la misma cadena de alarma que ejecuta SigepFirebaseMessagingService
        // al recibir un FCM real, así podemos validar el flujo visual/sonoro
        // (canal, full-screen intent, AlertaPedidoActivity) sin esperar al backend.
        findViewById<Button>(R.id.btnTestAlarma).setOnClickListener {
            Toast.makeText(
                this,
                "Apaga la pantalla en los próximos 10s para probar el caso real",
                Toast.LENGTH_LONG
            ).show()
            window.decorView.postDelayed({
                SigepNotificationManager.dispararAlertaPedido(
                    applicationContext,
                    titulo = "TEST — Nuevo pedido",
                    cuerpo = "Simulación local de FCM"
                )
            }, 5000)
        }
    }
}
