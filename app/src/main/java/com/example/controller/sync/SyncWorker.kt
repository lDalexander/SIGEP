package com.example.controller.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.controller.*
import com.example.controller.data.local.AppDatabase
import com.example.controller.data.local.OperacionPendiente
import com.google.gson.Gson
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val dao = AppDatabase.getInstance(ctx).operacionDao()
    private val api = RetrofitClient.apiService
    private val gson = Gson()

    override suspend fun doWork(): Result {
        var pendingSesionId: Int? = null

        while (true) {
            val op = dao.proximaParaEnviar() ?: return Result.success()

            // Bloqueo de cascada: si la op depende de un INICIAR_TURNO que quedó fatal,
            // no la enviamos al servidor (recibiría 404 porque la sesión nunca existió).
            if (op.tipo != "INICIAR_TURNO" &&
                op.payloadJson.contains("\"sesion_id\":-99") &&
                pendingSesionId == null &&
                dao.existeFatalIniciarTurno()
            ) {
                dao.marcarError(
                    op.id,
                    "Bloqueado: el INICIAR_TURNO previo falló. Reintenta el turno antes de sincronizar esta operación.",
                    fatal = true
                )
                continue
            }

            try {
                enviar(op, pendingSesionId)?.let { nuevoId -> pendingSesionId = nuevoId }
                dao.eliminar(op.id)
            } catch (e: HttpException) {
                val code = e.code()
                val detalle = extraerDetalleError(e.response()) ?: e.message()
                if (code == 409) {
                    dao.eliminar(op.id)
                } else if (code in 400..499) {
                    dao.marcarError(op.id, "HTTP $code: $detalle", fatal = true)
                    // Si era FINALIZAR_TURNO con sesión local huérfana (-99 sin id real
                    // disponible), el server confirma que esa sesión no existe.
                    // Limpiamos el estado local para sacar al operario del limbo
                    // y que pueda iniciar un turno nuevo sin reinstalar la app.
                    if (op.tipo == "FINALIZAR_TURNO" &&
                        op.payloadJson.contains("\"sesion_id\":-99")
                    ) {
                        limpiarSesionLocal()
                    }
                } else {
                    val intentos = op.intentos + 1
                    if (intentos > 10) {
                        dao.marcarError(op.id, "HTTP $code: $detalle (>10 intentos)", fatal = true)
                    } else {
                        dao.marcarError(op.id, "HTTP $code: $detalle", fatal = false)
                        return Result.retry()
                    }
                }
            } catch (e: IOException) {
                val intentos = op.intentos + 1
                if (intentos > 10) {
                    dao.marcarError(op.id, "Sin red (>10 intentos)", fatal = true)
                } else {
                    dao.marcarError(op.id, "Sin red: ${e.message}", fatal = false)
                    return Result.retry()
                }
            }
        }
    }

    // Devuelve el sesion_id real cuando procesa INICIAR_TURNO, null en los demás casos.
    private suspend fun enviar(op: OperacionPendiente, pendingSesionId: Int?): Int? {
        return when (op.tipo) {
            "INICIAR_TURNO" -> {
                val req = gson.fromJson(op.payloadJson, IniciarTurnoRequest::class.java)
                val resp = api.iniciarTurno(req)
                if (!resp.isSuccessful) throw HttpException(resp)
                resp.body()!!.sesion_id.also { id ->
                    actualizarSesionEnDependientes(id)
                    persistirSesionReal(id)
                }
            }
            "REGISTRAR_PALLET" -> {
                var req = gson.fromJson(op.payloadJson, RegistrarPalletRequest::class.java)
                if (req.sesion_id == -99 && pendingSesionId != null) req = req.copy(sesion_id = pendingSesionId)
                val resp = api.registrarPallet(req)
                if (!resp.isSuccessful) throw HttpException(resp)
                // duplicado:true = idempotencia o anti-ráfaga → éxito igual (se elimina en doWork)
                null
            }
            "FINALIZAR_TURNO" -> {
                var req = gson.fromJson(op.payloadJson, FinalizarTurnoRequest::class.java)
                if (req.sesion_id == -99 && pendingSesionId != null) req = req.copy(sesion_id = pendingSesionId)
                val resp = api.finalizarTurno(req)
                if (!resp.isSuccessful) throw HttpException(resp)
                null
            }
            "INICIAR_PARO" -> {
                var req = gson.fromJson(op.payloadJson, IniciarParoRequest::class.java)
                if (req.sesion_id == -99 && pendingSesionId != null) req = req.copy(sesion_id = pendingSesionId)
                val resp = api.iniciarParo(req)
                if (!resp.isSuccessful) throw HttpException(resp)
                null
            }
            "FINALIZAR_PARO" -> {
                var req = gson.fromJson(op.payloadJson, FinalizarParoRequest::class.java)
                if (req.sesion_id == -99 && pendingSesionId != null) req = req.copy(sesion_id = pendingSesionId)
                val resp = api.finalizarParo(req)
                if (!resp.isSuccessful) throw HttpException(resp)
                null
            }
            "MTTO_CHECKLIST" -> {
                // Checklist de mantenimiento (ENTRADA/SALIDA): no depende de sesion_id.
                // 409 (ya existe ese momento para el turno) = éxito vía idempotencia
                // y se elimina de la cola en doWork.
                val req = gson.fromJson(op.payloadJson, MantenimientoChecklistRequest::class.java)
                val resp = api.responderMantenimiento(req)
                if (!resp.isSuccessful) throw HttpException(resp)
                null
            }
            else -> {
                Log.w("SyncWorker", "Tipo desconocido: ${op.tipo}")
                null
            }
        }
    }

    // Tras crear el turno en el servidor, reemplazamos sesion_id=-99 por el id real
    // en TODAS las ops dependientes (pacas, finalizar, paros) para que sobrevivan
    // a un crash del worker antes de que las procese individualmente.
    private suspend fun actualizarSesionEnDependientes(nuevoId: Int) {
        dao.obtenerDependientesConSesionLocal().forEach { op ->
            val nuevoJson = op.payloadJson.replace("\"sesion_id\":-99", "\"sesion_id\":$nuevoId")
            if (nuevoJson != op.payloadJson) {
                dao.actualizar(op.copy(payloadJson = nuevoJson))
            }
        }
    }

    // Persiste el id real para que MainActivity lo lea (vía OnSharedPreferenceChangeListener)
    // y reemplace el -99 que estaba mostrando. Sin esto, el usuario sigue creyendo que
    // su sesión es -99 y al finalizar el server devuelve 404 "Sesion no encontrada".
    private fun persistirSesionReal(nuevoId: Int) {
        applicationContext
            .getSharedPreferences("DetcuadorPrefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("sesion_activa", nuevoId)
            .apply()
    }

    private fun limpiarSesionLocal() {
        applicationContext
            .getSharedPreferences("DetcuadorPrefs", Context.MODE_PRIVATE)
            .edit()
            .remove("sesion_activa")
            .remove("hora_inicio_ms")
            .apply()
    }

    // Lee el body de la respuesta de error para que la UI muestre la causa real
    // (ej. "campo 'operador' es obligatorio") en lugar de solo "Bad Request".
    private fun extraerDetalleError(response: Response<*>?): String? {
        val body = response?.errorBody() ?: return null
        return try {
            body.string().take(400).replace("\n", " ").trim().ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }
}
