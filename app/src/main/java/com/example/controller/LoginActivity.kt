package com.example.controller

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    // --- VARIABLES DE LA CLASE ---
    private lateinit var spinnerUsuarios: Spinner
    private lateinit var etPin: EditText
    private lateinit var btnIngresar: Button
    private var listaUsuariosFull: List<ListaUsuariosResponse> = emptyList()

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Vinculamos las vistas con el XML
        spinnerUsuarios = findViewById(R.id.spinnerUsuariosLogin)
        etPin = findViewById(R.id.etPinAcceso)
        btnIngresar = findViewById(R.id.btnIngresar)

        // 🔥 NUEVO: Vinculamos el título para que sea dinámico
        val tvTituloLogin = findViewById<TextView>(R.id.tvTituloLogin)

        // Leemos con qué "disfraz" entraron desde el menú anterior
        val rolSeleccionado = intent.getStringExtra("rol") ?: "ACCESO"

        // Cambiamos el texto del título según el botón que presionaron
        if (rolSeleccionado == "OPERADOR") {
            tvTituloLogin.text = "PANEL DE OPERADOR"
        } else if (rolSeleccionado == "INSUMISTA") {
            tvTituloLogin.text = "GESTIÓN DE INSUMOS"
        } else if (rolSeleccionado == "SUPERVISOR") {
            tvTituloLogin.text = "ACCESO DE SUPERVISOR"
        }

        // 1. Cargar la lista de usuarios apenas abre la pantalla
        cargarUsuarios()

        btnIngresar.setOnClickListener {
            // Validamos que haya seleccionado un nombre y no el título "-- Seleccione --"
            if (spinnerUsuarios.selectedItemPosition <= 0) {
                Toast.makeText(this, "Seleccione un usuario", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val nombreSeleccionado = spinnerUsuarios.selectedItem.toString()
            val pinIngresado = etPin.text.toString()

            if (pinIngresado.length == 4) {
                validarAcceso(nombreSeleccionado, pinIngresado)
            } else {
                Toast.makeText(this, "El PIN debe tener 4 dígitos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cargarUsuarios() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.apiService.obtenerUsuarios()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        listaUsuariosFull = response.body()!!

                        // Armamos la lista para el Spinner
                        val nombres = listOf("-- Seleccione Usuario --") + listaUsuariosFull.map { it.nombre }

                        // Constructor con el molde cerrado (R.layout.item_spinner_negro)
                        val adapter = ArrayAdapter(this@LoginActivity, R.layout.item_spinner_negro, nombres)

                        // INDICAMOS EL MOLDE ABIERTO para forzar fondo blanco
                        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown_negro)

                        spinnerUsuarios.adapter = adapter
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@LoginActivity,
                        "Error al descargar lista de usuarios",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun validarAcceso(nombre: String, pin: String) {
        btnIngresar.isEnabled = false
        btnIngresar.text = "Verificando..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = LoginRequest(nombre = nombre, pin = pin)
                val response = RetrofitClient.apiService.iniciarSesion(request)

                withContext(Dispatchers.Main) {
                    btnIngresar.isEnabled = true
                    btnIngresar.text = "INGRESAR"

                    if (response.isSuccessful && response.body() != null) {
                        val user = response.body()!!

                        // Guardar sesión en la memoria de la tablet
                        getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE).edit().apply {
                            putInt("usuario_id", user.id)
                            putString("usuario_nombre", user.nombre)
                            putString("usuario_rol", user.rol)
                            putLong("tiempo_login", System.currentTimeMillis())
                            apply()
                        }

                        Toast.makeText(
                            this@LoginActivity,
                            "Bienvenido, ${user.nombre}",
                            Toast.LENGTH_SHORT
                        ).show()

                        // 🔥 NUEVO: Enrutamiento industrial a 3 vías
                        if (user.rol.uppercase() == "OPERADOR") {
                            // Operador va a producción
                            val intent = Intent(this@LoginActivity, MainActivity::class.java)
                            startActivity(intent)
                        } else if (user.rol.uppercase() == "SUPERVISOR") {
                            // Supervisor va a la Torre de Control
                            val intent = Intent(this@LoginActivity, SupervisorDashboardActivity::class.java)
                            startActivity(intent)
                        } else {
                            // Insumista va a la bandeja de pedidos
                            val intent = Intent(this@LoginActivity, BandejaInsumosActivity::class.java)
                            startActivity(intent)
                        }
                        finish() // Destruimos el Login para que no puedan volver atrás

                    } else {
                        Toast.makeText(this@LoginActivity, "PIN incorrecto", Toast.LENGTH_SHORT).show()
                        etPin.text.clear()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnIngresar.isEnabled = true
                    btnIngresar.text = "INGRESAR"
                    Toast.makeText(
                        this@LoginActivity,
                        "Sin conexión al servidor",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}-