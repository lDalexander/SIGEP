package com.example.controller // <-- Asegúrate de que este sea tu paquete

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Ocultar la barra superior (ActionBar) para que se vea a pantalla completa
        supportActionBar?.hide()

        // Temporizador de 2 segundos (2000 milisegundos)
        Handler(Looper.getMainLooper()).postDelayed({
            // Instrucción de salto a la pantalla principal
            val intent = Intent(this, MenuRolesActivity::class.java)
            startActivity(intent)

            // "Matamos" el Splash para que si el usuario presiona "Atrás", no regrese al logo
            finish()
        }, 2000)
    }
}