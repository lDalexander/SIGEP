"""Chat supervisor ↔ insumista, con historial (v1.7.0).

Hasta ahora la única comunicación con bodega era el propio pedido. Si el supervisor
necesitaba decirle algo a un insumista —«prioriza la 7B», «esa entrega quedó sin foto»—
no había canal, y lo que se hablaba por radio no dejaba rastro. De ahí la petición de
trazabilidad: aquí todo queda escrito y nada se borra.

TABLA APARTE DE `mensajes_tablet` (CAMBIO 4) porque aquella es admin → tablet de
PRODUCCIÓN, de un solo sentido y colgada de `sesion_id`. Esta es bidireccional y entre
personas.

LOS DOS EXTREMOS VIVEN EN TABLAS DISTINTAS: los supervisores en `administradores` (entran
por `/api/admin/login`) y los insumistas en `usuarios` (por PIN). Por eso hay dos
columnas y no un «emisor_id» genérico, que obligaría a adivinar en qué tabla buscar.

QUIÉN ERES LO DICE TU TOKEN, no el cuerpo de la petición: con `X-Admin-Token` escribes
como SUPERVISOR, con `X-User-Token` como INSUMISTA. Sin ninguno de los dos no se escribe.

`insumista_id = NULL` es DIFUSIÓN: el aviso general a todos los de turno. Un insumista
ve su conversación y además las difusiones; nunca lo que el supervisor le escribió a otro.
"""

from datetime import datetime, timedelta

from fastapi import APIRouter, BackgroundTasks, Depends, Header, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy import func, or_
from sqlalchemy.orm import Session
from typing import Optional

from database import get_db, logger
from models import AdministradorDB, MensajeBodegaDB, UsuarioDB
from routers.admin import _TOKENS
from services import sesiones_usuario
from services.fcm_service import notificar_usuario_por_fcm

router = APIRouter(prefix="/api/mensajes/bodega", tags=["Chat de bodega"])

LIMITE_TEXTO = 500


class MensajeIn(BaseModel):
    texto: str
    # Solo lo usa el supervisor. Null o ausente = difusión a todos los de turno.
    insumista_id: Optional[int] = None


def _quien_escribe(db, x_admin_token, x_user_token):
    """-> (emisor, supervisor_id, insumista_id_propio). 401 si no se identifica.

    El admin se resuelve contra `_TOKENS` directamente y no con `require_admin` porque
    aquí los dos tipos de token son válidos: la dependencia habría cortado con 401 al
    insumista antes de mirar el suyo.
    """
    if x_admin_token:
        ctx = _TOKENS.get(x_admin_token)
        if ctx:
            admin = db.query(AdministradorDB).filter(
                AdministradorDB.username == ctx.get("username")
            ).first()
            return "SUPERVISOR", (admin.id if admin else None), None

    if x_user_token:
        sesion = sesiones_usuario.resolver_token(db, x_user_token)
        if sesion:
            return "INSUMISTA", None, sesion.usuario_id

    raise HTTPException(
        status_code=401,
        detail="Hay que iniciar sesión para usar el chat de bodega",
    )


def _serializar(filas, nombres_sup, nombres_ins):
    return [
        {
            "id": m.id,
            "emisor": m.emisor,
            "de": (nombres_sup.get(m.supervisor_id) if m.emisor == "SUPERVISOR"
                   else nombres_ins.get(m.insumista_id)) or "—",
            "supervisor_id": m.supervisor_id,
            "insumista_id": m.insumista_id,
            "difusion": m.insumista_id is None,
            "texto": m.texto,
            "creado_en": m.creado_en.strftime("%Y-%m-%d %H:%M") if m.creado_en else None,
            "hora": m.creado_en.strftime("%H:%M") if m.creado_en else None,
            "leido": m.leido_en is not None,
        }
        for m in filas
    ]


