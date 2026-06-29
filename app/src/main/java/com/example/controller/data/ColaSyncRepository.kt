package com.example.controller.data

import android.content.Context
import androidx.work.*
import com.example.controller.*
import com.example.controller.data.local.AppDatabase
import com.example.controller.data.local.OperacionPendiente
import com.example.controller.sync.SyncWorker
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ColaSyncRepository(private val context: Context) {

    private val dao = AppDatabase.getInstance(context).operacionDao()
    private val gson = Gson()

    val operacionesFlow: Flow<List<OperacionPendiente>> = dao.observarTodas()

    suspend fun contarPendientes(): Int = dao.contarPendientes()

    suspend fun existeIniciarTurnoEnCola(): Boolean = dao.existeIniciarTurnoEnCola()

    suspend fun enqueueIniciarTurno(req: IniciarTurnoRequest) {
        // Si quien encola ya generó un request_id (ej. tras un intento directo que
        // falló por red), lo respetamos. El server usa ese id para deduplicar y
        // evitar crear dos turnos si la respuesta del intento directo se perdió.
        val requestId = req.request_id.ifBlank { UUID.randomUUID().toString() }
        val reqConId = req.copy(request_id = requestId)
        dao.insertar(OperacionPendiente(
            tipo        = "INICIAR_TURNO",
            payloadJson = gson.toJson(reqConId),
            requestId   = requestId,
            creadoEn    = System.currentTimeMillis()
        ))
        dispararSync()
    }

    suspend fun enqueueRegistrarPallet(req: RegistrarPalletRequest) {
        val requestId = UUID.randomUUID().toString()
        dao.insertar(OperacionPendiente(
            tipo        = "REGISTRAR_PALLET",
            payloadJson = gson.toJson(req.copy(request_id = requestId)),
            requestId   = requestId,
            creadoEn    = System.currentTimeMillis()
        ))
        dispararSync()
    }

    suspend fun enqueueFinalizarTurno(req: FinalizarTurnoRequest) {
        dao.insertar(OperacionPendiente(
            tipo        = "FINALIZAR_TURNO",
            payloadJson = gson.toJson(req),
            requestId   = UUID.randomUUID().toString(),
            creadoEn    = System.currentTimeMillis()
        ))
        dispararSync()
    }

    suspend fun enqueueIniciarParo(req: IniciarParoRequest) {
        dao.insertar(OperacionPendiente(
            tipo        = "INICIAR_PARO",
            payloadJson = gson.toJson(req),
            requestId   = UUID.randomUUID().toString(),
            creadoEn    = System.currentTimeMillis()
        ))
        dispararSync()
    }

    suspend fun enqueueFinalizarParo(req: FinalizarParoRequest) {
        dao.insertar(OperacionPendiente(
            tipo        = "FINALIZAR_PARO",
            payloadJson = gson.toJson(req),
            requestId   = UUID.randomUUID().toString(),
            creadoEn    = System.currentTimeMillis()
        ))
        dispararSync()
    }

    // Checklist de mantenimiento (ENTRADA/SALIDA). Operación crítica: se encola y
    // la envía el SyncWorker en orden FIFO. Idempotente por request_id, igual que
    // pallets/paros: respeta un request_id ya generado si viene.
    suspend fun enqueueMantenimiento(req: MantenimientoChecklistRequest) {
        val requestId = req.requestId.ifBlank { UUID.randomUUID().toString() }
        val reqConId = req.copy(requestId = requestId)
        dao.insertar(OperacionPendiente(
            tipo        = "MTTO_CHECKLIST",
            payloadJson = gson.toJson(reqConId),
            requestId   = requestId,
            creadoEn    = System.currentTimeMillis()
        ))
        dispararSync()
    }

    // CAMBIO 5: comentarios del turno y reportes de la app, vía la misma cola.
    suspend fun enqueueComentarioTurno(req: ComentarioTurnoRequest) {
        val requestId = req.request_id.ifBlank { UUID.randomUUID().toString() }
        dao.insertar(OperacionPendiente(
            tipo        = "COMENTARIO_TURNO",
            payloadJson = gson.toJson(req.copy(request_id = requestId)),
            requestId   = requestId,
            creadoEn    = System.currentTimeMillis()
        ))
        dispararSync()
    }

    suspend fun enqueueReporteApp(req: ReporteAppRequest) {
        val requestId = req.request_id.ifBlank { UUID.randomUUID().toString() }
        dao.insertar(OperacionPendiente(
            tipo        = "REPORTE_APP",
            payloadJson = gson.toJson(req.copy(request_id = requestId)),
            requestId   = requestId,
            creadoEn    = System.currentTimeMillis()
        ))
        dispararSync()
    }

    suspend fun desmarcarFatal(id: Long) {
        dao.marcarError(id, "", false)
    }

    suspend fun descartar(id: Long) = dao.eliminar(id)

    fun dispararSync() {
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("flush_cola", ExistingWorkPolicy.KEEP, work)
    }
}
