"""
Router de administración (zona ADMIN web).

Fase 2: gestión de operarios (alta / baja / reactivación / eliminación).
Fase 3: edición de los datos que entran desde Android — sesiones de producción,
        pacas por registro, y checklists de mantenimiento (ítems + comentarios).

SEGURIDAD: a diferencia del resto del backend (abierto + gate por UI), esta zona
exige un TOKEN de sesión admin. El login (POST /api/admin/auth) valida contra la
tabla `administradores` y emite un token; todos los demás endpoints exigen la
cabecera `X-Admin-Token`. El store es en memoria (se vacía si se reinicia el
servicio; basta con volver a iniciar sesión).

La app Android descarga la lista de operarios ACTIVOS cuando tiene conexión y la
cachea, así que altas/bajas aquí se reflejan en las tablets al reconectar.
"""
import os
import secrets
from datetime import datetime, timedelta

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Header, Query
from pydantic import BaseModel
from sqlalchemy import func
from sqlalchemy.orm import Session

from database import get_db, logger
from sqlalchemy import text
from models import (
    OperadorDB,
    AdministradorDB,
    SesionTrabajoDB,
    PalletDB,
    MantenimientoChecklistDB,
    MantenimientoChecklistItemDB,
    MaquinaDB,
    MaquinaProductoDB,
    MaquinaMarcaFraganciaDB,
    MarcaDB,
    PresentacionDB,
    FraganciaDB,
    MensajeTabletDB,
    EstadoTabletDB,
    PedidoBodegaDB,
    EntregaProactivaDB,
    ParoMaquinaDB,
    ComentarioTurnoDB,
    ReporteAppDB,
)
from routers.tablets import enviar_payload_ws, tablet_manager
# Los estados de pedido se importan de `operaciones` en vez de repetirlos aquí: el
# cierre manual de un turno tiene que dejar los pedidos exactamente igual que el
# cierre desde la tablet, y si allí cambian, aquí cambian solos.
from routers.operaciones import ESTADOS_PEDIDO_ACTIVO, ESTADO_PEDIDO_CIERRE_TURNO
from routers.dashboard import _desglosar_motivo, _dur_segundos, _estado_paro
from services import config_correo, email_service, reporte_semanal, seguridad
from ws_manager import manager

router = APIRouter(prefix="/api/admin", tags=["Administración"])

# Token de sesión admin -> datos del admin. En memoria (1 worker gunicorn).
# Cada entrada: {"username", "nivel", "ultimo_uso"}.
_TOKENS: dict = {}

# ----------------------------------------------------------------------------
# Caducidad por inactividad (2026-08-07)
# ----------------------------------------------------------------------------
# Hasta ahora un token no caducaba NUNCA: solo lo mataba el «Salir» o un reinicio
# del servicio. Un navegador olvidado abierto en /admin seguía pudiendo cerrar
# turnos o borrar sesiones días después.
#
# Se mide INACTIVIDAD, no antigüedad: cada petición autenticada renueva el reloj,
# así que una sesión de trabajo larga no se corta a media edición. No hay tope
# absoluto a propósito (decisión del responsable, 2026-08-07).
#
# El reloj es el del servidor (`datetime.now()`, TZ America/Guayaquil como el
# resto del módulo): un equipo con la hora mal puesta no puede alargar su sesión.
#
# `ADMIN_INACTIVIDAD_MIN` permite ajustarlo sin tocar código (y probarlo en la
# instancia paralela del 8001 con un minuto en vez de esperar quince). Un valor
# ilegible o <= 0 cae al defecto: quedarse sin caducidad por una errata en el .env
# sería justo el agujero que esto viene a cerrar.
def _minutos_inactividad() -> int:
    try:
        valor = int(os.getenv("ADMIN_INACTIVIDAD_MIN", "15"))
        return valor if valor > 0 else 15
    except (TypeError, ValueError):
        return 15


INACTIVIDAD_MAX = timedelta(minutes=_minutos_inactividad())

# Mensaje del 401 cuando el token existía pero llevaba demasiado tiempo parado. La
# web lo muestra tal cual en el login, así que distingue «te has ido» de «se
# reinició el servicio» sin que el frontend tenga que adivinar el motivo.
MOTIVO_INACTIVIDAD = "Sesión cerrada por inactividad. Vuelve a iniciar sesión."


def _purgar_tokens(ahora: datetime) -> None:
    """Suelta los tokens ya caducados.

    `require_admin` ya rechaza uno caducado aunque siga en el dict, así que esto no
    es lo que da la seguridad: solo evita que el store crezca sin fin con sesiones
    que nadie va a volver a usar. Se llama en el login, que es poco frecuente.
    """
    caducados = [t for t, d in _TOKENS.items() if ahora - d["ultimo_uso"] > INACTIVIDAD_MAX]
    for t in caducados:
        datos = _TOKENS.pop(t, None)
        if datos:
            logger.info(f"Sesión admin de {datos['username']} caducada por inactividad")


# ----------------------------------------------------------------------------
# Autenticación
# ----------------------------------------------------------------------------
class AuthIn(BaseModel):
    nombre: str
    pin: str


def require_admin(x_admin_token: str = Header(default=None)):
    """Dependencia: exige un token admin válido y no caducado en X-Admin-Token.

    Solo autentica. Para exigir además un nivel concreto, ver `require_nivel`.

    Renueva el reloj de inactividad en cada petición: es el único punto por el que
    pasan TODOS los endpoints de la zona, así que aquí «usar la sesión» y «renovarla»
    son lo mismo. Consecuencia a tener presente: cualquier petición cuenta como uso,
    incluido el refresco automático de la pestaña Mensajes (cada 15 s) — mientras esa
    pestaña esté abierta el token no caduca por este camino, y quien lo cierra es el
    temporizador de inactividad del navegador, que sí sabe si hay alguien delante.
    """
    ctx = _TOKENS.get(x_admin_token) if x_admin_token else None
    if ctx is None:
        raise HTTPException(status_code=401, detail="Sesión admin requerida o expirada")

    ahora = datetime.now()
    if ahora - ctx["ultimo_uso"] > INACTIVIDAD_MAX:
        _TOKENS.pop(x_admin_token, None)
        logger.info(f"Sesión admin de {ctx['username']} rechazada: inactividad")
        raise HTTPException(status_code=401, detail=MOTIVO_INACTIVIDAD)

    ctx["ultimo_uso"] = ahora
    return ctx


# ----------------------------------------------------------------------------
# Niveles de acceso (2026-08-06)
# ----------------------------------------------------------------------------
# `administradores.nivel_acceso` existía desde siempre pero NO controlaba nada: bastaba
# un token válido para poder hacer cualquier cosa. Aquí se le da efecto.
#
# El control vive en el BACKEND a propósito. La web oculta lo que no corresponde, pero
# esconder un botón no es un permiso: con el token en la mano, cualquiera puede llamar
# al endpoint a mano.
#
# Los tres niveles operativos son los que ya existen en la tabla (SUPERADMIN,
# ADMINPLANTA, ADMINBODEGA, más ADMIN por compatibilidad); CONSULTA es nuevo y es de
# solo lectura. Ojo: `GET /api/admin/supervisores` (que alimenta el selector de
# supervisor de las tablets) filtra por los niveles operativos, así que un usuario
# CONSULTA no aparece ahí — es lo que se quiere.

NIVEL_SUPERADMIN = "SUPERADMIN"
NIVEL_CONSULTA = "CONSULTA"
NIVELES_OPERATIVOS = {NIVEL_SUPERADMIN, "ADMIN", "ADMINPLANTA", "ADMINBODEGA"}
NIVELES_VALIDOS = NIVELES_OPERATIVOS | {NIVEL_CONSULTA}


def require_nivel(*permitidos):
    """Fabrica una dependencia que exige uno de esos niveles. 403 si no lo tiene."""
    def dependencia(ctx=Depends(require_admin)):
        if ctx.get("nivel") not in permitidos:
            raise HTTPException(
                status_code=403,
                detail="Tu nivel de acceso no permite esta acción",
            )
        return ctx
    return dependencia


# ----------------------------------------------------------------------------
# Áreas (2026-08-07)
# ----------------------------------------------------------------------------
# Antes había un solo nivel «operativo»: quien podía escribir, escribía en todo. Ahora
# cada nivel trabaja en SU área, y el reparto lo decidió el responsable:
#
#   SUPERADMIN   todo, incluido lo que administra el propio sistema
#   ADMINPLANTA  planta: operarios, producción, paros, checklists, jerarquía, mensajes
#   ADMIN        igual que ADMINPLANTA (nivel heredado)
#   ADMINBODEGA  solo insumos: corregir pedidos y entregas
#   CONSULTA     lee lo de planta, no escribe nada
#
# Esto vive en el BACKEND y no solo en las pestañas de la web, por la misma razón de
# siempre: con el token en la mano, cualquiera llama al endpoint a mano.
#
# Reportes de la app, comentarios, tablets, usuarios y correo quedan **solo para
# SUPERADMIN**: no son operación diaria, son administración del sistema.

NIVELES_PLANTA = {NIVEL_SUPERADMIN, "ADMINPLANTA", "ADMIN"}
NIVELES_BODEGA = {NIVEL_SUPERADMIN, "ADMINBODEGA"}

# Ya no existe un `require_operativo` global: cada área tiene el suyo. Si vuelve a hacer
# falta uno transversal, es señal de que el endpoint no está en el área correcta.
# Escribir en planta (corregir sesiones, operarios, jerarquía, paros, mensajes).
require_planta = require_nivel(*NIVELES_PLANTA)
# Leer lo de planta: lo mismo más CONSULTA, que ve pero no toca.
require_planta_lectura = require_nivel(*NIVELES_PLANTA, NIVEL_CONSULTA)
# Corregir insumos (pedidos y entregas de bodega).
require_bodega = require_nivel(*NIVELES_BODEGA)
# Acciones irreversibles o de administración del propio sistema.
require_superadmin = require_nivel(NIVEL_SUPERADMIN)


@router.post("/auth")
def admin_auth(datos: AuthIn, db: Session = Depends(get_db)):
    """Valida credenciales admin y emite un token de sesión.

    La contraseña se comprueba con `services.seguridad`, que acepta tanto el hash
    PBKDF2 como el texto plano heredado; en el segundo caso la reescribe hasheada
    aquí mismo, así que la tabla se migra sola conforme cada admin entra.
    """
    admin = (
        db.query(AdministradorDB)
        .filter(AdministradorDB.username == datos.nombre, AdministradorDB.activo == True)
        .first()
    )
    correcta, necesita_rehash = (False, False)
    if admin:
        correcta, necesita_rehash = seguridad.verificar(datos.pin, admin.password)
    if not admin or not correcta:
        raise HTTPException(status_code=401, detail="Usuario o contraseña incorrectos")
    if necesita_rehash:
        admin.password = seguridad.hashear(datos.pin)
        db.commit()
        logger.info(f"Contraseña de {admin.username} migrada a hash en el login")
    ahora = datetime.now()
    _purgar_tokens(ahora)
    token = secrets.token_urlsafe(32)
    _TOKENS[token] = {
        "username": admin.username,
        "nivel": admin.nivel_acceso,
        "ultimo_uso": ahora,
    }
    logger.info(f"Admin login: {admin.username} ({admin.nivel_acceso})")
    # `inactividad_segundos` es una clave NUEVA en la respuesta: la web la usa para
    # cerrar sola antes de que el token muera, en vez de repetir aquí y allí el
    # número de minutos. Solo la consume esta web (la app Android usa /admin/login).
    return {
        "token": token,
        "username": admin.username,
        "nivel_acceso": admin.nivel_acceso,
        "inactividad_segundos": int(INACTIVIDAD_MAX.total_seconds()),
    }


@router.post("/logout")
def admin_logout(x_admin_token: str = Header(default=None), ctx=Depends(require_admin)):
    _TOKENS.pop(x_admin_token, None)
    return {"ok": True}


# ----------------------------------------------------------------------------
# Operarios (Fase 2)
# ----------------------------------------------------------------------------
class OperadorIn(BaseModel):
    nombre: str
    tipo: str | None = None  # SOLIDO (default) | LIQUIDO


class OperadorUpdate(BaseModel):
    nombre: str | None = None
    tipo: str | None = None
    activo: bool | None = None


@router.get("/operadores")
def listar_operadores(tipo: str = Query(None), db: Session = Depends(get_db), ctx=Depends(require_planta_lectura)):
    """Lista los operarios. Con `tipo` se limita a los de esa línea."""
    q = db.query(OperadorDB)
    if tipo:
        q = q.filter(OperadorDB.tipo == _norm_tipo(tipo))
    ops = q.order_by(OperadorDB.activo.desc(), OperadorDB.nombre.asc()).all()
    return [{"id": o.id, "nombre": o.nombre, "tipo": o.tipo, "activo": bool(o.activo)} for o in ops]


