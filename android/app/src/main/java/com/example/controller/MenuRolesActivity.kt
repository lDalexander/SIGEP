package com.example.controller

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.controller.util.versionNameInstalada
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MenuRolesActivity : AppCompatActivity() {

    private lateinit var btnOperador: Button
    private lateinit var btnInsumista: Button
    private lateinit var btnAdminAccess: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu_roles)

        btnOperador = findViewById(R.id.btnRolOperario)
        btnInsumista = findViewById(R.id.btnRolInsumista)
        val btnRolSupervisor = findViewById<Button>(R.id.btnRolSupervisor)
        btnAdminAccess = findViewById(R.id.btnAdminAccess)
        // versionNameInstalada() y NO BuildConfig.VERSION_NAME: esa constante se incrusta
        // en el .class y se queda vieja si el archivo no se recompila (ver VersionApp.kt).
        findViewById<android.widget.TextView>(R.id.textViewVersion)?.text =
            "DETCUADOR S.A. | John Garcia | Versión ${versionNameInstalada()}"

        btnRolSupervisor.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                putExtra("rol", "SUPERVISOR")
            })
        }

        // DISEÑO INTENCIONAL: El acceso de operario es compartido en turno.
        // No se requiere PIN individual. Ver decisión de arquitectura v1.
        btnOperador.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        btnInsumista.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                putExtra("rol", "INSUMISTA")
            })
        }

        btnAdminAccess.setOnClickListener {
            mostrarDialogoAdminAuth()
        }
    }

    private fun mostrarDialogoAdminAuth() {
        val vistaDialogo = LayoutInflater.from(this).inflate(R.layout.dialog_admin_auth, null)
        val etUsuario = vistaDialogo.findViewById<TextInputEditText>(R.id.etAdminUser)
        val etContrasena = vistaDialogo.findViewById<TextInputEditText>(R.id.etAdminPassword)

        val dialogo = MaterialAlertDialogBuilder(this)
            .setView(vistaDialogo)
            .setCancelable(true)
            .setNegativeButton("Cancelar", null)
            // null para manejar la validación manualmente y evitar cierre automático en error
            .setPositiveButton("Ingresar", null)
            .create()

        dialogo.show()

        dialogo.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val usuario = etUsuario.text.toString().trim()
            val contrasena = etContrasena.text.toString().trim()

            if (usuario.isEmpty() || contrasena.isEmpty()) {
                Toast.makeText(this@MenuRolesActivity, "Complete las credenciales", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val btnIngresar = dialogo.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            btnIngresar.isEnabled = false
            btnIngresar.text = "Validando..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val solicitud = AdminLoginRequest(usuario, contrasena)
                    val respuesta = RetrofitClient.apiService.loginAdministrador(solicitud)

                    withContext(Dispatchers.Main) {
                        if (respuesta.isSuccessful && respuesta.body() != null) {
                            val datos = respuesta.body()!!
                            dialogo.dismiss()

                            Toast.makeText(this@MenuRolesActivity, "Bienvenido, ${datos.nivel_acceso}", Toast.LENGTH_SHORT).show()

                            RetrofitClient.tokenAdmin = datos.token
                            getSharedPreferences("DetcuadorPrefs", MODE_PRIVATE).edit().apply {
                                putInt("usuario_id", datos.admin_id)
                                putString("usuario_nombre", "Admin")
                                putString("usuario_rol", datos.nivel_acceso)
                                putString("admin_token", datos.token)
                                apply()
                            }

                            // El nivel de acceso determina qué vista del dashboard se muestra,
                            // evitando que Bodega vea datos de Planta y viceversa.
                            when (datos.nivel_acceso.uppercase()) {
                                "ADMINPLANTA", "ADMINBODEGA" -> {
                                    startActivity(Intent(this@MenuRolesActivity, SupervisorDashboardActivity::class.java))
                                }
                                else -> {
                                    startActivity(Intent(this@MenuRolesActivity, AdminDashboardActivity::class.java))
                                }
                            }
                        } else {
                            btnIngresar.isEnabled = true
                            btnIngresar.text = "Ingresar"
                            etContrasena.error = "Usuario o contraseña incorrectos"
                            etContrasena.setText("")
                        }
                    }
                } catch (e: Exception) {
                    ErrorReporter.report("MenuRoles", "Fallo en login de supervisor", e)
                    withContext(Dispatchers.Main) {
                        btnIngresar.isEnabled = true
                        btnIngresar.text = "Ingresar"
                        Toast.makeText(this@MenuRolesActivity, "Error de conexión con el servidor", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
