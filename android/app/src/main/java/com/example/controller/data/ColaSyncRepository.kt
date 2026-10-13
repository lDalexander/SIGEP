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

    /**
     * Pedido de insumo a bodega (v1.7.0).
     *
     * Hasta ahora esta era la ÚNICA operación del operario que iba a fuego por Retrofit
     * y exigía red: el botón se bloqueaba sin conexión con un «Debe tener red para
     * solicitar insumos». Un operario sin cobertura se quedaba sin poder pedir fundas.
     *
     * Con `sesion_id = -99` la cascada del SyncWorker lo reescribe igual que a las
     * pacas: no hace falta nada especial aquí porque la consulta que busca dependientes
     * mira el payload, no el tipo.
     */
    suspend fun enqueueSolicitarInsumo(req: SolicitarInsumoRequest) {
        val requestId = req.request_id.ifBlank { UUID.randomUUID().toString() }
        dao.insertar(OperacionPendiente(
            tipo        = "SOLICITAR_INSUMO",
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

    // Velocidad de máquina (fundas/min). Idempotente por request_id: sin él una tablet
    // sin red reintentaría y la misma lectura entraría varias veces, sesgando el
    // promedio del turno.
    suspend fun enqueueVelocidad(req: VelocidadRequest) {
        val requestId = req.request_id.ifBlank { UUID.randomUUID().toString() }
        dao.insertar(OperacionPendiente(
            tipo        = "REGISTRAR_VELOCIDAD",
            payloadJson = gson.toJson(req.copy(request_id = requestId)),
            requestId   = requestId,
            creadoEn    = System.currentTimeMillis()
        ))
        dispararSync()
    }

    // Foto de evidencia de un paro. Va en su PROPIA operación, separada del paro: si no
    // hay red el paro se registra igual y la foto sube después, nunca al revés (un paro
    // que espera a que suba una foto es un paro que se puede perder).
    // paroId = -99 cuando el paro se encoló sin red: el SyncWorker lo reescribe con el
    // id real al sincronizar el INICIAR_PARO.
    suspend fun enqueueFotoParo(paroId: Int, rutaArchivo: String) {
        dao.insertar(OperacionPendiente(
            tipo        = "FOTO_PARO",
            payloadJson = gson.toJson(FotoParoPendiente(paro_id = paroId, ruta = rutaArchivo)),
            requestId   = UUID.randomUUID().toString(),
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