@router.get("")
def leer_conversacion(
    insumista_id: int = Query(None, description="Solo para el supervisor: con quién."),
    limite: int = Query(100, le=500),
    db: Session = Depends(get_db),
    x_admin_token: str = Header(default=None),
    x_user_token: str = Header(default=None),
):
    """La conversación, de la más vieja a la más nueva (como se lee un chat)."""
    emisor, _, propio = _quien_escribe(db, x_admin_token, x_user_token)

    q = db.query(MensajeBodegaDB)
    if emisor == "INSUMISTA":
        # El insumista ve lo suyo y las difusiones. Nunca lo de otro: `insumista_id`
        # no se acepta desde el cliente en esta rama, se impone el de su sesión.
        q = q.filter(or_(MensajeBodegaDB.insumista_id == propio,
                         MensajeBodegaDB.insumista_id.is_(None)))
    elif insumista_id:
        q = q.filter(or_(MensajeBodegaDB.insumista_id == insumista_id,
                         MensajeBodegaDB.insumista_id.is_(None)))
    # Un supervisor sin `insumista_id` ve todo: es su vista de «qué se está hablando».

    filas = q.order_by(MensajeBodegaDB.creado_en.desc()).limit(limite).all()
    filas.reverse()

    sup_ids = {m.supervisor_id for m in filas if m.supervisor_id}
    ins_ids = {m.insumista_id for m in filas if m.insumista_id}
    nombres_sup = {a.id: a.username for a in db.query(AdministradorDB).filter(
        AdministradorDB.id.in_(sup_ids))} if sup_ids else {}
    nombres_ins = {u.id: u.nombre for u in db.query(UsuarioDB).filter(
        UsuarioDB.id.in_(ins_ids))} if ins_ids else {}

    return {"yo": emisor, "mensajes": _serializar(filas, nombres_sup, nombres_ins)}


