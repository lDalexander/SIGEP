package com.example.controller.data.local

import android.content.ContentValues
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Guard local del checklist de mantenimiento: registra qué momento
 * (ENTRADA/SALIDA) ya se llenó para un turno dado, para no volver a pedirlo si la
 * app se reinicia a mitad del turno. La clave de turno (fecha + código) se calcula
 * con TurnoUtil, igual que en el resto del flujo offline.
 */
class MantenimientoChecklistDao(context: Context) {

    private val db = SigepDatabase(context)

    suspend fun marcarCompletado(
        maquina: String,
        operador: String,
        fechaTurno: String,
        codigoTurno: String,
        momento: String,
        itemsJson: String
    ) = withContext(Dispatchers.IO) {
        val valores = ContentValues().apply {
            put("maquina", maquina)
            put("operador", operador)
            put("fechaTurno", fechaTurno)
            put("codigoTurno", codigoTurno)
            put("momento", momento)
            put("itemsJson", itemsJson)
            put("creadoEn", System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(
            "mantenimiento_checklist", null, valores,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        Unit
    }

    suspend fun yaCompletado(
        maquina: String,
        operador: String,
        fechaTurno: String,
        codigoTurno: String,
        momento: String
    ): Boolean = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM mantenimiento_checklist WHERE maquina = ? AND operador = ? AND fechaTurno = ? AND codigoTurno = ? AND momento = ? LIMIT 1",
            arrayOf(maquina, operador, fechaTurno, codigoTurno, momento)
        ).use { c -> c.moveToFirst() }
    }
}
