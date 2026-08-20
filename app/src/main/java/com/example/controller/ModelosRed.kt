package com.example.controller

import com.google.gson.annotations.SerializedName

// --- PETICIONES (Lo que la tablet envía a Python) ---
data class HistorialPallet(val cantidad_pacas: Int, val hora: String)

data class IniciarTurnoRequest(
    val tipo: String,
    val maquina: String,
    val operador: String,
    val marca: String,
    val presentacion: String,
    val fragancia: String,
    val request_id: String
)

data class RegistrarPalletRequest(
    var sesion_id: Int,
    val cantidad_pacas: Int,
    val request_id: String = ""
)

data class RegistrarPalletResponse(
    val mensaje: String,
    @SerializedName("pallet_id") val palletId: Int? = null,
    val duplicado: Boolean = false,
    val razon: String? = null,
)

data class FinalizarTurnoRequest(
    val sesion_id: Int
)

// --- CAMBIO 5: comentarios del turno y reportes de problemas con la app ---
// Offline-first: se encolan y el SyncWorker los envía. Idempotentes por request_id.
data class ComentarioTurnoRequest(
    val session_id: Int?,
    val maquina: String?,
    val operador: String?,
    val texto: String,
    val request_id: String = ""
)

data class ReporteAppRequest(
    val session_id: Int?,
    val maquina: String?,
    val operador: String?,
    val texto: String,
    val request_id: String = ""
)

// --- RESPUESTAS (Lo que Python le devuelve a la tablet) ---
data class TurnoResponse(
    val sesion_id: Int,
    val mensaje: String
)

data class OperadorResponse(
    val id: Int,
    val nombre: String
)

data class MarcaResponse(
    val nombre: String,
    val presentaciones: List<String>
)

// El campo marcas es nulo mientras el backend no devuelva la jerarquía completa.
// Ver TAREA 1: migración de datos hardcodeados a api/maquinas.
data class MaquinaResponse(
    val id: Int,
    val nombre: String,
    // Tipo de línea: "SOLIDO" | "LIQUIDO". Nullable por compatibilidad con caché viejo
    // o backend que aún no lo envíe → se asume SOLIDO al filtrar (ver CAMBIO 2).
    val tipo: String? = null,
    val marcas: List<MarcaResponse>? = null
)

data class RespuestaGenerica(
    val mensaje: String
)

// --- MODELOS PARA PARO DE MÁQUINA ---
data class IniciarParoRequest(
    val sesion_id: Int, val motivo: String
)

data class FinalizarParoRequest(
    val sesion_id: Int
)

// paro_id es el id del paro recién abierto: lo persistimos para poder adjuntarle la
// foto después (POST /api/paro/{paro_id}/foto) y para reconocerlo al consultar estado.
data class IniciarParoResponse(
    val paro_id: Int? = null,
    val mensaje: String? = null
)

// ya_cerrado = true cuando el paro ya estaba cerrado (lo cerró un supervisor desde la
// web). El server responde 200, NO 404: es un éxito y la tablet debe desbloquearse.
// El campo no viaja en el camino normal, de ahí el default en false.
data class FinalizarParoResponse(
    val paro_id: Int? = null,
    val mensaje: String? = null,
    val duracion_segundos: Double? = null,
    val ya_cerrado: Boolean = false
)

data class ParoDetalle(
    val paro_id: Int,
    val sesion_id: Int? = null,
    val motivo: String? = null,
    val inicio_paro: String? = null,
    val fin_paro: String? = null,
    val duracion_segundos: Double? = null,
    val abierto: Boolean = false,
    val tiene_foto: Boolean = false
)

// GET /api/paro/estado?sesion_id= — solo lectura, es la FUENTE DE VERDAD del botón
// de paro. paro_abierto = null NO es error: significa que la máquina no está en paro.
// Sin esto, un paro cerrado desde la web dejaba el flag local encendido para siempre
// (bloqueo de 1 h 34 min en la Máquina 7 el 2026-08-19).
data class EstadoParoResponse(
    val sesion_id: Int? = null,
    val turno_abierto: Boolean = false,
    val en_paro: Boolean = false,
    val paro_abierto: ParoDetalle? = null,
    val ultimo_paro: ParoDetalle? = null
)

