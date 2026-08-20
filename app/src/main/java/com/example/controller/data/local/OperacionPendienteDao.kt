package com.example.controller.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class OperacionPendienteDao(context: Context) {

    private val db = SigepDatabase(context)
    private val _flow = MutableStateFlow<List<OperacionPendiente>>(emptyList())

    init {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch { refreshFlow() }
    }

    fun observarTodas(): Flow<List<OperacionPendiente>> = _flow.asStateFlow()

    private suspend fun refreshFlow() {
        _flow.value = withContext(Dispatchers.IO) { queryAll() }
    }

    private fun queryAll(): List<OperacionPendiente> =
        db.readableDatabase.query(
            "operaciones_pendientes", null, null, null, null, null, "id ASC"
        ).use { c -> generateSequence { if (c.moveToNext()) c.toOp() else null }.toList() }

    suspend fun insertar(op: OperacionPendiente): Long = withContext(Dispatchers.IO) {
        val id = db.writableDatabase.insert("operaciones_pendientes", null, op.toValues())
        refreshFlow()
        id
    }

    suspend fun proximaParaEnviar(): OperacionPendiente? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "operaciones_pendientes", null, "fatal = 0",
            null, null, null, "id ASC", "1"
        ).use { c -> if (c.moveToFirst()) c.toOp() else null }
    }

    suspend fun eliminar(id: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("operaciones_pendientes", "id = ?", arrayOf(id.toString()))
        refreshFlow()
    }

    suspend fun marcarError(id: Long, mensaje: String, fatal: Boolean) = withContext(Dispatchers.IO) {
        db.writableDatabase.execSQL(
            "UPDATE operaciones_pendientes SET intentos = intentos + 1, ultimoError = ?, fatal = ? WHERE id = ?",
            arrayOf<Any>(mensaje, if (fatal) 1 else 0, id)
        )
        refreshFlow()
    }

    suspend fun contarPendientes(): Int = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM operaciones_pendientes WHERE fatal = 0", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    suspend fun obtenerDependientesConSesionLocal(): List<OperacionPendiente> = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "operaciones_pendientes", null,
            "tipo != 'INICIAR_TURNO' AND fatal = 0 AND payloadJson LIKE '%\"sesion_id\":-99%'",
            null, null, null, "id ASC"
        ).use { c -> generateSequence { if (c.moveToNext()) c.toOp() else null }.toList() }
    }

    // Foto de paro que pertenece al paro cuya operación es [idOperacionParo]: la que se
    // encoló después de ese paro y antes del siguiente INICIAR_PARO. Acotarla por los
    // dos lados es lo que evita las asignaciones cruzadas — con la cola
    // [almuerzo, paro, foto] sin red, quedarse con "la primera posterior" le pegaría la
    // foto al almuerzo.
    suspend fun proximaFotoParoSinId(idOperacionParo: Long): OperacionPendiente? = withContext(Dispatchers.IO) {
        val id = idOperacionParo.toString()
        db.readableDatabase.rawQuery(
            """
            SELECT * FROM operaciones_pendientes
            WHERE tipo = 'FOTO_PARO' AND fatal = 0 AND id > ?
              AND payloadJson LIKE '%"paro_id":-99%'
              AND id < COALESCE(
                  (SELECT MIN(id) FROM operaciones_pendientes
                    WHERE tipo = 'INICIAR_PARO' AND id > ?),
                  9223372036854775807)
            ORDER BY id ASC LIMIT 1
            """.trimIndent(),
            arrayOf(id, id)
        ).use { c -> if (c.moveToFirst()) c.toOp() else null }
    }

    suspend fun existeFatalIniciarTurno(): Boolean = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM operaciones_pendientes WHERE tipo = 'INICIAR_TURNO' AND fatal = 1 LIMIT 1",
            null
        ).use { c -> c.moveToFirst() }
    }

    suspend fun existeIniciarTurnoEnCola(): Boolean = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM operaciones_pendientes WHERE tipo = 'INICIAR_TURNO' LIMIT 1",
            null
        ).use { c -> c.moveToFirst() }
    }

    suspend fun actualizar(op: OperacionPendiente) = withContext(Dispatchers.IO) {
        db.writableDatabase.update(
            "operaciones_pendientes", op.toValues(), "id = ?", arrayOf(op.id.toString())
        )
        refreshFlow()
    }

    private fun OperacionPendiente.toValues() = ContentValues().apply {
        put("tipo",        tipo)
        put("payloadJson", payloadJson)
        put("requestId",   requestId)
        put("creadoEn",    creadoEn)
        put("intentos",    intentos)
        put("ultimoError", ultimoError)
        put("fatal",       if (fatal) 1 else 0)
    }

    private fun Cursor.toOp() = OperacionPendiente(
        id          = getLong(getColumnIndexOrThrow("id")),
        tipo        = getString(getColumnIndexOrThrow("tipo")),
        payloadJson = getString(getColumnIndexOrThrow("payloadJson")),
        requestId   = getString(getColumnIndexOrThrow("requestId")),
        creadoEn    = getLong(getColumnIndexOrThrow("creadoEn")),
        intentos    = getInt(getColumnIndexOrThrow("intentos")),
        ultimoError = getString(getColumnIndexOrThrow("ultimoError")),
        fatal       = getInt(getColumnIndexOrThrow("fatal")) == 1
    )
}
