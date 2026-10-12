package com.example.controller

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Popup que muestra al operario un mensaje enviado desde el admin web (CAMBIO 4).
 * Se abre sobre la pantalla de producción (y enciende la pantalla si estaba apagada).
 * Al mostrarse hace ACK al servidor para que el mensaje no se vuelva a entregar.
 *
 * Llega por dos vías que convergen aquí (ver [MensajesAdmin]): WebSocket de tablets
 * (instantáneo) y heartbeat (respaldo). El dedupe evita popups repetidos.
 */
class MensajeAdminActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        setTitle("Mensaje de planta")

        val id = intent.getIntExtra(EXTRA_ID, -1)
        val texto = intent.getStringExtra(EXTRA_TEXTO).orEmpty()

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "📢 Mensaje del supervisor"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F5A623"))
        })
        root.addView(TextView(this).apply {
            text = texto
            textSize = 22f
            setPadding(0, dp(16), 0, dp(20))
            setTextColor(Color.parseColor("#111111"))
        })
        root.addView(Button(this).apply {
            text = "ENTENDIDO"
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
        root.gravity = Gravity.CENTER_VERTICAL
        setContentView(root)

        if (id > 0) ackMensaje(id)
    }

    private fun ackMensaje(id: Int) {
        val deviceId = PrefsRepository(applicationContext).deviceId
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RetrofitClient.apiService.marcarMensajeLeido(id, deviceId)
            } catch (e: Exception) {
                // El servidor lo reintentará entregar; no es fatal.
                ErrorReporter.report("MensajeAdmin", "Fallo al hacer ACK del mensaje $id", e)
            }
        }
    }

    companion object {
        const val EXTRA_ID = "mensaje_id"
        const val EXTRA_TEXTO = "mensaje_texto"
    }
}

/**
 * Punto único de entrada para mostrar un mensaje admin, con dedupe por id para que
 * el mismo mensaje (que puede llegar por WS y por heartbeat) no abra dos popups.
 */
object MensajesAdmin {
    private val manejados = Collections.synchronizedSet(mutableSetOf<Int>())

    fun mostrar(ctx: Context, id: Int, texto: String) {
        if (texto.isBlank()) return
        // add() devuelve false si el id ya estaba → ya se manejó en este proceso.
        if (id > 0 && !manejados.add(id)) return
        val intent = Intent(ctx, MensajeAdminActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MensajeAdminActivity.EXTRA_ID, id)
            putExtra(MensajeAdminActivity.EXTRA_TEXTO, texto)
        }
        ctx.startActivity(intent)
    }
}