data class FotoParoResponse(
    val paro_id: Int? = null,
    val foto_url: String? = null,
    val mensaje: String? = null
)

// Foto de paro pendiente de subir. Es el payload que viaja en la cola offline (no un
// DTO de red): la imagen ya comprimida vive en disco y solo guardamos su ruta.
// paro_id = -99 cuando el paro se abrió sin red y aún no tiene id real; el SyncWorker
// lo reescribe al sincronizar el INICIAR_PARO, igual que hace con sesion_id.
data class FotoParoPendiente(
    val paro_id: Int,
    val ruta: String
)

// --- VELOCIDAD DE MÁQUINA (fundas/min) ---
// Lectura puntual, no un ajuste: se pueden registrar varias por turno y todas quedan
// con su hora. Idempotente por request_id (sin él, una tablet sin red reintenta y la
// misma lectura sesga el promedio del turno).
data class VelocidadRequest(
    var sesion_id: Int,
    val velocidad: Double,
    val request_id: String = ""
)

data class VelocidadResponse(
    val velocidad_id: Int? = null,
    val mensaje: String? = null,
    val velocidad: Double? = null,
    val duplicado: Boolean = false
)

// --- MÓDULO DE INSUMOS (OPERADOR) ---
data class InsumoResponse(
    val id: Int,
    val nombre: String,
    val categoria: String,
    val unidad: String
)

data class SolicitarInsumoRequest(
    val sesion_id: Int,
    val detalle_pedido: String,
    val cantidad: Int
)

// --- MÓDULO DE LOGIN (INSUMISTAS) ---
// device_id permite al backend bloquear el segundo login del mismo usuario
// desde otra tablet, evitando que un insumista esté operando en dos lugares
// al mismo tiempo y duplique entregas/aceptaciones.
data class LoginRequest(val nombre: String, val pin: String, val device_id: String)

data class LogoutRequest(val usuario_id: Int, val device_id: String)

data class ListaUsuariosResponse(val id: Int, val nombre: String, val rol: String)

data class UsuarioResponse(
    val id: Int,
    val nombre: String,
    val rol: String
)

// ==========================================
// NUEVO: FASE 4 - RADAR UBER (INSUMISTAS)
// ==========================================
data class PedidoPendienteResponse(
    val id: Int,
    val maquina: String,
    val operador: String,
    val detalle: String,
    val cantidad: Int,
    val hora: String,
    val estado: String = "PENDIENTE",
    val cantidad_entregada: Int? = null,
    val cantidad_recibida: Int? = null
)

data class PedidoAccionRequest(
    val pedido_id: Int,
    val insumista_id: Int,
    val cantidad_entregada: Int? = null   // solo se usa al entregar
)

data class EstadoPedidosResponse(
    val pendientes: Int,
    val en_camino: Int,
    val entregados: Int,
    val pendiente_confirmacion: Int = 0
)

data class DetallePedidoOperador(
    val id: Int,
    val detalle: String,
    val cantidad: Int,
    val hora_solicitud: String,
    val hora_aceptacion: String?,
    val insumista: String?,
    val hora_entrega: String?,
    val estado: String
)

data class ConfirmarRecepcionRequest(
    val pedido_id: Int,
    val sesion_id: Int,
    val cantidad_recibida: Int? = null
)

data class AdminLoginRequest(
    val nombre: String,
    val pin: String
)

data class AdminLoginResponse(
    val status: String,
    val mensaje: String,
    val admin_id: Int,
    val nivel_acceso: String
)


// ==========================================
// NUEVO: FASE 5 - TORRE DE CONTROL (SUPERVISORES)
// ==========================================
data class EstadoMaquinaRealTime(
    val maquina: String,
    val operador: String,
    val producto: String,
    val tiempo_inicio: String,
    val pacas_totales: Int,
    val estado: String // "ACTIVA", "PARO", "OFFLINE"
)

