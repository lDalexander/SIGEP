package com.example.controller

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MenuRolesActivity : AppCompatActivity() {

    // Variables para los perfiles normales
    private lateinit var btnOperador: Button
    private lateinit var btnInsumista: Button

    // Variable para el acceso administrativo (Botón del engranaje)
    private lateinit var btnAdminAccess: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu_roles)

        // 1. Vincular los botones de perfiles operativos
        btnOperador = findViewById(R.id.btnRolOperario)
        btnInsumista = findViewById(R.id.btnRolInsumista)
        val btnRolSupervisor = findViewById<Button>(R.id.btnRolSupervisor)

        btnRolSupervisor.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            intent.putExtra("rol", "SUPERVISOR") // Enviamos el disfraz de supervisor
            startActivity(intent)
        }

        // 2. Vincular el botón administrativo
        btnAdminAccess = findViewById(R.id.btnAdminAccess)

        // 3. Listeners para perfiles normales
        btnOperador.setOnClickListener {
            // 🔥 El operador va DIRECTO a Control de Producción (MainActivity)
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }

        btnInsumista.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            intent.putExtra("rol", "INSUMISTA")
            startActivity(intent)
        }

        // 4. Listener para el acceso administrativo
        btnAdminAccess.setOnClickListener {
            showAdminAuthDialog()
        }
    }

    private fun showAdminAuthDialog() {
        // Inflamos la vista personalizada que creamos en el paso 1
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_admin_auth, null)
        val etUser = dialogView.findViewById<TextInputEditText>(R.id.etAdminUser)
        val etPassword = dialogView.findViewById<TextInputEditText>(R.id.etAdminPassword)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(true)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Ingresar", null) // null para manejar la validación manual
            .create()

        dialog.show()

        // Manejo manual del clic en "Ingresar" para evitar que se cierre si hay error
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val user = etUser.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (user.isEmpty() || password.isEmpty()) {
                Toast.makeText(this@MenuRolesActivity, "Complete las credenciales", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 1. Damos feedback visual y evitamos doble clic
            val btnIngresar = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            btnIngresar.isEnabled = false
            btnIngresar.text = "Validando..."

            // 2. Iniciamos el trabajo asíncrono (Comunicación con FastAPI)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // Empaquetamos los datos
                    val request = AdminLoginRequest(user, password)
                    // Hacemos la llamada al servidor
                    val response = RetrofitClient.apiService.loginAdministrador(request)

                    // 3. Volvemos al hilo principal para actualizar la pantalla
                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful && response.body() != null) {
                            // CREDENCIALES CORRECTAS (Código 200)
                            val datos = response.body()!!
                            dialog.dismiss()

                            Toast.makeText(this@MenuRolesActivity, "Bienvenido, ${datos.nivel_acceso}", Toast.LENGTH_SHORT).show()

                            // Navegar al panel de administración de D-Stock
                            val intent = Intent(this@MenuRolesActivity, AdminDashboardActivity::class.java)
                            startActivity(intent)
                        } else {
                            // CREDENCIALES INCORRECTAS (Código 401)
                            btnIngresar.isEnabled = true
                            btnIngresar.text = "Ingresar"
                            etPassword.error = "Usuario o contraseña incorrectos"
                            etPassword.setText("")
                        }
                    }
                } catch (e: Exception) {
                    // ERROR DE RED O SERVIDOR CAÍDO
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