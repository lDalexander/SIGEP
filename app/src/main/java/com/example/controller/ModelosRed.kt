package com.example.controller

// --- PETICIONES (Lo que la tablet envía a Python) ---
data class HistorialPallet(val cantidad_pacas: Int, val hora: String)

data class IniciarTurnoRequest(
    val tipo: String,
    val maquina: String,
    val operador: String,
    val marca: String,
    val presentacion: String,
    val fragancia: String
)

data class RegistrarPalletRequest(
    var sesion_id: Int,
    val cantidad_pacas: Int
)

data class FinalizarTurnoRequest(
    val sesion_id: Int
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

data class MaquinaResponse(
    val id: Int,
    val nombre: String
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
data class LoginRequest(val nombre: String, val pin: String)

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
    val hora: String
)

data class PedidoAccionRequest(
    val pedido_id: Int,
    val insumista_id: Int
)

data class EstadoPedidosResponse(
    val pendientes: Int,
    val en_camino: Int,
    val entregados: Int
)

data class AdminLoginRequest(
    val username: String,
    val password: String
)

data class AdminLoginResponse(
    val status: String,
    val mensaje: String,
    val admin_id: Int,
    val nivel_acceso: String
)