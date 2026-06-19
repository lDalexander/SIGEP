package com.example.controller.util

/**
 * Ítems fijos del checklist de mantenimiento y limpieza, separados por momento.
 * Quemados en el cliente por ahora (no configurables). Las etiquetas son la clave
 * que se envía al backend en cada llenado.
 */
object MantenimientoItems {

    val ENTRADA: List<String> = listOf(
        "Revisión de conexión de cables de mordaza",
        "Revisión de presión de aire comprimido",
        "Revisión de parámetros en pantalla HMI",
        "Realizar lubricación de barras y rodamientos",
        "Revisar el estado del teflón",
        "Cambio de teflón del sellador"
    )

    val SALIDA: List<String> = listOf(
        "Cerrar completamente el gabinete eléctrico antes de limpiar",
        "Limpieza general externa con aire a presión (sin agua)",
        "Limpieza de estructura exterior",
        "Limpieza del soporte de lámina de rollo",
        "Limpieza del sistema de arrastre, tubo dispensador y cuello de lámina",
        "Limpieza de sensor de taca"
    )

    /** Devuelve la lista de ítems según el momento (ENTRADA/SALIDA). */
    fun paraMomento(momento: String): List<String> = when (momento) {
        com.example.controller.MantenimientoMomento.SALIDA -> SALIDA
        else -> ENTRADA
    }
}
