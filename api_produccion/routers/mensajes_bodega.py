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

`insumista_id = NULL` es DIFUSIÓN: el aviso general a todos los de turno.

CONVERSACIONES POR PAREJA (2026-10-13). La conversación es el par (supervisor_id,
insumista_id), como en WhatsApp: el supervisor A no ve lo que habló B, y el insumista
tiene un chat aparte con cada supervisor que le escribe. Hasta entonces el insumista
escribía «a supervisión» (supervisor_id NULL) y todo salía mezclado en un solo hilo.

Tres hilos posibles para un insumista:
  * con un supervisor concreto  -> supervisor_id = S, insumista_id = él
  * «Supervisión» general       -> supervisor_id NULL: lo ven TODOS los supervisores en
                                   su chat con ese insumista. Es lo que manda una tablet
                                   1.7.1, que no sabe elegir supervisor, y lo que usa un
                                   insumista para empezar sin que nadie le haya escrito.
  * avisos a toda la bodega     -> insumista_id NULL

Todo lo nuevo son parámetros OPCIONALES: sin ellos cada endpoint responde como antes
para la tablet del insumista. El supervisor sí cambia —ya solo ve sus conversaciones—
porque eso es justo lo que se pidió.
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
    # Solo lo usa el insumista: a qué supervisor responde. Null, ausente o 0 =
    # «Supervisión» general, que es lo único que sabe mandar una tablet 1.7.1.
    supervisor_id: Optional[int] = None


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


def _nombres(db, filas):
    sup_ids = {m.supervisor_id for m in filas if m.supervisor_id}
    ins_ids = {m.insumista_id for m in filas if m.insumista_id}
    nombres_sup = {a.id: a.username for a in db.query(AdministradorDB).filter(
        AdministradorDB.id.in_(sup_ids))} if sup_ids else {}
    nombres_ins = {u.id: u.nombre for u in db.query(UsuarioDB).filter(
        UsuarioDB.id.in_(ins_ids))} if ins_ids else {}
    return nombres_sup, nombres_ins


def _filtro_hilo(emisor, supervisor_propio, insumista_propio,
                 insumista_id=None, supervisor_id=None, difusion=False):
    """Condiciones SQL del hilo pedido, o None si es la consulta antigua sin hilo.

    Nadie puede pedir un hilo ajeno: el supervisor no elige su propio id (sale del
    token) y el insumista tampoco el suyo.
    """
    M = MensajeBodegaDB
    if difusion:
        return [M.insumista_id.is_(None)]
    if emisor == "SUPERVISOR":
        if not insumista_id:
            return None
        # Su par con ese insumista, más lo que el insumista mandó «a supervisión».
        return [M.insumista_id == insumista_id,
                or_(M.supervisor_id == supervisor_propio, M.supervisor_id.is_(None))]
    if supervisor_id is None:
        return None
    if supervisor_id == 0:
        return [M.insumista_id == insumista_propio, M.supervisor_id.is_(None)]
    return [M.insumista_id == insumista_propio, M.supervisor_id == supervisor_id]