@router.post("/operadores")
def crear_operador(datos: OperadorIn, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    nombre = (datos.nombre or "").strip()
    if not nombre:
        raise HTTPException(status_code=400, detail="El nombre es obligatorio")
    tipo = _norm_tipo(datos.tipo)
    existente = db.query(OperadorDB).filter(OperadorDB.nombre == nombre).first()
    if existente:
        if existente.activo:
            raise HTTPException(status_code=409, detail="Ese operario ya existe y está activo")
        existente.activo = True
        existente.tipo = tipo
        db.commit()
        logger.info(f"Operario reactivado: {nombre} [{tipo}] (id {existente.id})")
        return {"id": existente.id, "nombre": existente.nombre, "tipo": tipo,
                "activo": True, "reactivado": True}
    op = OperadorDB(nombre=nombre, tipo=tipo, activo=True)
    db.add(op)
    db.commit()
    db.refresh(op)
    logger.info(f"Operario creado: {nombre} [{tipo}] (id {op.id})")
    return {"id": op.id, "nombre": op.nombre, "tipo": op.tipo, "activo": True}


@router.put("/operadores/{operador_id}")
def actualizar_operador(operador_id: int, datos: OperadorUpdate, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    op = db.query(OperadorDB).filter(OperadorDB.id == operador_id).first()
    if not op:
        raise HTTPException(status_code=404, detail="Operario no encontrado")
    if datos.nombre is not None:
        nuevo = datos.nombre.strip()
        if not nuevo:
            raise HTTPException(status_code=400, detail="El nombre no puede quedar vacío")
        choque = db.query(OperadorDB).filter(OperadorDB.nombre == nuevo, OperadorDB.id != operador_id).first()
        if choque:
            raise HTTPException(status_code=409, detail="Ya existe otro operario con ese nombre")
        op.nombre = nuevo
    if datos.tipo is not None:
        op.tipo = _norm_tipo(datos.tipo)
    if datos.activo is not None:
        op.activo = datos.activo
    db.commit()
    logger.info(f"Operario actualizado: id {op.id} activo={op.activo} tipo={op.tipo} nombre={op.nombre}")
    return {"id": op.id, "nombre": op.nombre, "tipo": op.tipo, "activo": bool(op.activo)}


@router.delete("/operadores/{operador_id}")
def eliminar_operador(operador_id: int, db: Session = Depends(get_db), ctx=Depends(require_superadmin)):
    op = db.query(OperadorDB).filter(OperadorDB.id == operador_id).first()
    if not op:
        raise HTTPException(status_code=404, detail="Operario no encontrado")
    nombre = op.nombre
    db.delete(op)
    db.commit()
    logger.info(f"Operario eliminado: {nombre} (id {operador_id})")
    return {"eliminado": operador_id, "nombre": nombre}


# ----------------------------------------------------------------------------
# Fase 3 — Edición de producción (sesiones + pacas)
# ----------------------------------------------------------------------------
def _rango(desde, hasta):
    hoy = datetime.now().date()
    try:
        d = datetime.strptime(desde, "%Y-%m-%d").date() if desde else hoy
    except ValueError:
        d = hoy
    try:
        h = datetime.strptime(hasta, "%Y-%m-%d").date() if hasta else hoy
    except ValueError:
        h = hoy
    if h < d:
        d, h = h, d
    return datetime.combine(d, datetime.min.time()), datetime.combine(h, datetime.min.time()) + timedelta(days=1)


class SesionUpdate(BaseModel):
    maquina: str | None = None
    operador: str | None = None
    marca: str | None = None
    presentacion: str | None = None
    fragancia: str | None = None


class PalletUpdate(BaseModel):
    # Los dos campos son opcionales para poder corregir solo uno. `cantidad_pacas` era
    # obligatorio hasta el 2026-08-06; hacerlo opcional no rompe a nadie, porque un
    # cuerpo que lo traiga sigue funcionando igual.
    cantidad_pacas: int | None = None
    fecha_hora: str | None = None      # "YYYY-MM-DD HH:MM[:SS]" o ISO con T


@router.get("/sesiones")
def listar_sesiones(desde: str = Query(None), hasta: str = Query(None), db: Session = Depends(get_db), ctx=Depends(require_planta_lectura)):
    """Lista sesiones del rango (default hoy) con su total de pacas y nº de registros."""
    ini, fin = _rango(desde, hasta)
    sesiones = (
        db.query(SesionTrabajoDB)
        .filter(SesionTrabajoDB.inicio_turno >= ini, SesionTrabajoDB.inicio_turno < fin)
        .order_by(SesionTrabajoDB.inicio_turno.desc())
        .all()
    )
    out = []
    for s in sesiones:
        total = int(db.query(func.coalesce(func.sum(PalletDB.cantidad_pacas), 0)).filter(PalletDB.session_id == s.id).scalar() or 0)
        n = db.query(func.count(PalletDB.id)).filter(PalletDB.session_id == s.id).scalar() or 0
        out.append({
            "id": s.id, "maquina": s.maquina, "operador": s.operador,
            "marca": s.marca, "presentacion": s.presentacion, "fragancia": s.fragancia,
            "inicio": s.inicio_turno.strftime("%Y-%m-%d %H:%M") if s.inicio_turno else "",
            "fin": s.fin_turno.strftime("%H:%M") if s.fin_turno else None,
            "estado": "Finalizado" if s.fin_turno else "Activo",
            "total_pacas": total, "n_registros": int(n),
        })
    return out


@router.put("/sesiones/{sesion_id}")
def actualizar_sesion(sesion_id: int, datos: SesionUpdate, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    s = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == sesion_id).first()
    if not s:
        raise HTTPException(status_code=404, detail="Sesión no encontrada")
    for campo in ("maquina", "operador", "marca", "presentacion", "fragancia"):
        val = getattr(datos, campo)
        if val is not None:
            setattr(s, campo, val.strip())
    db.commit()
    logger.info(f"Sesión {sesion_id} editada por admin {ctx.get('username')}")
    return {"id": s.id, "maquina": s.maquina, "operador": s.operador, "marca": s.marca,
            "presentacion": s.presentacion, "fragancia": s.fragancia}


# ----------------------------------------------------------------------------
# Cerrar un turno a mano / eliminar una sesión  (2026-08-06)
# ----------------------------------------------------------------------------
# Pasa a menudo que un grupo se va sin pulsar «finalizar» en la tablet, y el turno
# queda abierto: `iniciar_turno` rechaza al grupo siguiente con «Esta máquina ya
# tiene un turno activo». Hasta ahora la única salida era esperar al garbage
# collector de `tasks.py`, que cierra a las 13 h.

@router.post("/sesiones/{sesion_id}/cerrar")
def cerrar_sesion(sesion_id: int, background_tasks: BackgroundTasks,
                  db: Session = Depends(get_db), ctx=Depends(require_planta)):
    """Cierra un turno que quedó abierto, dejando constancia de quién lo hizo.

    Hace lo MISMO que el `POST /api/finalizar_turno` que usa la tablet
    (`operaciones.py`), y por eso el código es paralelo: si solo se pusiera
    `fin_turno`, quedarían detrás un paro abierto que crecería sin fin y pedidos de
    insumo que ningún operario va a confirmar ya.

      1. cierra el paro abierto, si lo hay —esto además evita el «SIN CIERRE» que
         deja el garbage collector, que sí olvida los paros—;
      2. cierra los pedidos de insumo vivos y avisa al insumista por WebSocket, para
         que desaparezcan de su bandeja al instante;
      3. fija `fin_turno` y `duracion_minutos`;
      4. escribe en `observaciones` quién lo cerró, en el mismo campo y con el mismo
         criterio que el GC («CERRADO AUTOMATICAMENTE POR EL SISTEMA», tasks.py).

    OJO con la tablet: `registrar_pallet` no comprueba `fin_turno`, así que si la
    tablet de esa máquina sigue trabajando seguirá mandando pallets a una sesión ya
    cerrada, y su botón de finalizar recibirá un 400. Es una acción pensada para
    turnos huérfanos; la web lo advierte antes de confirmar.
    """
    sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == sesion_id).first()
    if not sesion:
        raise HTTPException(status_code=404, detail="Sesión no encontrada")
    if sesion.fin_turno is not None:
        raise HTTPException(status_code=400, detail="Ese turno ya está finalizado")

    ahora = datetime.now()
    usuario = ctx.get("username") or "admin"

    paro_activo = db.query(ParoMaquinaDB).filter(
        ParoMaquinaDB.session_id == sesion_id,
        ParoMaquinaDB.fin_paro.is_(None),
    ).first()
    if paro_activo:
        paro_activo.fin_paro = ahora
        paro_activo.duracion_segundos = round(
            (ahora - paro_activo.inicio_paro).total_seconds(), 2
        )

    pedidos_activos = db.query(PedidoBodegaDB).filter(
        PedidoBodegaDB.session_id == sesion_id,
        PedidoBodegaDB.estado.in_(ESTADOS_PEDIDO_ACTIVO),
    ).all()
    for p in pedidos_activos:
        p.estado = ESTADO_PEDIDO_CIERRE_TURNO

    sesion.fin_turno = ahora
    sesion.duracion_minutos = (ahora - sesion.inicio_turno).total_seconds() / 60
    # String(255): el nombre de usuario es corto, pero se recorta por si acaso.
    sesion.observaciones = f"CERRADO MANUALMENTE POR: {usuario}"[:255]
    db.commit()

    # Mismo evento y mismo mecanismo que finalizar_turno: la app ya sabe manejarlo y
    # quita el pedido de la bandeja del insumista. Va en background porque un
    # WebSocket caído no puede tumbar el cierre del turno, que es lo importante; el
    # refresco periódico de la bandeja es el respaldo.
    for p in pedidos_activos:
        background_tasks.add_task(
            manager.broadcast_to_tipo,
            (p.categoria or "EMPAQUE").upper(),
            {"evento": "pedido_aceptado", "solicitud_id": p.id},
        )

    logger.info(
        f"Turno cerrado manualmente: sesión {sesion_id} ({sesion.maquina} · "
        f"{sesion.operador}) por {usuario} — {round(sesion.duracion_minutos, 1)} min, "
        f"{len(pedidos_activos)} pedido(s) cerrado(s), "
        f"paro abierto: {'sí' if paro_activo else 'no'}"
    )
    return {
        "id": sesion.id,
        "estado": "Finalizado",
        "fin": sesion.fin_turno.strftime("%H:%M"),
        "duracion_minutos": round(sesion.duracion_minutos, 2),
        "observaciones": sesion.observaciones,
        "paro_cerrado": bool(paro_activo),
        "pedidos_cerrados": len(pedidos_activos),
    }


@router.delete("/sesiones/{sesion_id}")
def eliminar_sesion(sesion_id: int, db: Session = Depends(get_db), ctx=Depends(require_superadmin)):
    """Borra una sesión y TODO lo que cuelga de ella. Irreversible, solo SUPERADMIN.

    Se borra en cascada a propósito. Dejar solo la fila de la sesión sería peor que
    no borrar: los pallets se cuentan en el dashboard por `pallets.fecha_hora`, sin
    pasar por la sesión, así que seguirían sumando en los KPIs y en los Excel
    mientras el turno del que salieron ya no existiría.

    Se devuelve el recuento de lo borrado por tabla para que quede en el log y en la
    respuesta: es la única traza que queda.
    """
    sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == sesion_id).first()
    if not sesion:
        raise HTTPException(status_code=404, detail="Sesión no encontrada")

    resumen = {
        "maquina": sesion.maquina,
        "operador": sesion.operador,
        "inicio": sesion.inicio_turno.strftime("%Y-%m-%d %H:%M") if sesion.inicio_turno else "",
    }
    borrado = {
        "pallets": db.query(PalletDB).filter(PalletDB.session_id == sesion_id).delete(synchronize_session=False),
        "paros": db.query(ParoMaquinaDB).filter(ParoMaquinaDB.session_id == sesion_id).delete(synchronize_session=False),
        "pedidos": db.query(PedidoBodegaDB).filter(PedidoBodegaDB.session_id == sesion_id).delete(synchronize_session=False),
        "comentarios": db.query(ComentarioTurnoDB).filter(ComentarioTurnoDB.session_id == sesion_id).delete(synchronize_session=False),
        "reportes": db.query(ReporteAppDB).filter(ReporteAppDB.session_id == sesion_id).delete(synchronize_session=False),
        "mensajes": db.query(MensajeTabletDB).filter(MensajeTabletDB.sesion_id == sesion_id).delete(synchronize_session=False),
    }
    db.delete(sesion)
    db.commit()

    logger.warning(
        f"SESIÓN ELIMINADA: #{sesion_id} ({resumen['maquina']} · {resumen['operador']} · "
        f"{resumen['inicio']}) por {ctx.get('username')} — "
        + ", ".join(f"{k}: {v}" for k, v in borrado.items())
    )
    return {"eliminada": sesion_id, "sesion": resumen, "borrado": borrado}


