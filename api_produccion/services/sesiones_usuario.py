"""Sesiones de insumistas y supervisores en las tablets (v1.7.0).

Una sola tabla (`sesiones_usuario`) resuelve tres agujeros que hasta ahora estaban
abiertos a la vez:

  a) LOGIN ÚNICO. `ModelosRed.kt` documentaba que `device_id` "permite al backend
     bloquear el segundo login del mismo usuario desde otra tablet" y que el servidor
     "auto-libera tras 9h". Nada de eso existía: `/api/login` recibía el campo y no lo
     miraba. El mismo insumista podía estar operando en dos tablets y aceptar dos veces
     el mismo pedido.

  b) AUTORIZACIÓN. `GET /api/insumos/pendientes/{rol}` recibe el rol COMO TEXTO desde
     el cliente, así que cualquiera con la URL ve y toma pedidos de cualquier bodega.
     Con el token, el servidor deduce quién es y qué bodega le toca.

  c) PRESENCIA. «Qué insumistas están de turno», que el supervisor necesita ver y que
     hoy no se puede saber.

EL TOKEN VIVE EN LA BASE, no en memoria como el `_TOKENS` del admin. Ese se vacía en
cada `systemctl restart sigep`: a un admin en su escritorio volver a loguearse le cuesta
nada, pero a un insumista con la tablet en el bolsillo a media jornada lo deja tirado,
y los despliegues son frecuentes.

LAS 9 HORAS no son un número al azar: es lo que la app ya le dice al usuario cuando le
rechaza el login ("Cierra sesión allí primero o espera 9 horas", LoginActivity.kt). El
backend se alinea con el mensaje que la tablet lleva mostrando desde la 1.5.
"""

import secrets
from datetime import datetime, timedelta

from fastapi import Header
from sqlalchemy import or_

from models import SesionUsuarioDB, UsuarioDB

# Ventana de vida de una sesión sin señales. Coincide con el texto que la app muestra.
HORAS_SESION = 9

# Motivos de cierre, para que el historial diga POR QUÉ terminó cada turno.
LOGOUT = "LOGOUT"          # el usuario cerró sesión desde la app
RELEVO = "RELEVO"          # volvió a entrar en la MISMA tablet
EXPIRACION = "EXPIRACION"  # llevaba 9 h sin dar señales y entró en otra tablet
GC = "GC"                  # lo cerró el recolector periódico


def _limite():
    return datetime.now() - timedelta(hours=HORAS_SESION)


def abiertas_de(db, usuario_id):
    """Sesiones sin cerrar de un usuario, de la más reciente a la más vieja."""
    return (
        db.query(SesionUsuarioDB)
        .filter(SesionUsuarioDB.usuario_id == usuario_id, SesionUsuarioDB.fin.is_(None))
        .order_by(SesionUsuarioDB.inicio.desc())
        .all()
    )


def cerrar(db, sesion, motivo):
    """Cierra una sesión. No hace commit: lo decide quien llama."""
    sesion.fin = datetime.now()
    sesion.cerrada_por = motivo
    return sesion


def abrir(db, usuario, device_id):
    """Crea la sesión y devuelve (sesion, token). Sin commit."""
    token = secrets.token_urlsafe(32)
    ahora = datetime.now()
    sesion = SesionUsuarioDB(
        usuario_id=usuario.id,
        device_id=(device_id or "desconocido")[:100],
        token=token,
        rol=usuario.rol,
        inicio=ahora,
        ultimo_visto=ahora,
    )
    db.add(sesion)
    return sesion, token