@router.get("")
def leer_conversacion(
    insumista_id: int = Query(None, description="Solo para el supervisor: con quién."),
    supervisor_id: int = Query(None, description="Solo para el insumista: con qué supervisor. 0 = «Supervisión» general."),
    difusion: bool = Query(False, description="Solo los avisos a toda la bodega."),
    limite: int = Query(100, le=500),
    db: Session = Depends(get_db),
    x_admin_token: str = Header(default=None),
    x_user_token: str = Header(default=None),
):
    """La conversación, de la más vieja a la más nueva (como se lee un chat)."""
    emisor, sup_propio, propio = _quien_escribe(db, x_admin_token, x_user_token)

    q = db.query(MensajeBodegaDB)
    filtro = _filtro_hilo(emisor, sup_propio, propio, insumista_id, supervisor_id, difusion)
    if filtro is not None:
        q = q.filter(*filtro)
    elif emisor == "INSUMISTA":
        # Consulta de una tablet 1.7.1: lo suyo y las difusiones, todo junto. Nunca lo
        # de otro: `insumista_id` no se acepta desde el cliente en esta rama.
        q = q.filter(or_(MensajeBodegaDB.insumista_id == propio,
                         MensajeBodegaDB.insumista_id.is_(None)))
    else:
        # Un supervisor sin hilo ve sus conversaciones, lo dirigido a supervisión y
        # las difusiones: su vista de «qué se está hablando», sin lo de otros.
        q = q.filter(or_(MensajeBodegaDB.supervisor_id == sup_propio,
                         MensajeBodegaDB.supervisor_id.is_(None),
                         MensajeBodegaDB.insumista_id.is_(None)))

    # El desempate por `id` NO es decorativo: `creado_en` es un DATETIME sin
    # milisegundos, así que dos mensajes del mismo segundo —un supervisor y un
    # insumista escribiendo a la vez, que es lo normal— salían en orden arbitrario y la
    # conversación se leía descolocada. `id` es autoincremental: es el único orden real.
    filas = q.order_by(MensajeBodegaDB.creado_en.desc(), MensajeBodegaDB.id.desc()).limit(limite).all()
    filas.reverse()

    nombres_sup, nombres_ins = _nombres(db, filas)
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
        # Responde en el hilo del supervisor que eligió, o a «Supervisión» en general.
        destinatario = propio
        supervisor_id = datos.supervisor_id or None
        if supervisor_id:
            if not db.query(AdministradorDB).filter(AdministradorDB.id == supervisor_id).first():
                raise HTTPException(status_code=404, detail="Ese supervisor no existe")
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
                # Con su nombre: el insumista tiene un chat por supervisor y necesita
                # saber en cuál le escribieron.
                firma = db.query(AdministradorDB.username).filter(
                    AdministradorDB.id == supervisor_id).scalar() if supervisor_id else None
                tareas.add_task(notificar_usuario_por_fcm, destinatario,
                                f"Mensaje de {firma}" if firma else "Mensaje del supervisor",
                                texto[:120])
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
    insumista_id: int = Query(None, description="Supervisor: solo ese hilo."),
    supervisor_id: int = Query(None, description="Insumista: solo ese hilo. 0 = «Supervisión»."),
    difusion: bool = Query(False, description="Insumista: solo los avisos generales."),
    db: Session = Depends(get_db),
    x_admin_token: str = Header(default=None),
    x_user_token: str = Header(default=None),
):
    """Marca como leído lo que te han escrito. Idempotente.

    Con un hilo marca solo ese: abrir el chat con un supervisor no puede dar por leído
    lo que escribió otro.
    """
    emisor, sup_propio, propio = _quien_escribe(db, x_admin_token, x_user_token)
    ahora = datetime.now()
    M = MensajeBodegaDB

    # Solo lo que te escribieron A TI: tus propios mensajes no se «leen» solos.
    q = db.query(M).filter(
        M.leido_en.is_(None),
        M.emisor == ("SUPERVISOR" if emisor == "INSUMISTA" else "INSUMISTA"),
    )
    filtro = _filtro_hilo(emisor, sup_propio, propio, insumista_id, supervisor_id, difusion)
    if filtro is not None:
        q = q.filter(*filtro)
    elif emisor == "INSUMISTA":
        q = q.filter(or_(M.insumista_id == propio, M.insumista_id.is_(None)))
    else:
        q = q.filter(or_(M.supervisor_id == sup_propio, M.supervisor_id.is_(None)))

    try:
        marcados = q.update({M.leido_en: ahora}, synchronize_session=False)
        db.commit()
        return {"marcados": int(marcados or 0)}
    except Exception as e:
        db.rollback()
        logger.error(f"Error al marcar leídos: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


def _ultimo_por_clave(filas, clave):
    """{clave: mensaje más reciente}. `filas` llega ordenada de más nueva a más vieja."""
    ultimos = {}
    for m in filas:
        ultimos.setdefault(clave(m), m)
    return ultimos


def _resumen_ultimo(m):
    if not m:
        return {"ultimo": None, "ultimo_texto": None, "ultimo_emisor": None}
    return {
        "ultimo": m.creado_en.strftime("%Y-%m-%d %H:%M") if m.creado_en else None,
        "ultimo_texto": m.texto[:80],
        "ultimo_emisor": m.emisor,
    }


@router.get("/resumen")
def resumen_conversaciones(
    db: Session = Depends(get_db),
    x_admin_token: str = Header(default=None),
    x_user_token: str = Header(default=None),
):
    """La lista de chats: con quién, el último mensaje y cuánto sin leer.

    Supervisor: TODOS los insumistas activos, tengan o no mensajes —desde aquí también
    se empieza una conversación, y una lista que solo muestre a quien ya escribió no
    deja empezar ninguna—, más el último aviso general.

    Insumista: además del `sin_leer` de siempre, `chats`: un hilo por supervisor que
    le haya escrito, el de «Supervisión» general y el de avisos. Va en `chats` y no en
    `conversaciones` para no cruzarse con lo que una tablet 1.7.1 espera de ese nombre.
    """
    emisor, sup_propio, propio = _quien_escribe(db, x_admin_token, x_user_token)
    M = MensajeBodegaDB
    desde = datetime.now() - timedelta(days=30)

    ultima_difusion = db.query(M).filter(M.insumista_id.is_(None)).order_by(
        M.creado_en.desc(), M.id.desc()).first()

    if emisor == "INSUMISTA":
        mios = db.query(M).filter(M.insumista_id == propio, M.creado_en >= desde).order_by(
            M.creado_en.desc(), M.id.desc()).all()
        ultimos = _ultimo_por_clave(mios, lambda m: m.supervisor_id or 0)
        no_leidos = dict(
            db.query(M.supervisor_id, func.count(M.id)).filter(
                M.emisor == "SUPERVISOR", M.leido_en.is_(None), M.insumista_id == propio,
            ).group_by(M.supervisor_id).all()
        )
        sin_leer_avisos = db.query(func.count(M.id)).filter(
            M.emisor == "SUPERVISOR", M.leido_en.is_(None), M.insumista_id.is_(None),
        ).scalar() or 0

        sup_ids = [k for k in ultimos if k] + [k for k in no_leidos if k and k not in ultimos]
        nombres = {a.id: a.username for a in db.query(AdministradorDB).filter(
            AdministradorDB.id.in_(sup_ids))} if sup_ids else {}

        chats = [
            {"tipo": "SUPERVISOR", "supervisor_id": sid, "nombre": nombres.get(sid, "Supervisor"),
             "sin_leer": int(no_leidos.get(sid, 0)), **_resumen_ultimo(ultimos.get(sid))}
            for sid in sup_ids
        ]
        # Un mensaje de supervisor sin `supervisor_id` (su cuenta no se encontró al
        # escribir) cae aquí: sin eso el total de no leídos bajaría respecto al de antes.
        chats.append({"tipo": "GENERAL", "supervisor_id": 0, "nombre": "Supervisión",
                      "sin_leer": int(no_leidos.get(None, 0)), **_resumen_ultimo(ultimos.get(0))})
        chats.append({"tipo": "DIFUSION", "supervisor_id": None, "nombre": "Avisos a toda la bodega",
                      "sin_leer": int(sin_leer_avisos), **_resumen_ultimo(ultima_difusion)})

        return {"yo": emisor, "sin_leer": int(sum(c["sin_leer"] for c in chats)), "chats": chats}

    visibles = or_(M.supervisor_id == sup_propio, M.supervisor_id.is_(None))
    conteos = dict(
        db.query(M.insumista_id, func.count(M.id))
        .filter(M.emisor == "INSUMISTA", M.leido_en.is_(None), visibles)
        .group_by(M.insumista_id).all()
    )
    recientes = (
        db.query(M)
        .filter(M.insumista_id.isnot(None), M.creado_en >= desde, visibles)
        .order_by(M.creado_en.desc(), M.id.desc())
        .all()
    )
    ultimos = _ultimo_por_clave(recientes, lambda m: m.insumista_id)
    de_turno = {u.id for _, u in sesiones_usuario.de_turno(db)}

    usuarios = db.query(UsuarioDB).filter(
        UsuarioDB.activo.is_(True), UsuarioDB.rol.ilike("%insumista%")
    ).order_by(UsuarioDB.nombre.asc()).all()

    conversaciones = [
        {
            "insumista_id": u.id,
            "nombre": u.nombre,
            "rol": u.rol,
            "de_turno": u.id in de_turno,
            "sin_leer": int(conteos.get(u.id, 0)),
            **_resumen_ultimo(ultimos.get(u.id)),
        }
        for u in usuarios
    ]
    return {
        "yo": emisor,
        "sin_leer": int(sum(c["sin_leer"] for c in conversaciones)),
        "conversaciones": conversaciones,
        "difusion": _resumen_ultimo(ultima_difusion),
    }
