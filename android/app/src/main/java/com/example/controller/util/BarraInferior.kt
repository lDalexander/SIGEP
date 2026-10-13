package com.example.controller.util

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Barra de pestañas inferior pegada a los botones del sistema, sin franja clara entre
 * medio. La usan la torre de control del supervisor y la bandeja del insumista.
 *
 * `SigepApplication` rellena por abajo el contenido de TODAS las activities con el alto
 * de la barra de navegación del sistema, y ese hueco salía con el fondo claro de la
 * ventana debajo de la barra oscura. Aquí ese relleno inferior lo pone la propia barra,
 * así su color se extiende por detrás de los botones. Se consume el inset para que ni
 * el layout ni el BottomNavigationView (que lo aplica por su cuenta) lo sumen otra vez.
 *
 * El layout de la activity NO debe llevar `fitsSystemWindows`: sumaría el mismo hueco.
 */
object BarraInferior {

    fun pegarAlBorde(activity: Activity, barra: View) {
        val content = activity.findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val barras = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(barras.left, barras.top, barras.right, 0)
            barra.updatePadding(bottom = barras.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)

        // Botones del sistema en claro sobre la barra oscura, y sin el velo gris que
        // Android pone por defecto sobre la navegación de 3 botones.
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .isAppearanceLightNavigationBars = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
        }
    }
}