def conflicto_de_sesion(db, usuario, device_id):
    """¿Hay otra tablet con la sesión de este usuario? -> texto del 409, o None.

    Las sesiones de la MISMA tablet no son conflicto: volver a entrar en el mismo
    dispositivo es un relevo normal (se cambió de turno, se reinició la app). Se cierran
    con RELEVO y la nueva ocupa su lugar.

    Una sesión pasada de 9 h tampoco bloquea: se cierra por EXPIRACION. Si no, un
    insumista cuya tablet se quedó sin batería no podría entrar en otra hasta que
    alguien fuese a MySQL a mano.
    """
    propio = (device_id or "desconocido")[:100]
    limite = _limite()
    bloqueante = None

    for sesion in abiertas_de(db, usuario.id):
        if sesion.device_id == propio:
            cerrar(db, sesion, RELEVO)
        elif sesion.ultimo_visto < limite:
            cerrar(db, sesion, EXPIRACION)
        else:
            bloqueante = sesion

    if bloqueante is None:
        return None

    inactivo = int((datetime.now() - bloqueante.ultimo_visto).total_seconds() // 60)
    return (
        "%s ya tiene la sesión abierta en otra tablet (%s), con actividad hace %d min. "
        "Cierra sesión allí y vuelve a intentarlo, o espera %d horas a que se libere sola."
        % (usuario.nombre, bloqueante.device_id, inactivo, HORAS_SESION)
    )


def cerrar_por_logout(db, usuario_id, device_id):
    """Cierra la sesión de esa tablet. Devuelve cuántas cerró.

    Es idempotente: si no había ninguna abierta responde 0 y no es un error. La app
    hace logout «best-effort» y no puede quedarse encallada porque el servidor y la
    tablet no coincidan en si había sesión.
    """
    propio = (device_id or "")[:100]
    cerradas = 0
    for sesion in abiertas_de(db, usuario_id):
        # Sin device_id (cliente viejo) se cierran todas las del usuario: es lo que el
        # usuario quiso decir al pulsar «cerrar sesión».
        if not propio or sesion.device_id == propio:
            cerrar(db, sesion, LOGOUT)
            cerradas += 1
    return cerradas


def resolver_token(db, token):
    """Token -> sesión viva, refrescando `ultimo_visto`. None si no vale.

    `ultimo_visto` se refresca en CADA petición autenticada y es lo que mantiene viva
    la sesión: un insumista trabajando no la pierde a las 9 h, y uno que dejó la tablet
    tirada sí. También es el dato de «está de turno» del panel del supervisor.
    """
    if not token:
        return None
    sesion = (
        db.query(SesionUsuarioDB)
        .filter(SesionUsuarioDB.token == token, SesionUsuarioDB.fin.is_(None))
        .first()
    )
    if sesion is None:
        return None
    if sesion.ultimo_visto < _limite():
        cerrar(db, sesion, EXPIRACION)
        db.commit()
        return None
    sesion.ultimo_visto = datetime.now()
    db.commit()
    return sesion


def purgar_expiradas(db):
    """Cierra las sesiones sin señales desde hace más de 9 h. Devuelve cuántas."""
    viejas = (
        db.query(SesionUsuarioDB)
        .filter(SesionUsuarioDB.fin.is_(None), SesionUsuarioDB.ultimo_visto < _limite())
        .all()
    )
    for sesion in viejas:
        cerrar(db, sesion, GC)
    if viejas:
        db.commit()
    return len(viejas)


def de_turno(db, solo_insumistas=True):
    """Usuarios con sesión viva ahora mismo. Es el panel de presencia del supervisor."""
    q = (
        db.query(SesionUsuarioDB, UsuarioDB)
        .join(UsuarioDB, UsuarioDB.id == SesionUsuarioDB.usuario_id)
        .filter(SesionUsuarioDB.fin.is_(None), SesionUsuarioDB.ultimo_visto >= _limite())
    )
    if solo_insumistas:
        q = q.filter(or_(UsuarioDB.rol.ilike("%insumista%"), UsuarioDB.rol.ilike("%bodega%")))
    return q.order_by(SesionUsuarioDB.inicio.asc()).all()


# ──────────────────────────────────────────────────────────────────────────────
# Dependencia de FastAPI
# ──────────────────────────────────────────────────────────────────────────────

async def token_de_cabecera(x_user_token: str = Header(None)):
    """Lee `X-User-Token`. OPCIONAL a propósito mientras queden tablets 1.6.0.

    Las tablets en planta se actualizan a mano por sideload, así que durante un tiempo
    van a convivir versiones. Exigir el token hoy dejaría a media planta sin poder
    atender pedidos. Los endpoints que lo usan prefieren el token cuando viene y caen
    al comportamiento anterior cuando no, y lo dejan anotado en el log para que se vea
    cuántas tablets faltan por actualizar antes de hacerlo obligatorio.
    """
    return x_user_token
