package com.example.controller

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.controller.util.versionNameInstalada
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MenuRolesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Barras del sistema transparentes con iconos oscuros, también en Android < 15,
        // para que el blanco de la cabecera llegue hasta el borde de la cámara.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu_roles)
        aplicarInsets()

        val btnOperador = configurarTarjeta(
            R.id.btnRolOperario, "Operario", "Producción, paros y checklist de la máquina",
            R.drawable.ic_rol_operario, R.color.rol_operario, R.color.rol_operario_suave
        )
        val btnInsumista = configurarTarjeta(
            R.id.btnRolInsumista, "Gestión de Insumos", "Pedidos y entregas de bodega",
            R.drawable.ic_rol_insumos, R.color.rol_insumos, R.color.rol_insumos_suave
        )
        val btnRolSupervisor = configurarTarjeta(
            R.id.btnRolSupervisor, "Supervisor", "Control de planta y maquinaria",
            R.drawable.ic_rol_supervisor, R.color.rol_supervisor, R.color.rol_supervisor_suave
        )
        val btnAdminAccess = findViewById<View>(R.id.btnAdminAccess)
        // versionNameInstalada() y NO BuildConfig.VERSION_NAME: esa constante se incrusta
        // en el .class y se queda vieja si el archivo no se recompila (ver VersionApp.kt).
        findViewById<TextView>(R.id.textViewVersion)?.text =
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

    /**
     * Sustituye, solo en esta pantalla, el relleno global de SigepApplication: ese relleno
     * deja una franja del color de la ventana entre la cámara y la cabecera. Aquí el alto
     * de la barra de estado va DENTRO de la cabecera blanca y el de la barra de navegación
     * dentro del fondo gris, así que no queda ninguna franja de otro color.
     */
    private fun aplicarInsets() {
        val contenido = findViewById<View>(android.R.id.content)
        val cabecera = findViewById<View>(R.id.cabeceraRoles)
        ViewCompat.setOnApplyWindowInsetsListener(contenido) { v, insets ->
            val barras = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(barras.left, 0, barras.right, 0)
            cabecera.setPadding(0, barras.top, 0, 0)
            (v as? ViewGroup)?.getChildAt(0)?.setPadding(0, 0, 0, barras.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(contenido)
    }

    /** Rellena una tarjeta de item_tarjeta_rol: el color del perfil va solo en la franja y el icono. */
    private fun configurarTarjeta(
        idTarjeta: Int,
        titulo: String,
        descripcion: String,
        @DrawableRes icono: Int,
        @ColorRes color: Int,
        @ColorRes colorSuave: Int
    ): View {
        val tarjeta = findViewById<View>(idTarjeta)
        val colorPerfil = ContextCompat.getColor(this, color)
        tarjeta.findViewById<View>(R.id.franjaRol).setBackgroundColor(colorPerfil)
        tarjeta.findViewById<View>(R.id.circuloRol).backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorSuave))
        tarjeta.findViewById<ImageView>(R.id.iconoRol).apply {
            setImageResource(icono)
            imageTintList = ColorStateList.valueOf(colorPerfil)
        }
        tarjeta.findViewById<TextView>(R.id.tituloRol).text = titulo
        tarjeta.findViewById<TextView>(R.id.descripcionRol).text = descripcion
        tarjeta.contentDescription = "$titulo. $descripcion"
        return tarjeta
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
