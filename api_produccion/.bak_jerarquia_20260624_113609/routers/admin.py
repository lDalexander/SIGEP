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
import secrets
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Header, Query
from pydantic import BaseModel
from sqlalchemy import func
from sqlalchemy.orm import Session

from database import get_db, logger
from models import (
    OperadorDB,
    AdministradorDB,
    SesionTrabajoDB,
    PalletDB,
    MantenimientoChecklistDB,
    MantenimientoChecklistItemDB,
)

router = APIRouter(prefix="/api/admin", tags=["Administración"])

# Token de sesión admin -> datos del admin. En memoria (1 worker gunicorn).
_TOKENS: dict = {}


# ----------------------------------------------------------------------------
# Autenticación
# ----------------------------------------------------------------------------
class AuthIn(BaseModel):
    nombre: str
    pin: str


def require_admin(x_admin_token: str = Header(default=None)):
    """Dependencia: exige un token admin válido en la cabecera X-Admin-Token."""
    if not x_admin_token or x_admin_token not in _TOKENS:
        raise HTTPException(status_code=401, detail="Sesión admin requerida o expirada")
    return _TOKENS[x_admin_token]


@router.post("/auth")
def admin_auth(datos: AuthIn, db: Session = Depends(get_db)):
    """Valida credenciales admin y emite un token de sesión."""
    admin = (
        db.query(AdministradorDB)
        .filter(AdministradorDB.username == datos.nombre, AdministradorDB.activo == True)
        .first()
    )
    if not admin or admin.password != datos.pin:
        raise HTTPException(status_code=401, detail="Usuario o contraseña incorrectos")
    token = secrets.token_urlsafe(32)
    _TOKENS[token] = {"username": admin.username, "nivel": admin.nivel_acceso}
    logger.info(f"Admin login: {admin.username} ({admin.nivel_acceso})")
    return {"token": token, "username": admin.username, "nivel_acceso": admin.nivel_acceso}


@router.post("/logout")
def admin_logout(x_admin_token: str = Header(default=None), ctx=Depends(require_admin)):
    _TOKENS.pop(x_admin_token, None)
    return {"ok": True}


# ----------------------------------------------------------------------------
# Operarios (Fase 2)
# ----------------------------------------------------------------------------
class OperadorIn(BaseModel):
    nombre: str


class OperadorUpdate(BaseModel):
    nombre: str | None = None
    activo: bool | None = None


@router.get("/operadores")
def listar_operadores(db: Session = Depends(get_db), ctx=Depends(require_admin)):
    ops = db.query(OperadorDB).order_by(OperadorDB.activo.desc(), OperadorDB.nombre.asc()).all()
    return [{"id": o.id, "nombre": o.nombre, "activo": bool(o.activo)} for o in ops]


@router.post("/operadores")
def crear_operador(datos: OperadorIn, db: Session = Depends(get_db), ctx=Depends(require_admin)):
    nombre = (datos.nombre or "").strip()
    if not nombre:
        raise HTTPException(status_code=400, detail="El nombre es obligatorio")
    existente = db.query(OperadorDB).filter(OperadorDB.nombre == nombre).first()
    if existente:
        if existente.activo:
            raise HTTPException(status_code=409, detail="Ese operario ya existe y está activo")
        existente.activo = True
        db.commit()
        logger.info(f"Operario reactivado: {nombre} (id {existente.id})")
        return {"id": existente.id, "nombre": existente.nombre, "activo": True, "reactivado": True}
    op = OperadorDB(nombre=nombre, activo=True)
    db.add(op)
    db.commit()
    db.refresh(op)
    logger.info(f"Operario creado: {nombre} (id {op.id})")
    return {"id": op.id, "nombre": op.nombre, "activo": True}


@router.put("/operadores/{operador_id}")
def actualizar_operador(operador_id: int, datos: OperadorUpdate, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
    if datos.activo is not None:
        op.activo = datos.activo
    db.commit()
    logger.info(f"Operario actualizado: id {op.id} activo={op.activo} nombre={op.nombre}")
    return {"id": op.id, "nombre": op.nombre, "activo": bool(op.activo)}


@router.delete("/operadores/{operador_id}")
def eliminar_operador(operador_id: int, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
    cantidad_pacas: int


@router.get("/sesiones")
def listar_sesiones(desde: str = Query(None), hasta: str = Query(None), db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def actualizar_sesion(sesion_id: int, datos: SesionUpdate, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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


@router.get("/sesiones/{sesion_id}/pallets")
def listar_pallets(sesion_id: int, db: Session = Depends(get_db), ctx=Depends(require_admin)):
    pallets = db.query(PalletDB).filter(PalletDB.session_id == sesion_id).order_by(PalletDB.id.asc()).all()
    return [{"id": p.id, "cantidad_pacas": p.cantidad_pacas,
             "fecha_hora": p.fecha_hora.strftime("%Y-%m-%d %H:%M:%S") if p.fecha_hora else ""} for p in pallets]


@router.put("/pallets/{pallet_id}")
def actualizar_pallet(pallet_id: int, datos: PalletUpdate, db: Session = Depends(get_db), ctx=Depends(require_admin)):
    if datos.cantidad_pacas < 0:
        raise HTTPException(status_code=400, detail="La cantidad no puede ser negativa")
    p = db.query(PalletDB).filter(PalletDB.id == pallet_id).first()
    if not p:
        raise HTTPException(status_code=404, detail="Registro no encontrado")
    anterior = p.cantidad_pacas
    p.cantidad_pacas = datos.cantidad_pacas
    db.commit()
    logger.info(f"Pallet {pallet_id}: {anterior} -> {datos.cantidad_pacas} pacas (admin {ctx.get('username')})")
    return {"id": p.id, "cantidad_pacas": p.cantidad_pacas}


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
def listar_checklists(desde: str = Query(None), hasta: str = Query(None), db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def actualizar_checklist(checklist_id: int, datos: ChecklistUpdate, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
