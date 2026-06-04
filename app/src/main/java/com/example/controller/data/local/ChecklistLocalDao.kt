package com.example.controller.data.local

import android.content.ContentValues
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persistencia local del checklist de estado de equipo:
 *  - Caché de la plantilla activa (para llenar el formulario sin red).
 *  - Registro de checklists ya completados (guard offline para no repetir el
 *    formulario cuando la misma máquina + mismo operador ya lo hizo este turno).
 */
class ChecklistLocalDao(context: Context) {

    private val db = SigepDatabase(context)

    // ---- Plantilla en caché (un único registro, id = 1) ----

    suspend fun guardarPlantilla(json: String) = withContext(Dispatchers.IO) {
        val valores = ContentValues().apply {
            put("id", 1)
            put("json", json)
            put("guardadaEn", System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(
            "checklist_plantilla_cache", null, valores,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        Unit
    }

    suspend fun leerPlantilla(): String? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "checklist_plantilla_cache", arrayOf("json"),
            "id = 1", null, null, null, null
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    // ---- Guard de completados ----

    suspend fun marcarCompletado(
        maquina: String,
        operador: String,
        fechaTurno: String,
        codigoTurno: String
    ) = withContext(Dispatchers.IO) {
        val valores = ContentValues().apply {
            put("maquina", maquina)
            put("operador", operador)
            put("fechaTurno", fechaTurno)
            put("codigoTurno", codigoTurno)
            put("creadoEn", System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(
            "checklist_completados", null, valores,
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        Unit
    }

    suspend fun yaCompletado(
        maquina: String,
        operador: String,
        fechaTurno: String,
        codigoTurno: String
    ): Boolean = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM checklist_completados WHERE maquina = ? AND operador = ? AND fechaTurno = ? AND codigoTurno = ? LIMIT 1",
            arrayOf(maquina, operador, fechaTurno, codigoTurno)
        ).use { c -> c.moveToFirst() }
    }
}
