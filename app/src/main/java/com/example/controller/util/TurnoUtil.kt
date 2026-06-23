package com.example.controller.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Cálculo del turno de trabajo (Día / Noche) y de la fecha de turno.
 *
 * Reglas (zona horaria Ecuador, America/Guayaquil, UTC-5):
 *  - DÍA   : 07:00–18:59 → fecha = día actual.
 *  - NOCHE : 19:00–06:59 (cruza medianoche).
 *      - 00:00–06:59 → el turno empezó la noche anterior → fecha = día anterior.
 *      - 19:00–23:59 → fecha = día actual.
 *
 * La clave de deduplicación de un checklist es (maquina, operador, fechaTurno, codigo).
 * El backend recalcula esto con SU hora; aquí lo replicamos para el guard offline.
 */
object TurnoUtil {

    const val DIA = "DIA"
    const val NOCHE = "NOCHE"

    private const val TZ = "America/Guayaquil"

    data class TurnoInfo(val codigo: String, val fechaTurno: String)

    fun actual(ahoraMs: Long = System.currentTimeMillis()): TurnoInfo {
        val tz = TimeZone.getTimeZone(TZ)
        val cal = Calendar.getInstance(tz).apply { time = Date(ahoraMs) }
        val hora = cal.get(Calendar.HOUR_OF_DAY)

        val codigo: String
        if (hora in 7..18) {
            codigo = DIA
        } else {
            codigo = NOCHE
            // Madrugada (<= 06:59) pertenece al turno que arrancó la noche anterior.
            if (hora <= 6) cal.add(Calendar.DAY_OF_YEAR, -1)
        }

        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        return TurnoInfo(codigo, fmt.format(cal.time))
    }
}
