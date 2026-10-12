package com.example.controller

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.*
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class AlertaPedidoActivity : AppCompatActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.e("SIGEP_ALARM", ">>> AlertaPedidoActivity lanzada")
        // CORRECCIÓN 4: Primera línea antes de setContentView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alerta_pedido)

        startIndustrialAlarm()

        findViewById<Button>(R.id.btnAceptarAlerta).setOnClickListener {
            stopAlarm()
            finish()
        }
    }

    private fun startIndustrialAlarm() {
        try {
            // 1. SONIDO CONTINUO (Usando alarm.mp3 en res/raw)
            mediaPlayer = MediaPlayer.create(this, R.raw.alarm).apply {
                isLooping = true
                start()
            }
            Log.e("SIGEP_ALARM", ">>> MediaPlayer iniciado")

            // 2. VIBRACIÓN DE ALERTA (1s On, 0.5s Off)
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            Log.e("SIGEP_ALARM", ">>> Vibrator iniciado")
            val pattern = longArrayOf(0, 1000, 500) // Patrón: Pausa, Vibrar, Pausa
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                // El reemplazo no-deprecado (VibratorManager + VibrationAttributes)
                // exige una rama adicional por API; la sobrecarga sigue operativa.
                @Suppress("DEPRECATION")
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0), attributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            ErrorReporter.report("AlertaPedido", "Fallo al iniciar alarma/vibración", e)
        }
    }

    private fun stopAlarm() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        vibrator?.cancel()
        // Cancela también la notificación del canal — su sonido (RingtoneManager
        // de tipo ALARM) sigue corriendo mientras la notif esté visible, aunque
        // ya hayamos detenido el MediaPlayer de alarm.mp3.
        SigepNotificationManager.cancelarAlertaPedido(this)
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }
}