data class HistorialPedidoSupervisor(
    val id: Int,
    val maquina: String,
    val insumo: String,
    val cantidad: Int,
    val solicitado_a: String,
    val aceptado_por: String?,
    val entregado_en: String?,
    val tiempo_respuesta: String?,
    val status: String // "NUEVO", "ACEPTADO", "ENTREGADO"
)

// ==========================================
// NUEVO: FASE 6 - UPDATER PRIVADO
// ==========================================
data class VersionResponse(
    val version_code: Int,
    val version_name: String,
    val url_descarga: String
)

// usuario_id es nulo cuando el dispositivo aún no tiene sesión activa.
// Con él el backend puede enviar FCM a un usuario específico en lugar de hacer broadcast.
data class SolicitudTokenFcm(
    @SerializedName("token")       val token: String,
    @SerializedName("plataforma")  val plataforma: String = "android",
    @SerializedName("usuario_id")  val usuarioId: Int? = null
)

// ==========================================
// ENTREGA PROACTIVA (INSUMISTA → MÁQUINA)
// ==========================================
data class EntregaProactivaResponse(
    val id: Int,
    val mensaje: String,
    val foto_url: String?
)

data class EntregaProactivaHistorial(
    val id: Int,
    val tipo_producto: String,
    val insumo: String,
    val cantidad: Int,
    val maquina: String,
    val observaciones: String?,
    val foto_path: String?,
    val fecha_hora: String
)

// El mismo modelo sirve para registro (HTTP 201) y actualización (HTTP 200).
data class RespuestaTokenFcm(
    @SerializedName("mensaje") val mensaje: String,
    @SerializedName("id")      val id: Int
)

// ==========================================
// HEARTBEAT DE TABLET
// ==========================================
data class HeartbeatRequest(
    @SerializedName("device_id")  val deviceId: String,
    @SerializedName("nombre")     val nombre: String?,
    @SerializedName("maquina")    val maquina: String?,
    @SerializedName("pendientes") val pendientes: Int,
    @SerializedName("en_linea")   val enLinea: Boolean,
)

data class HeartbeatResponse(
    @SerializedName("mensaje")         val mensaje: String,
    @SerializedName("sync_solicitada") val syncSolicitada: Boolean,
    // Mensajes admin no leídos para la máquina de esta tablet (CAMBIO 4).
    @SerializedName("mensajes")        val mensajes: List<MensajeAdmin> = emptyList(),
)

// Mensaje enviado por el admin web a la tablet (CAMBIO 4).
data class MensajeAdmin(
    @SerializedName("id")        val id: Int,
    @SerializedName("texto")     val texto: String,
    @SerializedName("origen")    val origen: String? = null,
    @SerializedName("creado_en") val creadoEn: String? = null,
)

// ==========================================
// CHECKLIST DE MANTENIMIENTO Y LIMPIEZA (ENTRADA / SALIDA)
// Ítems fijos definidos en el cliente (ver MantenimientoItems).
// ==========================================
object MantenimientoMomento {
    const val ENTRADA = "ENTRADA"
    const val SALIDA  = "SALIDA"
}

data class MantenimientoItemEstado(
    val etiqueta: String,
    val marcado: Boolean
)

data class MantenimientoChecklistRequest(
    @SerializedName("request_id") val requestId: String,
    val maquina: String,
    val operador: String,
    val momento: String,            // ENTRADA | SALIDA
    val fecha: String,              // yyyy-MM-dd (reloj de la tablet)
    val hora: String,               // HH:mm
    val supervisor: String,         // supervisor de turno (texto)
    val comentarios: String,        // comentarios libres (puede ir vacío)
    val items: List<MantenimientoItemEstado>
)

data class MantenimientoChecklistResponse(
    val id: Int,
    val mensaje: String
)
