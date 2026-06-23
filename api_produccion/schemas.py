"""
Este módulo define los esquemas (Schemas) utilizando Pydantic.
Se encargan de la validación y serialización de los datos de entrada (Requests)
y salida (Responses) de la API.
"""
from pydantic import BaseModel
from typing import Optional, List

class IniciarTurno(BaseModel):
    """Esquema para iniciar un nuevo turno o sesión de trabajo."""
    request_id: str
    tipo: str
    maquina: str
    operador: str
    marca: str
    presentacion: str
    fragancia: str

class RegistrarPalletRequest(BaseModel):
    """Esquema para registrar la producción de un pallet.

    `request_id` es opcional para compatibilidad con clientes viejos. Cuando el
    cliente lo envía (UUID generado al encolar el pallet en su cache local),
    el backend garantiza idempotencia: un mismo request_id nunca se inserta
    dos veces aunque la tablet reintente.
    """
    sesion_id: int
    cantidad_pacas: int
    request_id: Optional[str] = None

class FinalizarTurno(BaseModel):
    """Esquema para finalizar un turno activo."""
    sesion_id: int

class IniciarParo(BaseModel):
    """Esquema para registrar el inicio de un paro o tiempo muerto."""
    sesion_id: int
    motivo: str

class FinalizarParo(BaseModel):
    """Esquema para registrar el fin de un paro activo."""
    sesion_id: int

class NuevoPedidoRequest(BaseModel):
    """Esquema para que los operadores soliciten insumos a bodega."""
    sesion_id: int
    detalle_pedido: str
    cantidad: int

class PedidoAccionRequest(BaseModel):
    """Esquema para las acciones de los insumistas (aceptar/entregar pedido)."""
    pedido_id: int
    insumista_id: int
    cantidad_entregada: int | None = None  # solo se usa en /entregar

class LoginRequest(BaseModel):
    """Esquema para la autenticación rápida de usuarios.

    `device_id` lo envía la app (ID de la tablet) para asociar el FCM al usuario;
    es opcional para no romper clientes que no lo manden.
    """
    nombre: str
    pin: str
    device_id: Optional[str] = None


class LogoutRequest(BaseModel):
    """Esquema para el cierre de sesión de un usuario en una tablet."""
    usuario_id: int
    device_id: str

class AdminLoginRequest(BaseModel):
    """Esquema para el login de administradores."""
    nombre: str
    pin: str

class AdminLoginResponse(BaseModel):
    """Respuesta al login de administradores."""
    status: str
    mensaje: str
    admin_id: int
    nivel_acceso: str

class ConfirmarRecepcionRequest(BaseModel):
    """Esquema para que el operador confirme la recepción de un insumo."""
    pedido_id: int
    sesion_id: int
    cantidad_recibida: int | None = None

class RegistrarTokenRequest(BaseModel):
    """Esquema para registrar o actualizar un token FCM de un dispositivo Android."""
    token: str
    plataforma: str = "android"
    usuario_id: Optional[int] = None

class RegistrarTokenResponse(BaseModel):
    """Respuesta al registrar un token FCM."""
    mensaje: str
    id: int


class HeartbeatTabletRequest(BaseModel):
    """Esquema para el heartbeat que envía cada tablet al servidor."""
    device_id: str
    nombre: Optional[str] = None
    maquina: Optional[str] = None
    pendientes: int = 0
    en_linea: bool = True


class HeartbeatTabletResponse(BaseModel):
    mensaje: str
    sync_solicitada: bool = False


class EstadoTabletResponse(BaseModel):
    device_id: str
    nombre: Optional[str] = None
    maquina: Optional[str] = None
    pendientes: int
    ultimo_heartbeat: Optional[str] = None
    ultima_sincronizacion: Optional[str] = None
    en_linea: bool
    segundos_desde_heartbeat: Optional[int] = None


class SincronizarTabletResponse(BaseModel):
    device_id: str
    enviada: bool
    motivo: Optional[str] = None


class SincronizarTodasResponse(BaseModel):
    total: int
    enviadas: int
    detalle: list[SincronizarTabletResponse]


# ============================================================================
# CHECKLIST DE MANTENIMIENTO (ENTRADA / SALIDA)
# ============================================================================
# Contrato CONGELADO contra la app Android. Los nombres de campo son literales
# (vienen de Gson). No los cambies.

class MantenimientoItemEstado(BaseModel):
    """Un ítem del checklist: etiqueta fija + si está marcado."""
    etiqueta: str
    marcado: bool


class MantenimientoChecklistRequest(BaseModel):
    """Body EXACTO que envía la tablet al endpoint de mantenimiento.

    `fecha`/`hora` son del reloj de la tablet. El servidor IGNORA cualquier
    cálculo de turno del cliente y lo recalcula con su propio reloj.
    """
    request_id: str
    maquina: str
    operador: str
    momento: str                              # "ENTRADA" | "SALIDA"
    fecha: str                                # yyyy-MM-dd (reloj tablet)
    hora: str                                 # HH:mm (reloj tablet)
    supervisor: str = ""                      # obligatorio en la app; default "" por robustez
    comentarios: str = ""                     # puede venir ""
    items: List[MantenimientoItemEstado] = []


class MantenimientoChecklistResponse(BaseModel):
    """Respuesta informativa. La app solo mira el código HTTP, no este body."""
    id: int
    mensaje: str