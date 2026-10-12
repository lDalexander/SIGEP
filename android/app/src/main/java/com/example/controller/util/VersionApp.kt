package com.example.controller.util

import android.content.Context
import com.example.controller.BuildConfig
import com.example.controller.ErrorReporter

/**
 * Versión legible de la app, leída del **paquete instalado** y no de `BuildConfig`.
 *
 * No es una preferencia de estilo: `BuildConfig.VERSION_NAME` es un
 * `static final String`, o sea una **constante en tiempo de compilación**, y Kotlin la
 * incrusta como literal en el `.class` de cada archivo que la usa. El compilador
 * incremental no recompila un archivo porque haya cambiado el *valor* de esa constante,
 * así que toda pantalla que no se toque en un release se queda mostrando la versión de
 * la última vez que se compiló. Pasó de verdad: con la 1.5.1 instalada,
 * `MenuRolesActivity` y `SplashActivity` seguían enseñando «Versión 1.2.3» (lo último
 * que se compiló de esos archivos), mientras `MainActivity` —editada ese día— sí decía
 * 1.5.1. El `versionName` del PackageManager sale del manifest del APK instalado y no
 * puede quedar desfasado.
 *
 * Mismo criterio que `SplashActivity.obtenerCodigoVersion()` usa para el `versionCode`
 * del updater OTA.
 */
fun Context.versionNameInstalada(): String {
    return try {
        val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        // versionName es nullable en el SDK; el fallback a BuildConfig solo actúa si el
        // PackageManager no lo devuelve, que en la práctica no pasa.
        info.versionName ?: BuildConfig.VERSION_NAME
    } catch (e: Exception) {
        ErrorReporter.report("VersionApp", "No se pudo leer versionName del package", e)
        BuildConfig.VERSION_NAME
    }
}