@router.get("/sesiones/{sesion_id}/pallets")
def listar_pallets(sesion_id: int, db: Session = Depends(get_db), ctx=Depends(require_planta_lectura)):
    pallets = db.query(PalletDB).filter(PalletDB.session_id == sesion_id).order_by(PalletDB.id.asc()).all()
    return [{"id": p.id, "cantidad_pacas": p.cantidad_pacas,
             "fecha_hora": p.fecha_hora.strftime("%Y-%m-%d %H:%M:%S") if p.fecha_hora else ""} for p in pallets]


@router.put("/pallets/{pallet_id}")
def actualizar_pallet(pallet_id: int, datos: PalletUpdate, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    """Corrige un registro de pacas: la cantidad, la hora, o las dos.

    La hora se admite desde el 2026-08-06. No es cosmética: el dashboard cuenta la
    producción por `pallets.fecha_hora`, así que mover un registro lo mueve de hora
    —y de día— en KPIs, gráfico y Excel. Sirve para colocar en su sitio los pallets
    que una tablet sincroniza tarde tras estar sin red.
    """
    p = db.query(PalletDB).filter(PalletDB.id == pallet_id).first()
    if not p:
        raise HTTPException(status_code=404, detail="Registro no encontrado")

    cambios = []
    if datos.cantidad_pacas is not None:
        if datos.cantidad_pacas < 0:
            raise HTTPException(status_code=400, detail="La cantidad no puede ser negativa")
        cambios.append(f"{p.cantidad_pacas} -> {datos.cantidad_pacas} pacas")
        p.cantidad_pacas = datos.cantidad_pacas

    if datos.fecha_hora is not None:
        nueva = _parsear_fecha_hora(datos.fecha_hora)
        anterior = p.fecha_hora.strftime("%Y-%m-%d %H:%M:%S") if p.fecha_hora else "—"
        cambios.append(f"{anterior} -> {nueva:%Y-%m-%d %H:%M:%S}")
        p.fecha_hora = nueva

    if not cambios:
        raise HTTPException(status_code=400, detail="No hay nada que cambiar")

    db.commit()
    logger.info(f"Pallet {pallet_id}: {' · '.join(cambios)} (admin {ctx.get('username')})")
    return {
        "id": p.id,
        "cantidad_pacas": p.cantidad_pacas,
        "fecha_hora": p.fecha_hora.strftime("%Y-%m-%d %H:%M:%S") if p.fecha_hora else "",
    }


def _parsear_fecha_hora(valor: str):
    """Acepta 'YYYY-MM-DD HH:MM[:SS]' y el ISO con T que manda `datetime-local`."""
    texto = (valor or "").strip().replace("T", " ")
    for formato in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            return datetime.strptime(texto, formato)
        except ValueError:
            continue
    raise HTTPException(
        status_code=400,
        detail="Fecha y hora inválidas (use AAAA-MM-DD HH:MM)",
    )


@router.delete("/pallets/{pallet_id}")
def eliminar_pallet(pallet_id: int, db: Session = Depends(get_db), ctx=Depends(require_superadmin)):
    """Borra un registro de pacas. Irreversible, solo SUPERADMIN.

    Es la vía para los duplicados que deja una tablet al reenviar su cola. Alternativa
    no destructiva y al alcance de un operativo: poner la cantidad a 0 con el PUT, que
    conserva la traza de que ese registro existió.
    """
    p = db.query(PalletDB).filter(PalletDB.id == pallet_id).first()
    if not p:
        raise HTTPException(status_code=404, detail="Registro no encontrado")
    resumen = {
        "sesion_id": p.session_id,
        "cantidad_pacas": p.cantidad_pacas,
        "fecha_hora": p.fecha_hora.strftime("%Y-%m-%d %H:%M:%S") if p.fecha_hora else "",
    }
    db.delete(p)
    db.commit()
    logger.warning(
        f"PALLET ELIMINADO: #{pallet_id} (sesión {resumen['sesion_id']}, "
        f"{resumen['cantidad_pacas']} pacas, {resumen['fecha_hora']}) "
        f"por {ctx.get('username')}"
    )
    return {"eliminado": pallet_id, "registro": resumen}


# ----------------------------------------------------------------------------
# Fase 3 — Edición de checklists de mantenimiento
# ----------------------------------------------------------------------------
class ItemUpdate(BaseModel):
    id: int
    marcado: bool


class ChecklistUpdate(BaseModel):
    supervisor: str | None = None
    comentarios: str | None = None
    items: list[ItemUpdate] | None = None


@router.get("/checklists")
def listar_checklists(desde: str = Query(None), hasta: str = Query(None), db: Session = Depends(get_db), ctx=Depends(require_planta_lectura)):
    ini, fin = _rango(desde, hasta)
    d, h = ini.date(), (fin - timedelta(days=1)).date()
    checklists = (
        db.query(MantenimientoChecklistDB)
        .filter(MantenimientoChecklistDB.fecha_turno >= d, MantenimientoChecklistDB.fecha_turno <= h)
        .order_by(MantenimientoChecklistDB.id.desc())
        .all()
    )
    out = []
    for c in checklists:
        items = [{"id": it.id, "etiqueta": it.etiqueta, "marcado": bool(it.marcado)} for it in c.items]
        out.append({
            "id": c.id, "maquina": c.maquina, "operador": c.operador, "momento": c.momento,
            "codigo_turno": c.codigo_turno,
            "fecha_turno": c.fecha_turno.isoformat() if c.fecha_turno else None,
            "hora": c.hora, "supervisor": c.supervisor, "comentarios": c.comentarios,
            "items": items, "items_ok": sum(1 for it in items if it["marcado"]), "total_items": len(items),
        })
    return out


@router.put("/checklists/{checklist_id}")
def actualizar_checklist(checklist_id: int, datos: ChecklistUpdate, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    c = db.query(MantenimientoChecklistDB).filter(MantenimientoChecklistDB.id == checklist_id).first()
    if not c:
        raise HTTPException(status_code=404, detail="Checklist no encontrado")
    if datos.supervisor is not None:
        c.supervisor = datos.supervisor.strip() or None
    if datos.comentarios is not None:
        c.comentarios = datos.comentarios.strip() or None
    if datos.items:
        por_id = {it.id: it.marcado for it in datos.items}
        for item in c.items:
            if item.id in por_id:
                item.marcado = por_id[item.id]
    db.commit()
    logger.info(f"Checklist {checklist_id} editado por admin {ctx.get('username')}")
    ok = sum(1 for it in c.items if it.marcado)
    return {"id": c.id, "supervisor": c.supervisor, "comentarios": c.comentarios,
            "items_ok": ok, "total_items": len(c.items)}


# ----------------------------------------------------------------------------
# Jerarquía Máquina → Marca → Presentación (maquina_productos)
# ----------------------------------------------------------------------------
# Fuente de verdad de QUÉ puede producir cada máquina. La app Android la consume
# (filtra selectores al iniciar turno) y la cachea offline; al reconectar la
# re-descarga. Editar aquí se refleja en las tablets sin reinstalar la app.

class MaquinaProductoIn(BaseModel):
    maquina_id: int
    marca: str
    presentacion: str


class MaquinaProductoUpdate(BaseModel):
    marca: str | None = None
    presentacion: str | None = None
    activo: bool | None = None


@router.get("/catalogos")
def catalogos_jerarquia(db: Session = Depends(get_db), ctx=Depends(require_planta_lectura)):
    """Catálogos para poblar los selectores del editor: máquinas activas + las
    listas maestras de marcas y presentaciones (tablas sin modelo SQLAlchemy)."""
    maquinas = db.query(MaquinaDB).filter(MaquinaDB.activa.is_(True)).order_by(MaquinaDB.id).all()
    marcas = [r[0] for r in db.execute(text(
        "SELECT nombre FROM marcas WHERE activa = 1 ORDER BY nombre"
    )).fetchall()]
    presentaciones = [r[0] for r in db.execute(text(
        "SELECT nombre FROM presentaciones WHERE activa = 1 ORDER BY id"
    )).fetchall()]
    # `fragancias` se añadió el 2026-08-06 para el editor de la jerarquía de
    # fragancias. Es una clave NUEVA en la respuesta: este endpoint exige token
    # admin y solo lo consume esta web, así que ninguna tablet lo ve.
    fragancias = [f.nombre for f in db.query(FraganciaDB)
                  .filter(FraganciaDB.activa.is_(True))
                  .order_by(FraganciaDB.nombre).all()]
    return {
        "maquinas": [{"id": m.id, "nombre": m.nombre, "tipo": m.tipo} for m in maquinas],
        "marcas": marcas,
        "presentaciones": presentaciones,
        "fragancias": fragancias,
    }


@router.get("/maquina_productos")
def listar_maquina_productos(db: Session = Depends(get_db), ctx=Depends(require_planta_lectura)):
    """Matriz completa agrupada por máquina (incluye filas inactivas)."""
    maquinas = db.query(MaquinaDB).order_by(MaquinaDB.id).all()
    filas = db.query(MaquinaProductoDB).order_by(MaquinaProductoDB.marca, MaquinaProductoDB.presentacion).all()
    por_maquina: dict[int, list] = {}
    for f in filas:
        por_maquina.setdefault(f.maquina_id, []).append(
            {"id": f.id, "marca": f.marca, "presentacion": f.presentacion, "activo": bool(f.activo)}
        )
    return [
        {"maquina_id": m.id, "maquina": m.nombre, "tipo": m.tipo, "activa": bool(m.activa),
         "productos": por_maquina.get(m.id, [])}
        for m in maquinas
    ]


@router.post("/maquina_productos")
def crear_maquina_producto(datos: MaquinaProductoIn, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    maquina = db.query(MaquinaDB).filter(MaquinaDB.id == datos.maquina_id).first()
    if not maquina:
        raise HTTPException(status_code=404, detail="Máquina no encontrada")
    marca = (datos.marca or "").strip()
    presentacion = (datos.presentacion or "").strip()
    if not marca or not presentacion:
        raise HTTPException(status_code=400, detail="Marca y presentación son obligatorias")
    existente = db.query(MaquinaProductoDB).filter(
        MaquinaProductoDB.maquina_id == datos.maquina_id,
        MaquinaProductoDB.marca == marca,
        MaquinaProductoDB.presentacion == presentacion,
    ).first()
    if existente:
        if existente.activo:
            raise HTTPException(status_code=409, detail="Esa combinación ya existe y está activa")
        existente.activo = True
        db.commit()
        logger.info(f"Jerarquía reactivada: maq {datos.maquina_id} {marca} {presentacion} (admin {ctx.get('username')})")
        return {"id": existente.id, "maquina_id": existente.maquina_id, "marca": marca,
                "presentacion": presentacion, "activo": True, "reactivado": True}
    fila = MaquinaProductoDB(maquina_id=datos.maquina_id, marca=marca, presentacion=presentacion, activo=True)
    db.add(fila)
    db.commit()
    db.refresh(fila)
    logger.info(f"Jerarquía creada: maq {datos.maquina_id} {marca} {presentacion} (admin {ctx.get('username')})")
    return {"id": fila.id, "maquina_id": fila.maquina_id, "marca": marca, "presentacion": presentacion, "activo": True}


@router.put("/maquina_productos/{fila_id}")
def actualizar_maquina_producto(fila_id: int, datos: MaquinaProductoUpdate, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    fila = db.query(MaquinaProductoDB).filter(MaquinaProductoDB.id == fila_id).first()
    if not fila:
        raise HTTPException(status_code=404, detail="Combinación no encontrada")
    if datos.marca is not None:
        fila.marca = datos.marca.strip()
    if datos.presentacion is not None:
        fila.presentacion = datos.presentacion.strip()
    if datos.activo is not None:
        fila.activo = datos.activo
    db.commit()
    logger.info(f"Jerarquía editada: id {fila.id} (admin {ctx.get('username')})")
    return {"id": fila.id, "maquina_id": fila.maquina_id, "marca": fila.marca,
            "presentacion": fila.presentacion, "activo": bool(fila.activo)}


# ----------------------------------------------------------------------------
# Jerarquía de fragancias: (máquina, marca) -> fragancias  (2026-08-06)
# ----------------------------------------------------------------------------
# La fragancia era universal (la misma lista fija en las 21 tablets). Con la línea
# líquida en producción cada máquina y marca hace fragancias distintas, así que
# pasa a ser parte de la jerarquía, en su propia tabla para no tocar
# `maquina_productos` ni la respuesta de GET /api/maquinas que consumen las tablets.
#
# La presentación NO entra: la fragancia no depende del gramaje.

class MaquinaFraganciaIn(BaseModel):
    maquina_id: int
    marca: str
    fragancia: str


class MaquinaFraganciaUpdate(BaseModel):
    fragancia: str | None = None
    activo: bool | None = None


def _fragancia_del_catalogo(db, nombre):
    """Valida contra el catálogo maestro y devuelve el nombre canónico.

    Se exige que exista y esté activa en `fragancias` en vez de aceptar texto
    libre: la fragancia se cruza con `sesiones_trabajo.fragancia` por texto, y un
    'Limon' sin tilde crearía una fragancia paralela que no cuadra con nada. Se
    devuelve el nombre tal como está en el catálogo (MySQL compara sin acentos ni
    mayúsculas, así que 'limon' entra y sale como 'Limón')."""
    nombre = (nombre or "").strip()
    if not nombre:
        raise HTTPException(status_code=400, detail="La fragancia es obligatoria")
    fila = db.query(FraganciaDB).filter(FraganciaDB.nombre == nombre).first()
    if not fila or not fila.activa:
        raise HTTPException(
            status_code=422,
            detail=f'La fragancia "{nombre}" no está en el catálogo. Créala primero.',
        )
    return fila.nombre


def _marca_de_la_maquina(db, maquina_id, marca):
    """Valida que la máquina produzca esa marca, con el mismo criterio blando que
    `iniciar_turno`: solo se comprueba si la máquina TIENE jerarquía configurada.
    Una máquina recién dada de alta (Maquina 12 hoy) debe poder configurar sus
    fragancias antes que sus presentaciones."""
    marca = (marca or "").strip()
    if not marca:
        raise HTTPException(status_code=400, detail="La marca es obligatoria")
    suyas = {f.marca for f in db.query(MaquinaProductoDB).filter(
        MaquinaProductoDB.maquina_id == maquina_id,
        MaquinaProductoDB.activo.is_(True),
    ).all()}
    if suyas and marca not in suyas:
        raise HTTPException(status_code=422, detail=f"Esa máquina no produce {marca}")
    return marca


@router.get("/maquina_fragancias")
def listar_maquina_fragancias(db: Session = Depends(get_db), ctx=Depends(require_planta_lectura)):
    """Matriz máquina → marca → fragancias, para el editor de la jerarquía.

    Las marcas de cada máquina son la unión de las que produce (`maquina_productos`
    activo) y las que ya tienen fragancias configuradas: si se da de baja una
    combinación de producto, sus fragancias siguen visibles y quitables en vez de
    quedarse en la tabla sin que nada las muestre.

    Incluye filas inactivas (`activo: false`) para poder reactivarlas, igual que
    /admin/maquina_productos.
    """
    maquinas = db.query(MaquinaDB).order_by(MaquinaDB.id).all()

    productos = db.query(MaquinaProductoDB).filter(MaquinaProductoDB.activo.is_(True)).all()
    marcas_por_maquina: dict[int, set] = {}
    for p in productos:
        if p.marca:
            marcas_por_maquina.setdefault(p.maquina_id, set()).add(p.marca)

    filas = db.query(MaquinaMarcaFraganciaDB).order_by(
        MaquinaMarcaFraganciaDB.marca, MaquinaMarcaFraganciaDB.fragancia
    ).all()
    fragancias_por_clave: dict[tuple, list] = {}
    for f in filas:
        fragancias_por_clave.setdefault((f.maquina_id, f.marca), []).append(
            {"id": f.id, "fragancia": f.fragancia, "activo": bool(f.activo)}
        )

    def _marcas(maquina_id: int) -> list:
        produce = marcas_por_maquina.get(maquina_id, set())
        configuradas = {m for (mid, m) in fragancias_por_clave if mid == maquina_id}
        return [
            {
                "marca": marca,
                "produce": marca in produce,
                "fragancias": fragancias_por_clave.get((maquina_id, marca), []),
            }
            for marca in sorted(produce | configuradas)
        ]

    return [
        {"maquina_id": m.id, "maquina": m.nombre, "tipo": m.tipo, "activa": bool(m.activa),
         "marcas": _marcas(m.id)}
        for m in maquinas
    ]


@router.post("/maquina_fragancias")
def crear_maquina_fragancia(datos: MaquinaFraganciaIn, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    """Asigna una fragancia a (máquina, marca). Reactiva si existía dada de baja."""
    maquina = db.query(MaquinaDB).filter(MaquinaDB.id == datos.maquina_id).first()
    if not maquina:
        raise HTTPException(status_code=404, detail="Máquina no encontrada")
    marca = _marca_de_la_maquina(db, datos.maquina_id, datos.marca)
    fragancia = _fragancia_del_catalogo(db, datos.fragancia)

    existente = db.query(MaquinaMarcaFraganciaDB).filter(
        MaquinaMarcaFraganciaDB.maquina_id == datos.maquina_id,
        MaquinaMarcaFraganciaDB.marca == marca,
        MaquinaMarcaFraganciaDB.fragancia == fragancia,
    ).first()
    if existente:
        if existente.activo:
            raise HTTPException(status_code=409, detail="Esa fragancia ya está activa para esa marca")
        existente.activo = True
        db.commit()
        logger.info(f"Fragancia reactivada: maq {datos.maquina_id} {marca} {fragancia} (admin {ctx.get('username')})")
        return {"id": existente.id, "maquina_id": existente.maquina_id, "marca": marca,
                "fragancia": fragancia, "activo": True, "reactivado": True}

    fila = MaquinaMarcaFraganciaDB(maquina_id=datos.maquina_id, marca=marca,
                                   fragancia=fragancia, activo=True)
    db.add(fila)
    db.commit()
    db.refresh(fila)
    logger.info(f"Fragancia creada: maq {datos.maquina_id} {marca} {fragancia} (admin {ctx.get('username')})")
    return {"id": fila.id, "maquina_id": fila.maquina_id, "marca": marca,
            "fragancia": fragancia, "activo": True}


@router.put("/maquina_fragancias/{fila_id}")
def actualizar_maquina_fragancia(fila_id: int, datos: MaquinaFraganciaUpdate, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    """Baja lógica (`{activo:false}`) o cambio de fragancia. Es lo que usa la web:
    borrar físicamente dejaría el histórico de sesiones sin su referencia."""
    fila = db.query(MaquinaMarcaFraganciaDB).filter(MaquinaMarcaFraganciaDB.id == fila_id).first()
    if not fila:
        raise HTTPException(status_code=404, detail="Fragancia no encontrada")
    if datos.fragancia is not None:
        fila.fragancia = _fragancia_del_catalogo(db, datos.fragancia)
    if datos.activo is not None:
        fila.activo = datos.activo
    db.commit()
    logger.info(f"Fragancia editada: id {fila.id} (admin {ctx.get('username')})")
    return {"id": fila.id, "maquina_id": fila.maquina_id, "marca": fila.marca,
            "fragancia": fila.fragancia, "activo": bool(fila.activo)}


@router.delete("/maquina_productos/{fila_id}")
def eliminar_maquina_producto(fila_id: int, db: Session = Depends(get_db), ctx=Depends(require_superadmin)):
    fila = db.query(MaquinaProductoDB).filter(MaquinaProductoDB.id == fila_id).first()
    if not fila:
        raise HTTPException(status_code=404, detail="Combinación no encontrada")
    db.delete(fila)
    db.commit()
    logger.info(f"Jerarquía eliminada: id {fila_id} (admin {ctx.get('username')})")
    return {"eliminado": fila_id}


# ----------------------------------------------------------------------------
# Catálogos maestros: Máquinas, Marcas, Presentaciones
# ----------------------------------------------------------------------------
# Altas para poder crear nuevas máquinas/marcas/presentaciones desde el editor de
# jerarquía y luego asignarlas. Idempotente: si ya existe (incluso inactiva) se
# reactiva en vez de duplicar (las columnas nombre son UNIQUE).

class NombreIn(BaseModel):
    nombre: str


# Tipos de línea válidos (mayúsculas, sin tilde). Los comparten `maquinas.tipo` y
# `operadores.tipo`: un operario de línea líquida trabaja en máquinas líquidas.
TIPOS_LINEA = {"SOLIDO", "LIQUIDO"}


def _norm_tipo(valor, por_defecto="SOLIDO"):
    """Normaliza el tipo de línea a 'SOLIDO'/'LIQUIDO'. Tolera 'Sólido'/'líquido'."""
    if valor is None:
        return por_defecto
    t = (valor or "").strip().upper()
    t = t.replace("Á", "A").replace("Í", "I").replace("Ó", "O")  # quita tildes comunes
    if t not in TIPOS_LINEA:
        raise HTTPException(status_code=400, detail="tipo inválido (use SOLIDO o LIQUIDO)")
    return t


class MaquinaIn(BaseModel):
    nombre: str
    tipo: str | None = None  # SOLIDO (default) | LIQUIDO


class MaquinaUpdate(BaseModel):
    nombre: str | None = None
    tipo: str | None = None
    activa: bool | None = None


def _crear_o_reactivar(db, modelo, campo_activo, nombre, etiqueta, ctx):
    nombre = (nombre or "").strip()
    if not nombre:
        raise HTTPException(status_code=400, detail="El nombre es obligatorio")
    existente = db.query(modelo).filter(modelo.nombre == nombre).first()
    if existente:
        if getattr(existente, campo_activo):
            raise HTTPException(status_code=409, detail=f"{etiqueta} \"{nombre}\" ya existe")
        setattr(existente, campo_activo, True)
        db.commit()
        logger.info(f"{etiqueta} reactivada: {nombre} (admin {ctx.get('username')})")
        return {"id": existente.id, "nombre": existente.nombre, "activo": True, "reactivado": True}
    fila = modelo(nombre=nombre)
    setattr(fila, campo_activo, True)
    db.add(fila)
    db.commit()
    db.refresh(fila)
    logger.info(f"{etiqueta} creada: {nombre} (admin {ctx.get('username')})")
    return {"id": fila.id, "nombre": fila.nombre, "activo": True}


@router.post("/maquinas")
def crear_maquina(datos: MaquinaIn, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    """Crea (o reactiva) una máquina con su tipo de línea (SOLIDO por defecto)."""
    nombre = (datos.nombre or "").strip()
    if not nombre:
        raise HTTPException(status_code=400, detail="El nombre es obligatorio")
    tipo = _norm_tipo(datos.tipo)
    existente = db.query(MaquinaDB).filter(MaquinaDB.nombre == nombre).first()
    if existente:
        if existente.activa:
            raise HTTPException(status_code=409, detail=f"Máquina \"{nombre}\" ya existe")
        existente.activa = True
        existente.tipo = tipo
        db.commit()
        logger.info(f"Máquina reactivada: {nombre} [{tipo}] (admin {ctx.get('username')})")
        return {"id": existente.id, "nombre": existente.nombre, "tipo": tipo, "activo": True, "reactivado": True}
    maq = MaquinaDB(nombre=nombre, tipo=tipo, activa=True)
    db.add(maq)
    db.commit()
    db.refresh(maq)
    logger.info(f"Máquina creada: {nombre} [{tipo}] (admin {ctx.get('username')})")
    return {"id": maq.id, "nombre": maq.nombre, "tipo": maq.tipo, "activo": True}


@router.put("/maquinas/{maquina_id}")
def actualizar_maquina(maquina_id: int, datos: MaquinaUpdate, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    maq = db.query(MaquinaDB).filter(MaquinaDB.id == maquina_id).first()
    if not maq:
        raise HTTPException(status_code=404, detail="Máquina no encontrada")
    if datos.nombre is not None:
        nuevo = datos.nombre.strip()
        if not nuevo:
            raise HTTPException(status_code=400, detail="El nombre no puede quedar vacío")
        choque = db.query(MaquinaDB).filter(MaquinaDB.nombre == nuevo, MaquinaDB.id != maquina_id).first()
        if choque:
            raise HTTPException(status_code=409, detail="Ya existe otra máquina con ese nombre")
        maq.nombre = nuevo
    if datos.tipo is not None:
        maq.tipo = _norm_tipo(datos.tipo)
    if datos.activa is not None:
        maq.activa = datos.activa
    db.commit()
    logger.info(f"Máquina {maq.id} actualizada (admin {ctx.get('username')})")
    return {"id": maq.id, "nombre": maq.nombre, "tipo": maq.tipo, "activa": bool(maq.activa)}


@router.post("/marcas")
def crear_marca(datos: NombreIn, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    return _crear_o_reactivar(db, MarcaDB, "activa", datos.nombre, "Marca", ctx)


@router.post("/presentaciones")
def crear_presentacion(datos: NombreIn, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    return _crear_o_reactivar(db, PresentacionDB, "activa", datos.nombre, "Presentación", ctx)


@router.post("/fragancias")
def crear_fragancia(datos: NombreIn, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    """Alta en el catálogo maestro de fragancias (Floral, Limón, ...).

    El catálogo es la lista de la que se eligen las fragancias de cada máquina+marca;
    asignarlas es cosa de /admin/maquina_fragancias."""
    return _crear_o_reactivar(db, FraganciaDB, "activa", datos.nombre, "Fragancia", ctx)


# ----------------------------------------------------------------------------
# Usuarios administradores (2026-08-06) — solo SUPERADMIN
# ----------------------------------------------------------------------------
# Gestiona la tabla `administradores`, que NO es solo de la web: la app Android hace
# login contra ella (POST /api/admin/login) y `GET /api/admin/supervisores` alimenta
# el selector «Seleccione Supervisor» del checklist de las tablets. Por eso:
#
#   - dar de alta un usuario operativo lo hace aparecer en ese selector;
#   - «eliminar» es baja lógica (`activo = 0`), como en el resto de la web: el
#     histórico de checklists guarda el nombre del supervisor como texto y un
#     borrado físico no lo rompería, pero sí perdería la trazabilidad de quién
#     existió;
#   - la contraseña NUNCA se devuelve, ni siquiera hasheada.

class UsuarioAdminIn(BaseModel):
    username: str
    password: str
    nivel_acceso: str | None = None      # SUPERADMIN por defecto sería peligroso: ver abajo


class UsuarioAdminUpdate(BaseModel):
    password: str | None = None          # resetear contraseña
    nivel_acceso: str | None = None
    activo: bool | None = None


def _norm_nivel(valor, por_defecto=None):
    """Normaliza y valida el nivel de acceso."""
    if valor is None:
        if por_defecto is None:
            raise HTTPException(status_code=400, detail="El nivel de acceso es obligatorio")
        return por_defecto
    nivel = (valor or "").strip().upper()
    if nivel not in NIVELES_VALIDOS:
        raise HTTPException(
            status_code=400,
            detail=f"Nivel inválido. Use uno de: {', '.join(sorted(NIVELES_VALIDOS))}",
        )
    return nivel


def _superadmins_activos(db, excluyendo=None):
    q = db.query(AdministradorDB).filter(
        AdministradorDB.nivel_acceso == NIVEL_SUPERADMIN,
        AdministradorDB.activo.is_(True),
    )
    if excluyendo is not None:
        q = q.filter(AdministradorDB.id != excluyendo)
    return q.count()


@router.get("/usuarios")
def listar_usuarios(db: Session = Depends(get_db), ctx=Depends(require_superadmin)):
    """Usuarios administradores. Sin contraseñas, ni en claro ni hasheadas.

    `password_migrada` dice si esa fila ya usa hash PBKDF2: mientras haya usuarios
    en texto plano conviene verlo, y se resuelve solo cuando esa persona entra.
    """
    usuarios = db.query(AdministradorDB).order_by(
        AdministradorDB.activo.desc(), AdministradorDB.username.asc()
    ).all()
    return [
        {
            "id": u.id,
            "username": u.username,
            "nivel_acceso": u.nivel_acceso,
            "activo": bool(u.activo),
            "password_migrada": seguridad.es_hash(u.password or ""),
            "es_tu_usuario": u.username == ctx.get("username"),
        }
        for u in usuarios
    ]


@router.post("/usuarios")
def crear_usuario(datos: UsuarioAdminIn, db: Session = Depends(get_db), ctx=Depends(require_superadmin)):
    """Alta de usuario administrador. La contraseña se guarda hasheada desde el minuto uno."""
    username = (datos.username or "").strip()
    if not username:
        raise HTTPException(status_code=400, detail="El usuario es obligatorio")
    password = datos.password or ""
    if len(password) < 6:
        raise HTTPException(status_code=400, detail="La contraseña debe tener al menos 6 caracteres")
    nivel = _norm_nivel(datos.nivel_acceso)

    existente = db.query(AdministradorDB).filter(AdministradorDB.username == username).first()
    if existente:
        if existente.activo:
            raise HTTPException(status_code=409, detail=f'El usuario "{username}" ya existe')
        # Reactivar con credenciales nuevas, igual que operarios/marcas.
        existente.activo = True
        existente.nivel_acceso = nivel
        existente.password = seguridad.hashear(password)
        db.commit()
        logger.info(f"Usuario admin reactivado: {username} [{nivel}] por {ctx.get('username')}")
        return {"id": existente.id, "username": username, "nivel_acceso": nivel,
                "activo": True, "reactivado": True}

    usuario = AdministradorDB(
        username=username,
        password=seguridad.hashear(password),
        nivel_acceso=nivel,
        activo=True,
    )
    db.add(usuario)
    db.commit()
    db.refresh(usuario)
    logger.info(f"Usuario admin creado: {username} [{nivel}] por {ctx.get('username')}")
    return {"id": usuario.id, "username": username, "nivel_acceso": nivel, "activo": True}


@router.put("/usuarios/{usuario_id}")
def actualizar_usuario(usuario_id: int, datos: UsuarioAdminUpdate,
                       db: Session = Depends(get_db), ctx=Depends(require_superadmin)):
    """Cambia nivel, reactiva/desactiva o resetea la contraseña.

    Dos salvaguardas que no son negociables:
      - nadie se desactiva ni se degrada a sí mismo (te quedarías fuera en el acto);
      - no puede quedar el sistema sin ningún SUPERADMIN activo, porque entonces
        nadie podría volver a gestionar usuarios y habría que arreglarlo por SQL.
    """
    usuario = db.query(AdministradorDB).filter(AdministradorDB.id == usuario_id).first()
    if not usuario:
        raise HTTPException(status_code=404, detail="Usuario no encontrado")

    es_uno_mismo = usuario.username == ctx.get("username")
    era_superadmin = usuario.nivel_acceso == NIVEL_SUPERADMIN and bool(usuario.activo)

    if datos.activo is False and es_uno_mismo:
        raise HTTPException(status_code=400, detail="No puedes desactivar tu propio usuario")

    nivel_nuevo = _norm_nivel(datos.nivel_acceso, usuario.nivel_acceso) if datos.nivel_acceso is not None else usuario.nivel_acceso
    if es_uno_mismo and nivel_nuevo != NIVEL_SUPERADMIN and usuario.nivel_acceso == NIVEL_SUPERADMIN:
        raise HTTPException(status_code=400, detail="No puedes quitarte a ti mismo el nivel SUPERADMIN")

    # ¿Este cambio dejaría el sistema sin superadmin?
    pierde_superadmin = era_superadmin and (datos.activo is False or nivel_nuevo != NIVEL_SUPERADMIN)
    if pierde_superadmin and _superadmins_activos(db, excluyendo=usuario.id) == 0:
        raise HTTPException(
            status_code=400,
            detail="Debe quedar al menos un SUPERADMIN activo",
        )

    if datos.password is not None:
        if len(datos.password) < 6:
            raise HTTPException(status_code=400, detail="La contraseña debe tener al menos 6 caracteres")
        usuario.password = seguridad.hashear(datos.password)
        logger.info(f"Contraseña de {usuario.username} reseteada por {ctx.get('username')}")
    if datos.nivel_acceso is not None:
        usuario.nivel_acceso = nivel_nuevo
    if datos.activo is not None:
        usuario.activo = datos.activo

    db.commit()
    logger.info(
        f"Usuario admin {usuario.username} actualizado por {ctx.get('username')} "
        f"[{usuario.nivel_acceso}, activo={bool(usuario.activo)}]"
    )
    return {"id": usuario.id, "username": usuario.username,
            "nivel_acceso": usuario.nivel_acceso, "activo": bool(usuario.activo)}


@router.get("/niveles")
def listar_niveles(ctx=Depends(require_superadmin)):
    """Niveles disponibles y qué implica cada uno, para el selector de la web."""
    return [
        {"nivel": NIVEL_SUPERADMIN, "descripcion": "Todo, incluidos usuarios y eliminar sesiones"},
        {"nivel": "ADMINPLANTA", "descripcion": "Operación de planta: corregir, cerrar turnos, catálogos"},
        {"nivel": "ADMINBODEGA", "descripcion": "Operación de bodega: corregir, cerrar turnos, catálogos"},
        {"nivel": "ADMIN", "descripcion": "Operación general (nivel heredado)"},
        {"nivel": NIVEL_CONSULTA, "descripcion": "Solo lectura: ve todo, no modifica nada"},
    ]


# ----------------------------------------------------------------------------
# Mensajes admin -> tablets activas (CAMBIO 4)
# ----------------------------------------------------------------------------
# El admin lista las sesiones de producción activas y envía un mensaje a una de
# ellas. Se persiste como no leído y se empuja por WebSocket de tablets (instantáneo
# si la tablet está online); el heartbeat lo recupera como respaldo. La tablet
# confirma (leído) al mostrarlo.

class MensajeAdminIn(BaseModel):
    sesion_id: int
    texto: str


@router.get("/sesiones_activas")
def listar_sesiones_activas(db: Session = Depends(get_db), ctx=Depends(require_planta_lectura)):
    """Sesiones de producción activas (turno abierto) + si su tablet recibiría ya.

    `tablet_online` responde a UNA pregunta concreta: ¿este mensaje sale al instante?
    Por eso mira el **WebSocket abierto** (`tablet_manager.connections`) y no el
    heartbeat.

    Antes usaba el heartbeat con el umbral de 60 s de `/api/tablets/estado`, y era
    engañoso: medido el 2026-08-07 sobre las cinco máquinas en turno, los latidos
    llegaban cada 20-25 minutos (hoy: 7, 8, 20, 36 min y 2 h 45), así que el chip
    decía OFFLINE casi siempre aunque la tablet estuviera produciendo. Un OFFLINE
    permanente no informa de nada y hace dudar de la lista entera.

    `segundos_desde_contacto` se mantiene aparte porque responde a otra pregunta —
    ¿hay alguien ahí?— y es la que importa al cerrar un turno a mano: una tablet
    puede estar trabajando con el WebSocket caído (el 2026-08-07 hubo 79 cierres de
    WS en una jornada) y sus pacas siguen llegando.

    Ojo si algún día el servicio pasa de `-w 1`: el registro de WebSockets vive en la
    memoria de cada worker, así que una conexión atendida por otro proceso no se
    vería desde aquí y saldría como «en cola». Se entregaría igual —los mensajes no
    leídos viajan en la respuesta del heartbeat—, pero el rótulo se quedaría corto.
    """
    sesiones = (
        db.query(SesionTrabajoDB)
        .filter(SesionTrabajoDB.fin_turno.is_(None))
        .order_by(SesionTrabajoDB.maquina.asc())
        .all()
    )
    ahora = datetime.now()
    tablets = db.query(EstadoTabletDB).all()
    conectadas = set(tablet_manager.connections.keys())
    ws_por_maquina: dict[str, bool] = {}
    contacto_por_maquina: dict[str, int] = {}
    for t in tablets:
        if not t.maquina:
            continue
        if t.device_id in conectadas:
            ws_por_maquina[t.maquina] = True
        if t.ultimo_heartbeat is not None:
            segundos = int((ahora - t.ultimo_heartbeat).total_seconds())
            # Una máquina puede tener varias tablets registradas (recambios, bajas):
            # vale la más reciente, que es la que de verdad está en la línea.
            previo = contacto_por_maquina.get(t.maquina)
            if previo is None or segundos < previo:
                contacto_por_maquina[t.maquina] = segundos

    out = []
    for s in sesiones:
        producto = " · ".join([x for x in [s.marca, s.presentacion, s.fragancia] if x]) or "—"
        out.append({
            "sesion_id": s.id,
            "maquina": s.maquina,
            "operador": s.operador,
            "producto": producto,
            "inicio": s.inicio_turno.strftime("%H:%M") if s.inicio_turno else "",
            "tablet_online": ws_por_maquina.get(s.maquina, False),
            # None = esa tablet no ha reportado nunca. No es 0: pintarlo como «hace 0s»
            # sería decir que acaba de conectarse.
            "segundos_desde_contacto": contacto_por_maquina.get(s.maquina),
        })
    return out


def _persistir_y_notificar(db, sesion, texto, origen):
    """Crea el mensaje para una sesión y lo empuja por WS a las tablets de su máquina.

    Devuelve el dict de resultado. NO hace commit (lo hace el llamador, para poder
    agrupar varios mensajes en una sola transacción en el envío masivo)."""
    msg = MensajeTabletDB(
        origen=origen,
        sesion_id=sesion.id,
        maquina=sesion.maquina,
        operador=sesion.operador,
        texto=texto,
    )
    db.add(msg)
    db.flush()  # asigna msg.id sin cerrar la transacción
    return msg


def _push_ws_mensaje(db, msg):
    """Empuja un mensaje ya persistido por WS a las tablets de su máquina."""
    entregado_ws = False
    if msg.maquina:
        tablets = db.query(EstadoTabletDB).filter(EstadoTabletDB.maquina == msg.maquina).all()
        for t in tablets:
            if enviar_payload_ws(t.device_id, {"tipo": "mensaje_admin", "id": msg.id, "texto": msg.texto}):
                entregado_ws = True
    return entregado_ws


@router.post("/mensajes")
def enviar_mensaje(datos: MensajeAdminIn, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    """Envía un mensaje a la sesión/tablet de producción indicada (individual)."""
    texto = (datos.texto or "").strip()
    if not texto:
        raise HTTPException(status_code=400, detail="El mensaje no puede estar vacío")
    if len(texto) > 500:
        raise HTTPException(status_code=400, detail="El mensaje es demasiado largo (máx. 500)")

    sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == datos.sesion_id).first()
    if not sesion:
        raise HTTPException(status_code=404, detail="Sesión no encontrada")
    if sesion.fin_turno is not None:
        raise HTTPException(status_code=400, detail="Esa sesión ya está finalizada")

    msg = _persistir_y_notificar(db, sesion, texto, ctx.get("username") or "admin")
    db.commit()
    db.refresh(msg)
    entregado_ws = _push_ws_mensaje(db, msg)

    logger.info(
        f"📨 Mensaje admin {msg.id} → {sesion.maquina} ({sesion.operador}) "
        f"[{'WS' if entregado_ws else 'pendiente heartbeat'}] por {msg.origen}"
    )
    return {
        "id": msg.id,
        "maquina": sesion.maquina,
        "operador": sesion.operador,
        "entregado_ws": entregado_ws,
        "mensaje": "Mensaje enviado",
    }


class MensajeMasivoIn(BaseModel):
    texto: str
    sesion_ids: list[int] | None = None  # None/[] => TODAS las sesiones activas


@router.post("/mensajes/masivo")
def enviar_mensaje_masivo(datos: MensajeMasivoIn, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    """Envía la MISMA alerta a varias sesiones activas a la vez.

    - `sesion_ids` vacío o nulo  -> a TODAS las sesiones activas (alerta general).
    - `sesion_ids` con una lista  -> solo a esas sesiones activas (varias seleccionadas).
    """
    texto = (datos.texto or "").strip()
    if not texto:
        raise HTTPException(status_code=400, detail="El mensaje no puede estar vacío")
    if len(texto) > 500:
        raise HTTPException(status_code=400, detail="El mensaje es demasiado largo (máx. 500)")

    q = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.fin_turno.is_(None))
    if datos.sesion_ids:
        q = q.filter(SesionTrabajoDB.id.in_(datos.sesion_ids))
    sesiones = q.order_by(SesionTrabajoDB.maquina.asc()).all()
    if not sesiones:
        raise HTTPException(status_code=404, detail="No hay sesiones activas a las que enviar")

    origen = ctx.get("username") or "admin"
    msgs = [_persistir_y_notificar(db, s, texto, origen) for s in sesiones]
    db.commit()

    detalle = []
    for msg in msgs:
        db.refresh(msg)
        entregado_ws = _push_ws_mensaje(db, msg)
        detalle.append({
            "id": msg.id, "sesion_id": msg.sesion_id, "maquina": msg.maquina,
            "operador": msg.operador, "entregado_ws": entregado_ws,
        })

    logger.info(f"📢 Alerta masiva por {origen}: {len(detalle)} sesión(es) — \"{texto[:60]}\"")
    return {"enviados": len(detalle), "detalle": detalle, "mensaje": f"Alerta enviada a {len(detalle)} tablet(s)"}


# ----------------------------------------------------------------------------
# Corrección de cantidades de un pedido de insumo (dashboard de insumos)
# ----------------------------------------------------------------------------
# "Validar" = corregir lo entregado/recibido cuando difiere de lo solicitado
# (ej. pidió 100, hubo 80, el operario recibió 79). Solo cantidades; no cambia
# el flujo ni el estado del pedido.

class PedidoCorreccionIn(BaseModel):
    cantidad_entregada: int | None = None
    cantidad_recibida: int | None = None


@router.put("/pedidos/{pedido_id}")
def corregir_pedido(pedido_id: int, datos: PedidoCorreccionIn, db: Session = Depends(get_db), ctx=Depends(require_bodega)):
    p = db.query(PedidoBodegaDB).filter(PedidoBodegaDB.id == pedido_id).first()
    if not p:
        raise HTTPException(status_code=404, detail="Pedido no encontrado")
    if datos.cantidad_entregada is not None:
        if datos.cantidad_entregada < 0:
            raise HTTPException(status_code=400, detail="La cantidad entregada no puede ser negativa")
        p.cantidad_entregada = datos.cantidad_entregada
    if datos.cantidad_recibida is not None:
        if datos.cantidad_recibida < 0:
            raise HTTPException(status_code=400, detail="La cantidad recibida no puede ser negativa")
        p.cantidad_recibida = datos.cantidad_recibida
    db.commit()
    logger.info(
        f"Pedido {pedido_id} corregido (admin {ctx.get('username')}): "
        f"ent={p.cantidad_entregada} rec={p.cantidad_recibida}"
    )
    return {
        "id": p.id,
        "cantidad_solicitada": p.cantidad_solicitada,
        "cantidad_entregada": p.cantidad_entregada,
        "cantidad_recibida": p.cantidad_recibida,
    }


@router.delete("/pedidos/{pedido_id}")
def eliminar_pedido(pedido_id: int, db: Session = Depends(get_db), ctx=Depends(require_superadmin)):
    """Elimina un registro de pedido de insumo (desde el dashboard de insumos)."""
    p = db.query(PedidoBodegaDB).filter(PedidoBodegaDB.id == pedido_id).first()
    if not p:
        raise HTTPException(status_code=404, detail="Pedido no encontrado")
    db.delete(p)
    db.commit()
    logger.info(f"Pedido {pedido_id} eliminado (admin {ctx.get('username')})")
    return {"eliminado": pedido_id}


# ----------------------------------------------------------------------------
# Entregas proactivas (entregar sin pedido) — corregir cantidad / eliminar
# ----------------------------------------------------------------------------
class EntregaCorreccionIn(BaseModel):
    cantidad: int | None = None


@router.put("/entregas/{entrega_id}")
def corregir_entrega(entrega_id: int, datos: EntregaCorreccionIn, db: Session = Depends(get_db), ctx=Depends(require_bodega)):
    """Corrige la cantidad de una entrega proactiva."""
    e = db.query(EntregaProactivaDB).filter(EntregaProactivaDB.id == entrega_id).first()
    if not e:
        raise HTTPException(status_code=404, detail="Entrega no encontrada")
    if datos.cantidad is not None:
        if datos.cantidad < 0:
            raise HTTPException(status_code=400, detail="La cantidad no puede ser negativa")
        e.cantidad = datos.cantidad
    db.commit()
    logger.info(f"Entrega proactiva {entrega_id} corregida (admin {ctx.get('username')}): cant={e.cantidad}")
    return {"id": e.id, "cantidad": e.cantidad}


@router.delete("/entregas/{entrega_id}")
def eliminar_entrega(entrega_id: int, db: Session = Depends(get_db), ctx=Depends(require_superadmin)):
    """Elimina una entrega proactiva y, si existe, su foto de evidencia en disco."""
    e = db.query(EntregaProactivaDB).filter(EntregaProactivaDB.id == entrega_id).first()
    if not e:
        raise HTTPException(status_code=404, detail="Entrega no encontrada")
    foto_path = e.foto_path  # p.ej. "/static/entregas/2_1_....jpg"
    db.delete(e)
    db.commit()
    # Borrado best-effort del archivo físico (no es crítico si falla).
    if foto_path:
        try:
            base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # api_produccion/
            ruta = os.path.join(base, foto_path.lstrip("/"))
            if os.path.isfile(ruta):
                os.remove(ruta)
        except Exception as ex:
            logger.warning(f"No se pudo borrar la foto de la entrega {entrega_id}: {ex}")
    logger.info(f"Entrega proactiva {entrega_id} eliminada (admin {ctx.get('username')})")
    return {"eliminado": entrega_id}


# ----------------------------------------------------------------------------
# Servidor de correo (2026-08-07) — solo SUPERADMIN
# ----------------------------------------------------------------------------
# Antes todo esto vivía en el `.env`: cambiar un destinatario obligaba a entrar al
# servidor y recargar el servicio. Ahora se administra desde /admin → Correo.
#
# Es SUPERADMIN porque aquí se decide quién recibe información de planta, se pueden
# tocar las credenciales del correo corporativo y el botón de prueba manda correos de
# verdad.
#
# La contraseña NUNCA sale de aquí: la respuesta dice si hay una guardada y de dónde
# sale (BD o `.env`), nunca su valor. Al guardar, un campo de contraseña vacío significa
# «déjala como está», no «bórrala» — si no, cualquier edición de un destinatario dejaría
# el correo sin poder autenticarse.

class CorreoIn(BaseModel):
    smtp_host: str | None = None
    smtp_port: int | None = None
    smtp_user: str | None = None
    smtp_pass: str | None = None
    smtp_from: str | None = None
    pedidos_to: list[str] | None = None
    pedidos_cc: list[str] | None = None
    reportes_to: list[str] | None = None
    reportes_cc: list[str] | None = None
    semanal_to: list[str] | None = None
    semanal_cc: list[str] | None = None
    semanal_activo: bool | None = None


class PruebaCorreoIn(BaseModel):
    tipo: str = "reportes"          # a qué lista mandar la prueba
    destinatario: str | None = None  # o a una dirección suelta, sin tocar las listas


def _correo_publico(db):
    """La configuración tal como la ve la web: sin contraseña, con el origen de cada dato."""
    cfg = config_correo.efectiva(db)
    fila = config_correo.obtener_fila(db)
    proxima = reporte_semanal.ultimo_corte() + timedelta(days=7)
    return {
        "smtp_host": cfg["smtp_host"],
        "smtp_port": cfg["smtp_port"],
        "smtp_user": cfg["smtp_user"],
        "smtp_from": cfg["smtp_from"],
        # Nunca el valor. Solo si existe y de dónde sale.
        "password_definida": bool(cfg["smtp_pass"]),
        "password_en_bd": bool(fila is not None and fila.smtp_pass),
        "destinos": cfg["destinos"],
        "semanal_activo": cfg["semanal_activo"],
        "semanal_ultimo_envio": _fmt_dt(fila.semanal_ultimo_envio) if fila is not None else None,
        "semanal_ultima_ventana": _fmt_dt(fila.semanal_ultima_ventana) if fila is not None else None,
        "semanal_proximo_envio": _fmt_dt(proxima),
        "actualizado_en": _fmt_dt(fila.actualizado_en) if fila is not None else None,
        "actualizado_por": fila.actualizado_por if fila is not None else None,
    }


def _fmt_dt(dt):
    return dt.strftime("%Y-%m-%d %H:%M:%S") if dt else None


@router.get("/correo")
def obtener_config_correo(db: Session = Depends(get_db), ctx=Depends(require_superadmin)):
    """Configuración de correo vigente. La contraseña no se devuelve nunca."""
    return _correo_publico(db)


@router.put("/correo")
def guardar_config_correo(datos: CorreoIn, db: Session = Depends(get_db),
                          ctx=Depends(require_superadmin)):
    """Guarda la configuración. Solo se tocan los campos presentes en el cuerpo.

    Vaciar una lista de destinatarios **sí** es una operación válida (`[]`): significa
    «vuelve a usar la del `.env`». Es la forma de deshacer un cambio sin tener que
    recordar cuál era el valor original.
    """
    fila = config_correo.obtener_fila(db, crear=True)
    cambios = datos.model_dump(exclude_unset=True)

    for campo in ("smtp_host", "smtp_user", "smtp_from"):
        if campo in cambios:
            valor = (cambios[campo] or "").strip()
            setattr(fila, campo, valor or None)
    if "smtp_port" in cambios:
        puerto = cambios["smtp_port"]
        if puerto is not None and not (1 <= int(puerto) <= 65535):
            raise HTTPException(status_code=400, detail="Puerto SMTP fuera de rango")
        fila.smtp_port = int(puerto) if puerto else None
    if "smtp_pass" in cambios:
        # Vacío = no tocar. Para volver a la del `.env` hay que mandar el literal "-".
        nueva = (cambios["smtp_pass"] or "").strip()
        if nueva == "-":
            fila.smtp_pass = None
        elif nueva:
            fila.smtp_pass = nueva
    for tipo in config_correo.TIPOS:
        for sufijo in ("to", "cc"):
            campo = f"{tipo}_{sufijo}"
            if campo in cambios:
                setattr(fila, campo, config_correo.texto(cambios[campo]) or None)
    if "semanal_activo" in cambios and cambios["semanal_activo"] is not None:
        fila.semanal_activo = bool(cambios["semanal_activo"])

    fila.actualizado_en = datetime.now()
    fila.actualizado_por = ctx.get("username")
    db.commit()
    logger.info(f"Configuración de correo actualizada por {ctx.get('username')}")
    return _correo_publico(db)


@router.post("/correo/prueba")
def probar_correo(datos: PruebaCorreoIn, db: Session = Depends(get_db),
                  ctx=Depends(require_superadmin)):
    """Manda un correo de prueba con la configuración YA GUARDADA.

    Se envía en primer plano a propósito, al revés que los avisos de planta: aquí lo
    único que se quiere es saber si el envío funciona, así que hace falta la respuesta
    del servidor SMTP, no una promesa.
    """
    tipo = (datos.tipo or "reportes").strip().lower()
    if tipo not in config_correo.TIPOS:
        raise HTTPException(status_code=400, detail=f"Tipo de correo desconocido: {tipo}")

    suelto = (datos.destinatario or "").strip()
    to = [suelto] if suelto else None
    cc = [] if suelto else None  # a una dirección suelta no se le mete el CC de la lista
    cfg = config_correo.efectiva(db)
    destino_txt = suelto or ", ".join(cfg["destinos"][tipo]["to"]) or "(sin destinatarios)"

    asunto = "✅ Prueba de correo — SIGEP"
    cuando = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    txt = (
        "Prueba de configuración de correo (SIGEP)\n"
        "-----------------------------------------\n"
        f"Lanzada por: {ctx.get('username')}\n"
        f"Fecha/Hora:  {cuando}\n"
        f"Servidor:    {cfg['smtp_host']}:{cfg['smtp_port']} como {cfg['smtp_user']}\n"
        f"Lista:       {tipo}\n"
        "-----------------------------------------\n"
        "Si lees esto, el correo saliente funciona."
    )
    html = f"""\
<div style="font-family:Arial,sans-serif;background:#f3f6f5;padding:22px">
  <div style="max-width:520px;margin:0 auto;background:#fff;border:1px solid #e2e8e6;border-radius:12px;overflow:hidden">
    <div style="background:#0D1A1C;padding:16px 20px">
      <span style="color:#F5A623;font-weight:800;font-size:16px">SIGEP</span>
      <span style="color:#88A19E;font-size:11px;letter-spacing:.18em;text-transform:uppercase;margin-left:8px">Prueba de correo</span>
    </div>
    <div style="padding:18px 20px">
      <p style="margin:0 0 12px;color:#1c2b29;font-size:15px">Si lees esto, el correo saliente funciona.</p>
      <p style="margin:0;color:#5E7674;font:600 12px Arial">
        Lanzada por {ctx.get('username')} · {cuando}<br>
        {cfg['smtp_host']}:{cfg['smtp_port']} como {cfg['smtp_user']} · lista «{tipo}»
      </p>
    </div>
  </div>
</div>"""

    enviado = email_service._enviar(asunto, txt, html, to=to, cc=cc, tipo=tipo, cfg=cfg)
    if not enviado:
        raise HTTPException(
            status_code=502,
            detail="No se pudo enviar. Revisa servidor, usuario, contraseña y destinatarios "
                   "(el detalle exacto queda en el log del servicio).",
        )
    return {"enviado": True, "destino": destino_txt, "tipo": tipo}


@router.post("/correo/semanal_ahora")
def enviar_semanal_ahora(db: Session = Depends(get_db), ctx=Depends(require_superadmin)):
    """Manda el reporte semanal de la última semana cerrada, sin esperar al viernes.

    **No mueve la marca del programador**: sirve para revisar el contenido o reenviarlo,
    y el envío automático del viernes sigue su curso igual.
    """
    enviado, datos = reporte_semanal.enviar(db, motivo=f"manual por {ctx.get('username')}")
    if not enviado:
        raise HTTPException(
            status_code=502,
            detail="El reporte se generó pero no pudo enviarse. Revisa la configuración de correo.",
        )
    return {
        "enviado": True,
        "desde": _fmt_dt(datos["desde"]),
        "hasta": _fmt_dt(datos["hasta"]),
        "total_paros": datos["total_paros"],
        "total_horas": reporte_semanal._hhmm(datos["total_segundos"]),
    }


@router.get("/correo/semanal_vista_previa")
def vista_previa_semanal(db: Session = Depends(get_db), ctx=Depends(require_superadmin)):
    """Los números del reporte sin enviar nada, para verlos en pantalla."""
    datos = reporte_semanal.calcular(db)
    return {
        "desde": _fmt_dt(datos["desde"]),
        "hasta": _fmt_dt(datos["hasta"]),
        "total_horas": reporte_semanal._hhmm(datos["total_segundos"]),
        "total_paros": datos["total_paros"],
        "promedio": reporte_semanal._hhmm(datos["promedio_segundos"]) if datos["promedio_segundos"] is not None else None,
        "variacion_pct": datos["variacion_pct"],
        "previo_horas": reporte_semanal._hhmm(datos["previo_segundos"]),
        "sin_cierre": datos["sin_cierre"],
        "en_curso": datos["en_curso"],
        # Lo que queda fuera del total (almuerzos): la web lo enseña para que nadie
        # compare con la vista de paros y crea que falta tiempo.
        "excluidos_paros": datos["excluidos_paros"],
        "excluidas": datos["excluidas"],
        "excluidos_horas": reporte_semanal._hhmm(datos["excluidos_segundos"]),
        "por_categoria": [
            {"etiqueta": e, "paros": n, "horas": reporte_semanal._hhmm(s)}
            for e, n, s in datos["por_categoria"][:8]
        ],
        "por_maquina": [
            {"etiqueta": e, "paros": n, "horas": reporte_semanal._hhmm(s)}
            for e, n, s in datos["por_maquina"][:8]
        ],
    }


# ----------------------------------------------------------------------------
# Paros (2026-08-07)
# ----------------------------------------------------------------------------
# Hasta ahora los paros solo se podían leer (`GET /dashboard/paros`) y corregir
# entrando a MySQL a mano. Así se perdió el paro 105: se borró por SQL sin ver de qué
# sesión colgaba. Estos endpoints hacen lo mismo, pero dejando traza en el log y sin
# tocar nada que no se vea.
#
# El `motivo` se guarda como "[Categoría] - comentario" (lo compone la tablet), así que
# aquí se acepta cada parte por separado y se recompone: escribir corchetes a mano en un
# campo de texto es justo el tipo de detalle que se olvida y rompe el desglose.

class ParoIn(BaseModel):
    categoria: str | None = None
    comentario: str | None = None
    inicio_paro: str | None = None
    fin_paro: str | None = None


class CerrarParoIn(BaseModel):
    fin_paro: str | None = None   # ausente = ahora mismo


def _componer_motivo(categoria, comentario):
    """("MANTENIMIENTO", "cambio de teflón") -> "[MANTENIMIENTO] - cambio de teflón".

    Sin comentario se guarda la categoría sola, que es como manda la tablet los motivos
    simples ("ALMUERZO"): así `_desglosar_motivo` lo vuelve a leer igual.
    """
    cat = (categoria or "").strip().upper()
    com = (comentario or "").strip()
    if not cat:
        raise HTTPException(status_code=400, detail="La categoría no puede ir vacía")
    return f"[{cat}] - {com}" if com else cat


def _paro_publico(p, sesion=None):
    categoria, comentario = _desglosar_motivo(p.motivo)
    estado, fin_efectivo, estimada = _estado_paro(p, sesion, datetime.now())
    return {
        "id": p.id,
        "sesion_id": p.session_id,
        "maquina": (sesion.maquina if sesion is not None else None) or "—",
        "operador": (sesion.operador if sesion is not None else None) or "—",
        "categoria": categoria,
        "comentario": comentario,
        "motivo": p.motivo or "",
        "inicio_paro": _fmt_dt(p.inicio_paro),
        "fin_paro": _fmt_dt(p.fin_paro),
        "duracion_segundos": _dur_segundos(p, fin_efectivo),
        "duracion_estimada": estimada,
        "estado": estado,
        # Un paro cuyo turno ya no existe: se puede corregir igual, pero conviene verlo.
        "sesion_existe": sesion is not None,
    }


@router.get("/paros")
def listar_paros_admin(desde: str = Query(None), hasta: str = Query(None),
                       db: Session = Depends(get_db), ctx=Depends(require_planta_lectura)):
    """Paros del rango (por `inicio_paro`), del más reciente al más antiguo.

    Mismo criterio de rango que `GET /dashboard/paros`, para que las dos listas no
    puedan discrepar. Sin rango, el día de hoy.
    """
    ini, fin = _rango(desde, hasta)
    filas = (
        db.query(ParoMaquinaDB)
        .filter(ParoMaquinaDB.inicio_paro >= ini, ParoMaquinaDB.inicio_paro < fin)
        .order_by(ParoMaquinaDB.inicio_paro.desc())
        .all()
    )
    ids = {p.session_id for p in filas if p.session_id}
    sesiones = {}
    if ids:
        sesiones = {s.id: s for s in
                    db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id.in_(ids)).all()}
    return [_paro_publico(p, sesiones.get(p.session_id)) for p in filas]


@router.put("/paros/{paro_id}")
def editar_paro(paro_id: int, datos: ParoIn, db: Session = Depends(get_db),
                ctx=Depends(require_planta)):
    """Corrige motivo y/o tiempos de un paro. Solo se toca lo que venga en el cuerpo.

    **Cambiar los tiempos mueve el reporte semanal y la vista de paros**, que cuentan
    por `inicio_paro`; y la duración se recalcula sola, porque dejar la vieja con horas
    nuevas daría un dato incoherente que nadie sabría de dónde salió.
    """
    p = db.query(ParoMaquinaDB).filter(ParoMaquinaDB.id == paro_id).first()
    if p is None:
        raise HTTPException(status_code=404, detail="Paro no encontrado")

    cambios = []
    cuerpo = datos.model_dump(exclude_unset=True)

    if "categoria" in cuerpo or "comentario" in cuerpo:
        categoria_actual, comentario_actual = _desglosar_motivo(p.motivo)
        nuevo = _componer_motivo(
            cuerpo.get("categoria", categoria_actual),
            cuerpo.get("comentario", comentario_actual),
        )
        if nuevo != (p.motivo or ""):
            cambios.append(f"motivo {p.motivo!r} -> {nuevo!r}")
            p.motivo = nuevo

    # Los tiempos se calculan y se VALIDAN antes de tocar la fila. Asignar y validar
    # después deja el objeto de la sesión con un valor que se rechazó: no llega a la BD
    # —la sesión de la petición se descarta— pero cualquier lectura posterior dentro del
    # mismo request vería un dato inválido.
    nuevo_inicio = p.inicio_paro
    nuevo_fin = p.fin_paro
    if "inicio_paro" in cuerpo and cuerpo["inicio_paro"]:
        nuevo_inicio = _parsear_fecha_hora(cuerpo["inicio_paro"])
    if "fin_paro" in cuerpo:
        # Cadena vacía = «déjalo abierto otra vez»; es la única forma de deshacer un
        # cierre puesto por error sin borrar el registro entero.
        nuevo_fin = _parsear_fecha_hora(cuerpo["fin_paro"]) if cuerpo["fin_paro"] else None

    if nuevo_fin and nuevo_inicio and nuevo_fin < nuevo_inicio:
        raise HTTPException(status_code=400, detail="El fin no puede ser anterior al inicio")

    if nuevo_inicio != p.inicio_paro:
        cambios.append(f"inicio {_fmt_dt(p.inicio_paro)} -> {nuevo_inicio:%Y-%m-%d %H:%M:%S}")
        p.inicio_paro = nuevo_inicio
    if nuevo_fin != p.fin_paro:
        destino = f"{nuevo_fin:%Y-%m-%d %H:%M:%S}" if nuevo_fin else "abierto"
        cambios.append(f"fin {_fmt_dt(p.fin_paro)} -> {destino}")
        p.fin_paro = nuevo_fin

    if not cambios:
        raise HTTPException(status_code=400, detail="No hay nada que cambiar")

    # La duración se recalcula siempre que haya con qué; sin fin, se deja en NULL, que
    # es lo que significa «sigue abierto» en el resto del sistema.
    p.duracion_segundos = (
        (p.fin_paro - p.inicio_paro).total_seconds()
        if (p.fin_paro and p.inicio_paro) else None
    )

    db.commit()
    logger.info(f"Paro {paro_id}: {' · '.join(cambios)} (admin {ctx.get('username')})")
    sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == p.session_id).first()
    return _paro_publico(p, sesion)


@router.post("/paros/{paro_id}/cerrar")
def cerrar_paro(paro_id: int, datos: CerrarParoIn = None, db: Session = Depends(get_db),
                ctx=Depends(require_planta)):
    """Cierra un paro que quedó abierto. Sin `fin_paro`, se cierra ahora mismo.

    Existe por el agujero conocido: el recolector de `tasks.py` cierra los turnos
    colgados pero **no sus paros**, que se quedan con `fin_paro` NULL para siempre. Antes
    la única salida era un UPDATE a mano en MySQL.
    """
    p = db.query(ParoMaquinaDB).filter(ParoMaquinaDB.id == paro_id).first()
    if p is None:
        raise HTTPException(status_code=404, detail="Paro no encontrado")
    if p.fin_paro is not None:
        raise HTTPException(status_code=400, detail="Ese paro ya está cerrado")

    fin = _parsear_fecha_hora(datos.fin_paro) if (datos and datos.fin_paro) else datetime.now()
    if p.inicio_paro and fin < p.inicio_paro:
        raise HTTPException(status_code=400, detail="El fin no puede ser anterior al inicio")

    p.fin_paro = fin
    p.duracion_segundos = (fin - p.inicio_paro).total_seconds() if p.inicio_paro else None
    db.commit()
    logger.info(f"Paro {paro_id} cerrado manualmente en {fin:%Y-%m-%d %H:%M:%S} "
                f"(admin {ctx.get('username')})")
    sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == p.session_id).first()
    return _paro_publico(p, sesion)


@router.delete("/paros/{paro_id}")
def eliminar_paro(paro_id: int, db: Session = Depends(get_db),
                  ctx=Depends(require_superadmin)):
    """⚠️ Borrado físico de un paro. Solo SUPERADMIN.

    No arrastra nada: `paros_maquina` no tiene hijos. Aun así desaparece de los KPIs y
    del reporte semanal, así que para anularlo sin destruirlo suele bastar con cerrarlo
    o corregir sus horas.
    """
    p = db.query(ParoMaquinaDB).filter(ParoMaquinaDB.id == paro_id).first()
    if p is None:
        raise HTTPException(status_code=404, detail="Paro no encontrado")
    resumen = (f"sesión {p.session_id} · {p.motivo!r} · {_fmt_dt(p.inicio_paro)} → "
               f"{_fmt_dt(p.fin_paro) or 'abierto'}")
    db.delete(p)
    db.commit()
    logger.warning(f"Paro {paro_id} ELIMINADO por {ctx.get('username')} — {resumen}")
    return {"eliminado": paro_id, "detalle": resumen}


# ----------------------------------------------------------------------------
# Comentarios de turno y reportes de la app (2026-08-07)
# ----------------------------------------------------------------------------
# Los dos los escriben los operarios desde la tablet (`POST /api/comentarios_turno` y
# `POST /api/reportes_app`). Se leían en el dashboard y por correo, pero corregir un
# texto o borrar una prueba obligaba a entrar a MySQL.
#
# Los reportes además se «atienden» en vez de borrarse: el histórico de qué falló en
# planta es justo lo que hoy se pierde en el buzón de correo.

class TextoIn(BaseModel):
    texto: str | None = None


class ReporteAdminIn(BaseModel):
    texto: str | None = None
    atendido: bool | None = None


def _feedback_publico(f, atendido=False):
    salida = {
        "id": f.id,
        "sesion_id": f.session_id,
        "maquina": f.maquina or "—",
        "operador": f.operador or "—",
        "texto": f.texto or "",
        "creado_en": _fmt_dt(f.creado_en),
    }
    if atendido:
        salida.update({
            "atendido": bool(f.atendido),
            "atendido_en": _fmt_dt(f.atendido_en),
            "atendido_por": f.atendido_por,
        })
    return salida


def _feedback_del_rango(db, modelo, desde, hasta, limite):
    """Filas del rango por `creado_en`; sin rango, las `limite` más recientes.

    Sin rango NO se cae al día de hoy, al revés que el resto del admin: un comentario o
    un reporte es esporádico —uno por turno como mucho— y la pantalla saldría vacía casi
    siempre. Mismo criterio que las tarjetas del dashboard.
    """
    q = db.query(modelo)
    if desde or hasta:
        ini, fin = _rango(desde, hasta)
        q = q.filter(modelo.creado_en >= ini, modelo.creado_en < fin)
    return q.order_by(modelo.creado_en.desc()).limit(min(max(limite or 100, 1), 500)).all()


@router.get("/comentarios")
def listar_comentarios(desde: str = Query(None), hasta: str = Query(None),
                       limit: int = Query(100), db: Session = Depends(get_db),
                       ctx=Depends(require_superadmin)):
    filas = _feedback_del_rango(db, ComentarioTurnoDB, desde, hasta, limit)
    return [_feedback_publico(f) for f in filas]


@router.put("/comentarios/{comentario_id}")
def editar_comentario(comentario_id: int, datos: TextoIn, db: Session = Depends(get_db),
                      ctx=Depends(require_superadmin)):
    """Corrige el texto de un comentario de turno. Vacío no: para eso está eliminar."""
    c = db.query(ComentarioTurnoDB).filter(ComentarioTurnoDB.id == comentario_id).first()
    if c is None:
        raise HTTPException(status_code=404, detail="Comentario no encontrado")
    texto = (datos.texto or "").strip()
    if not texto:
        raise HTTPException(status_code=400, detail="El texto no puede quedar vacío")
    c.texto = texto[:1000]
    db.commit()
    logger.info(f"Comentario de turno {comentario_id} editado (admin {ctx.get('username')})")
    return _feedback_publico(c)


@router.delete("/comentarios/{comentario_id}")
def eliminar_comentario(comentario_id: int, db: Session = Depends(get_db),
                        ctx=Depends(require_superadmin)):
    """⚠️ Borrado físico. Solo SUPERADMIN."""
    c = db.query(ComentarioTurnoDB).filter(ComentarioTurnoDB.id == comentario_id).first()
    if c is None:
        raise HTTPException(status_code=404, detail="Comentario no encontrado")
    db.delete(c)
    db.commit()
    logger.warning(f"Comentario de turno {comentario_id} ELIMINADO por {ctx.get('username')}")
    return {"eliminado": comentario_id}


@router.get("/reportes_app")
def listar_reportes_app(desde: str = Query(None), hasta: str = Query(None),
                        limit: int = Query(100), solo_pendientes: bool = Query(False),
                        db: Session = Depends(get_db), ctx=Depends(require_superadmin)):
    filas = _feedback_del_rango(db, ReporteAppDB, desde, hasta, limit)
    if solo_pendientes:
        filas = [f for f in filas if not f.atendido]
    return [_feedback_publico(f, atendido=True) for f in filas]


@router.put("/reportes_app/{reporte_id}")
def editar_reporte_app(reporte_id: int, datos: ReporteAdminIn, db: Session = Depends(get_db),
                       ctx=Depends(require_superadmin)):
    """Corrige el texto de un reporte y/o lo marca como atendido.

    Atender **no borra nada**: deja quién y cuándo. Desmarcarlo limpia las dos marcas,
    para que no quede un «atendido por» de algo que se volvió a abrir.
    """
    r = db.query(ReporteAppDB).filter(ReporteAppDB.id == reporte_id).first()
    if r is None:
        raise HTTPException(status_code=404, detail="Reporte no encontrado")

    cuerpo = datos.model_dump(exclude_unset=True)
    if not cuerpo:
        raise HTTPException(status_code=400, detail="No hay nada que cambiar")

    if "texto" in cuerpo:
        texto = (cuerpo["texto"] or "").strip()
        if not texto:
            raise HTTPException(status_code=400, detail="El texto no puede quedar vacío")
        r.texto = texto[:1000]
    if "atendido" in cuerpo and cuerpo["atendido"] is not None:
        r.atendido = bool(cuerpo["atendido"])
        r.atendido_en = datetime.now() if r.atendido else None
        r.atendido_por = ctx.get("username") if r.atendido else None

    db.commit()
    logger.info(f"Reporte de app {reporte_id} actualizado (admin {ctx.get('username')}): "
                f"atendido={r.atendido}")
    return _feedback_publico(r, atendido=True)


@router.delete("/reportes_app/{reporte_id}")
def eliminar_reporte_app(reporte_id: int, db: Session = Depends(get_db),
                         ctx=Depends(require_superadmin)):
    """⚠️ Borrado físico. Solo SUPERADMIN — para los de prueba; los reales se atienden."""
    r = db.query(ReporteAppDB).filter(ReporteAppDB.id == reporte_id).first()
    if r is None:
        raise HTTPException(status_code=404, detail="Reporte no encontrado")
    db.delete(r)
    db.commit()
    logger.warning(f"Reporte de app {reporte_id} ELIMINADO por {ctx.get('username')}")
    return {"eliminado": reporte_id}


# ----------------------------------------------------------------------------
# Tablets registradas (2026-08-07)
# ----------------------------------------------------------------------------
# `estado_tablets` la escriben las propias tablets con su heartbeat, y la fila se crea
# sola la primera vez que un dispositivo aparece. Con el tiempo se acumulan entradas de
# equipos retirados o reinstalados —había 24 el 2026-08-07, varias sin dar señales en
# semanas— y no había forma de limpiarlas salvo entrando a MySQL.
#
# **Un borrado NO es permanente si el equipo sigue vivo:** el siguiente heartbeat vuelve
# a crear la fila (con el nombre y la máquina que mande la app). Sirve para retirar
# equipos que ya no están, no para «desactivar» uno en uso; la UI lo advierte.

class TabletAdminIn(BaseModel):
    nombre: str | None = None
    maquina: str | None = None


@router.get("/tablets")
def listar_tablets_admin(db: Session = Depends(get_db), ctx=Depends(require_superadmin)):
    """Tablets registradas, de la que dio señales más recientemente a la más olvidada."""
    ahora = datetime.now()
    conectadas = set(tablet_manager.connections.keys())
    filas = db.query(EstadoTabletDB).all()
    salida = []
    for t in filas:
        segundos = int((ahora - t.ultimo_heartbeat).total_seconds()) if t.ultimo_heartbeat else None
        salida.append({
            "device_id": t.device_id,
            "nombre": t.nombre,
            "maquina": t.maquina,
            "pendientes": t.pendientes or 0,
            "ultimo_heartbeat": _fmt_dt(t.ultimo_heartbeat),
            "ultima_sincronizacion": _fmt_dt(t.ultima_sincronizacion),
            "segundos_desde_heartbeat": segundos,
            # Igual que en `sesiones_activas`: conectada = WebSocket abierto ahora, que
            # es lo único que se puede afirmar. El heartbeat llega cada 20-25 minutos.
            "conectada": t.device_id in conectadas,
        })
    # Sin heartbeat al final: son las que nunca reportaron, no las más recientes.
    salida.sort(key=lambda t: (t["segundos_desde_heartbeat"] is None,
                               t["segundos_desde_heartbeat"] or 0))
    return salida


@router.put("/tablets/{device_id}")
def editar_tablet(device_id: str, datos: TabletAdminIn, db: Session = Depends(get_db),
                  ctx=Depends(require_superadmin)):
    """Corrige el nombre o la máquina de una tablet.

    **La app los vuelve a mandar en cada heartbeat**, así que esto arregla la lista hasta
    el siguiente latido; si el valor está mal en el equipo, hay que corregirlo allí. Sirve
    sobre todo para entradas de tablets que ya no reportan y ensucian la lista.
    """
    t = db.query(EstadoTabletDB).filter(EstadoTabletDB.device_id == device_id).first()
    if t is None:
        raise HTTPException(status_code=404, detail="Tablet no encontrada")
    cuerpo = datos.model_dump(exclude_unset=True)
    if not cuerpo:
        raise HTTPException(status_code=400, detail="No hay nada que cambiar")
    if "nombre" in cuerpo:
        t.nombre = (cuerpo["nombre"] or "").strip() or None
    if "maquina" in cuerpo:
        t.maquina = (cuerpo["maquina"] or "").strip() or None
    db.commit()
    logger.info(f"Tablet {device_id} editada (admin {ctx.get('username')}): "
                f"nombre={t.nombre!r} maquina={t.maquina!r}")
    return {"device_id": t.device_id, "nombre": t.nombre, "maquina": t.maquina}


@router.delete("/tablets/{device_id}")
def eliminar_tablet(device_id: str, db: Session = Depends(get_db),
                    ctx=Depends(require_superadmin)):
    """⚠️ Quita el registro de una tablet. Solo SUPERADMIN.

    No borra nada de producción: `estado_tablets` solo guarda el estado de sincronización.
    Si el equipo sigue encendido, su próximo heartbeat vuelve a crear la fila.
    """
    t = db.query(EstadoTabletDB).filter(EstadoTabletDB.device_id == device_id).first()
    if t is None:
        raise HTTPException(status_code=404, detail="Tablet no encontrada")
    resumen = f"{t.nombre or '—'} / {t.maquina or '—'}"
    db.delete(t)
    db.commit()
    logger.warning(f"Tablet {device_id} ({resumen}) ELIMINADA por {ctx.get('username')}")
    return {"eliminada": device_id, "detalle": resumen}