@router.post("", status_code=201)
def enviar(
    datos: MensajeIn,
    tareas: BackgroundTasks,
    db: Session = Depends(get_db),
    x_admin_token: str = Header(default=None),
    x_user_token: str = Header(default=None),
):
    """Escribe un mensaje. El emisor sale del token, nunca del cuerpo."""
    emisor, supervisor_id, propio = _quien_escribe(db, x_admin_token, x_user_token)

    texto = (datos.texto or "").strip()
    if not texto:
        raise HTTPException(status_code=400, detail="El mensaje no puede estar vacío")
    if len(texto) > LIMITE_TEXTO:
        raise HTTPException(status_code=400, detail=f"El mensaje no puede pasar de {LIMITE_TEXTO} caracteres")

    if emisor == "INSUMISTA":
        # El insumista escribe «a supervisión», no a un supervisor concreto: cuál está
        # de turno no es asunto suyo. Se guarda con su id y sin supervisor.
        destinatario = propio
        supervisor_id = None
    else:
        destinatario = datos.insumista_id
        if destinatario:
            if not db.query(UsuarioDB).filter(UsuarioDB.id == destinatario).first():
                raise HTTPException(status_code=404, detail="Ese insumista no existe")

    try:
        msg = MensajeBodegaDB(
            supervisor_id=supervisor_id,
            insumista_id=destinatario,
            emisor=emisor,
            texto=texto,
        )
        db.add(msg)
        db.commit()
        db.refresh(msg)

        # El push es un aviso, no el canal: el mensaje ya está guardado y la app lo ve
        # al abrir el chat aunque el FCM se pierda.
        if emisor == "SUPERVISOR":
            if destinatario:
                tareas.add_task(notificar_usuario_por_fcm, destinatario,
                                "Mensaje del supervisor", texto[:120])
            else:
                for sesion, usuario in sesiones_usuario.de_turno(db):
                    tareas.add_task(notificar_usuario_por_fcm, usuario.id,
                                    "Aviso a bodega", texto[:120])

        logger.info(f"💬 Mensaje de bodega {msg.id} ({emisor} → {destinatario or 'TODOS'})")
        return {"id": msg.id, "mensaje": "Enviado"}
    except HTTPException:
        db.rollback()
        raise
    except Exception as e:
        db.rollback()
        logger.error(f"Error al enviar mensaje de bodega: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.post("/leidos")
def marcar_leidos(
    db: Session = Depends(get_db),
    x_admin_token: str = Header(default=None),
    x_user_token: str = Header(default=None),
):
    """Marca como leído lo que te han escrito. Idempotente."""
    emisor, _, propio = _quien_escribe(db, x_admin_token, x_user_token)
    ahora = datetime.now()

    q = db.query(MensajeBodegaDB).filter(MensajeBodegaDB.leido_en.is_(None))
    if emisor == "INSUMISTA":
        # Solo lo que le escribieron A ÉL: sus propios mensajes no se «leen» solos.
        q = q.filter(MensajeBodegaDB.emisor == "SUPERVISOR",
                     or_(MensajeBodegaDB.insumista_id == propio,
                         MensajeBodegaDB.insumista_id.is_(None)))
    else:
        q = q.filter(MensajeBodegaDB.emisor == "INSUMISTA")

    try:
        marcados = q.update({MensajeBodegaDB.leido_en: ahora}, synchronize_session=False)
        db.commit()
        return {"marcados": int(marcados or 0)}
    except Exception as e:
        db.rollback()
        logger.error(f"Error al marcar leídos: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.get("/resumen")
def resumen_conversaciones(
    db: Session = Depends(get_db),
    x_admin_token: str = Header(default=None),
    x_user_token: str = Header(default=None),
):
    """Para el supervisor: con quién tiene conversación y cuánto sin leer.

    Devuelve a TODOS los insumistas activos, tengan o no mensajes: la pantalla del
    supervisor es también desde donde se empieza una conversación, y una lista que solo
    muestre a quien ya escribió no deja empezar ninguna.
    """
    emisor, _, propio = _quien_escribe(db, x_admin_token, x_user_token)

    if emisor == "INSUMISTA":
        sin_leer = db.query(func.count(MensajeBodegaDB.id)).filter(
            MensajeBodegaDB.emisor == "SUPERVISOR",
            MensajeBodegaDB.leido_en.is_(None),
            or_(MensajeBodegaDB.insumista_id == propio, MensajeBodegaDB.insumista_id.is_(None)),
        ).scalar() or 0
        return {"yo": emisor, "sin_leer": int(sin_leer)}

    limite = datetime.now() - timedelta(days=7)
    conteos = dict(
        db.query(MensajeBodegaDB.insumista_id, func.count(MensajeBodegaDB.id))
        .filter(MensajeBodegaDB.emisor == "INSUMISTA", MensajeBodegaDB.leido_en.is_(None))
        .group_by(MensajeBodegaDB.insumista_id).all()
    )
    ultimos = dict(
        db.query(MensajeBodegaDB.insumista_id, func.max(MensajeBodegaDB.creado_en))
        .filter(MensajeBodegaDB.creado_en >= limite)
        .group_by(MensajeBodegaDB.insumista_id).all()
    )
    de_turno = {u.id for _, u in sesiones_usuario.de_turno(db)}

    usuarios = db.query(UsuarioDB).filter(
        UsuarioDB.activo.is_(True), UsuarioDB.rol.ilike("%insumista%")
    ).order_by(UsuarioDB.nombre.asc()).all()

    return {
        "yo": emisor,
        "conversaciones": [
            {
                "insumista_id": u.id,
                "nombre": u.nombre,
                "rol": u.rol,
                "de_turno": u.id in de_turno,
                "sin_leer": int(conteos.get(u.id, 0)),
                "ultimo": ultimos[u.id].strftime("%Y-%m-%d %H:%M") if ultimos.get(u.id) else None,
            }
            for u in usuarios
        ],
    }
