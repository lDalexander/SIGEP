package com.example.controller

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.controller.ui.SplashScreenAnimada
import com.example.controller.util.versionNameInstalada
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        // versionNameInstalada() y NO BuildConfig.VERSION_NAME (ver VersionApp.kt): esta
        // pantalla es justo una de las que se quedó mostrando «1.2.3» con la 1.5.1 puesta.
        val version = versionNameInstalada()
        setContent {
            // API de actualización deshabilitada temporalmente (aún no integrada).
            // Para reactivarla, reemplazar continuarAPrincipal() por: verificarActualizacion()
            SplashScreenAnimada(version = version) { continuarAPrincipal() }
        }
    }

    @Suppress("unused")
    private fun verificarActualizacion() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.checkVersion()
                }

                if (response.isSuccessful) {
                    val infoVersion = response.body()
                    val codigoVersionActual = obtenerCodigoVersion()

                    if (infoVersion != null && infoVersion.version_code > codigoVersionActual) {
                        mostrarDialogoActualizacion(infoVersion.url_descarga, infoVersion.version_name)
                    } else {
                        continuarAPrincipal()
                    }
                } else {
                    // Si el servidor no responde con la versión, dejamos pasar al usuario
                    // para no bloquear el acceso a la app por un fallo de infraestructura.
                    continuarAPrincipal()
                }
            } catch (e: Exception) {
                // Sin red al arrancar: continuamos sin forzar actualización
                // para que la app sea usable en condiciones de red degradada.
                ErrorReporter.report("SplashActivity", "Fallo al consultar versión", e)
                continuarAPrincipal()
            }
        }
    }

    private fun obtenerCodigoVersion(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode
            }
        } catch (e: Exception) {
            ErrorReporter.report("SplashActivity", "No se pudo leer versionCode del package", e)
            0
        }
    }

    private fun mostrarDialogoActualizacion(urlDescarga: String, nombreNuevaVersion: String) {
        AlertDialog.Builder(this)
            .setTitle("Actualización Disponible")
            .setMessage("Hay una nueva versión disponible ($nombreNuevaVersion). Es necesario actualizar para continuar.")
            .setCancelable(false)
            .setPositiveButton("ACTUALIZAR") { _, _ ->
                descargarApk(urlDescarga)
            }
            .show()
    }

    private fun descargarApk(url: String) {
        var urlFinal = url
        // Si la URL es relativa, el servidor la generó sin host. La completamos aquí
        // para que el DownloadManager pueda resolverla sin conocer la IP del backend.
        if (!url.startsWith("http")) {
            val baseUrl = RetrofitClient.BASE_URL.removeSuffix("/")
            urlFinal = if (url.startsWith("/")) "$baseUrl$url" else "$baseUrl/$url"
        }

        Toast.makeText(this, "Iniciando descarga...", Toast.LENGTH_LONG).show()

        val archivo = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "ControllerUpdate.apk"
        )
        if (archivo.exists()) archivo.delete()

        val solicitudDescarga = try {
            DownloadManager.Request(Uri.parse(urlFinal))
                .setTitle("Descargando actualización")
                .setDescription("Controller se está actualizando")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ControllerUpdate.apk")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
        } catch (e: Exception) {
            Toast.makeText(this, "URL inválida ($urlFinal): ${e.message}", Toast.LENGTH_LONG).show()
            continuarAPrincipal()
            return
        }

        val gestorDescarga = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val idDescarga = gestorDescarga.enqueue(solicitudDescarga)

        val alCompletar = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == idDescarga) {
                    val consulta = DownloadManager.Query().setFilterById(idDescarga)
                    val cursor = gestorDescarga.query(consulta)
                    if (cursor != null && cursor.moveToFirst()) {
                        val estado = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (estado == DownloadManager.STATUS_SUCCESSFUL) {
                            instalarApk()
                        } else {
                            val razon = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            val msg = when (razon) {
                                404  -> "Error 404: el servidor no encontró el APK en $urlFinal"
                                else -> "Error en la descarga: código $razon"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            continuarAPrincipal()
                        }
                        cursor.close()
                    } else {
                        continuarAPrincipal()
                    }
                    try { unregisterReceiver(this) } catch (e: Exception) { /* ya desregistrado */ }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alCompletar, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(alCompletar, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun instalarApk() {
        try {
            val archivo = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ControllerUpdate.apk"
            )
            if (archivo.exists()) {
                // FileProvider es obligatorio desde Android 7 para compartir URIs de archivo
                // entre aplicaciones sin exponer rutas de almacenamiento interno.
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", archivo)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                Toast.makeText(this, "Abriendo instalador...", Toast.LENGTH_SHORT).show()
                startActivity(intent)
            } else {
                Toast.makeText(this, "Error: no se encontró el archivo descargado", Toast.LENGTH_SHORT).show()
                continuarAPrincipal()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error al instalar: ${e.message}", Toast.LENGTH_LONG).show()
            continuarAPrincipal()
        }
    }

    private fun continuarAPrincipal() {
        startActivity(Intent(this, MenuRolesActivity::class.java))
        finish()
    }
}
