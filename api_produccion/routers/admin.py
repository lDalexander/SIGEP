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

from fastapi import APIRouter, Depends, HTTPException, Header, Query
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
)
from routers.tablets import enviar_payload_ws, UMBRAL_OFFLINE_SEGUNDOS

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
    tipo: str | None = None  # SOLIDO (default) | LIQUIDO


class OperadorUpdate(BaseModel):
    nombre: str | None = None
    tipo: str | None = None
    activo: bool | None = None


@router.get("/operadores")
def listar_operadores(tipo: str = Query(None), db: Session = Depends(get_db), ctx=Depends(require_admin)):
    """Lista los operarios. Con `tipo` se limita a los de esa línea."""
    q = db.query(OperadorDB)
    if tipo:
        q = q.filter(OperadorDB.tipo == _norm_tipo(tipo))
    ops = q.order_by(OperadorDB.activo.desc(), OperadorDB.nombre.asc()).all()
    return [{"id": o.id, "nombre": o.nombre, "tipo": o.tipo, "activo": bool(o.activo)} for o in ops]


@router.post("/operadores")
def crear_operador(datos: OperadorIn, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
    if datos.tipo is not None:
        op.tipo = _norm_tipo(datos.tipo)
    if datos.activo is not None:
        op.activo = datos.activo
    db.commit()
    logger.info(f"Operario actualizado: id {op.id} activo={op.activo} tipo={op.tipo} nombre={op.nombre}")
    return {"id": op.id, "nombre": op.nombre, "tipo": op.tipo, "activo": bool(op.activo)}


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
def catalogos_jerarquia(db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def listar_maquina_productos(db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def crear_maquina_producto(datos: MaquinaProductoIn, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def actualizar_maquina_producto(fila_id: int, datos: MaquinaProductoUpdate, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def listar_maquina_fragancias(db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def crear_maquina_fragancia(datos: MaquinaFraganciaIn, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def actualizar_maquina_fragancia(fila_id: int, datos: MaquinaFraganciaUpdate, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def eliminar_maquina_producto(fila_id: int, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def crear_maquina(datos: MaquinaIn, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def actualizar_maquina(maquina_id: int, datos: MaquinaUpdate, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def crear_marca(datos: NombreIn, db: Session = Depends(get_db), ctx=Depends(require_admin)):
    return _crear_o_reactivar(db, MarcaDB, "activa", datos.nombre, "Marca", ctx)


@router.post("/presentaciones")
def crear_presentacion(datos: NombreIn, db: Session = Depends(get_db), ctx=Depends(require_admin)):
    return _crear_o_reactivar(db, PresentacionDB, "activa", datos.nombre, "Presentación", ctx)


@router.post("/fragancias")
def crear_fragancia(datos: NombreIn, db: Session = Depends(get_db), ctx=Depends(require_admin)):
    """Alta en el catálogo maestro de fragancias (Floral, Limón, ...).

    El catálogo es la lista de la que se eligen las fragancias de cada máquina+marca;
    asignarlas es cosa de /admin/maquina_fragancias."""
    return _crear_o_reactivar(db, FraganciaDB, "activa", datos.nombre, "Fragancia", ctx)


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
def listar_sesiones_activas(db: Session = Depends(get_db), ctx=Depends(require_admin)):
    """Sesiones de producción activas (turno abierto) + si su tablet está online."""
    sesiones = (
        db.query(SesionTrabajoDB)
        .filter(SesionTrabajoDB.fin_turno.is_(None))
        .order_by(SesionTrabajoDB.maquina.asc())
        .all()
    )
    # Estado de tablets por máquina, para indicar si el mensaje llegará al instante.
    ahora = datetime.now()
    tablets = db.query(EstadoTabletDB).all()
    online_por_maquina: dict[str, bool] = {}
    for t in tablets:
        if not t.maquina:
            continue
        en_linea = bool(t.en_linea_reportado) and t.ultimo_heartbeat is not None and \
            (ahora - t.ultimo_heartbeat).total_seconds() <= UMBRAL_OFFLINE_SEGUNDOS
        online_por_maquina[t.maquina] = online_por_maquina.get(t.maquina, False) or en_linea

    out = []
    for s in sesiones:
        producto = " · ".join([x for x in [s.marca, s.presentacion, s.fragancia] if x]) or "—"
        out.append({
            "sesion_id": s.id,
            "maquina": s.maquina,
            "operador": s.operador,
            "producto": producto,
            "inicio": s.inicio_turno.strftime("%H:%M") if s.inicio_turno else "",
            "tablet_online": online_por_maquina.get(s.maquina, False),
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
def enviar_mensaje(datos: MensajeAdminIn, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def enviar_mensaje_masivo(datos: MensajeMasivoIn, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def corregir_pedido(pedido_id: int, datos: PedidoCorreccionIn, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def eliminar_pedido(pedido_id: int, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def corregir_entrega(entrega_id: int, datos: EntregaCorreccionIn, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
def eliminar_entrega(entrega_id: int, db: Session = Depends(get_db), ctx=Depends(require_admin)):
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
