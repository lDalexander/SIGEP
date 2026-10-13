"""
Servicio de notificaciones push vía Firebase Cloud Messaging.

Envía mensajes data-only (sin `notification=`) para que la app Android
invoque siempre `onMessageReceived`, también cuando está en background
o cerrada.
"""
import os
import logging

import firebase_admin
from firebase_admin import credentials, messaging

from database import SessionLocal
from models import DispositivoFCMDB, UsuarioDB

log = logging.getLogger("sigep.fcm")


# Inicialización idempotente — se ejecuta una sola vez al importar.
if not firebase_admin._apps:
    cred_path = os.getenv("FIREBASE_CREDENTIALS_PATH")
    if cred_path and os.path.exists(cred_path):
        cred = credentials.Certificate(cred_path)
        firebase_admin.initialize_app(cred)
        log.info(f"Firebase Admin SDK inicializado ({cred_path})")
    else:
        log.warning(
            "FIREBASE_CREDENTIALS_PATH no seteado o ruta inexistente — "
            "FCM deshabilitado (los pedidos seguirán llegando por WebSocket)."
        )


def notificar_usuario_por_fcm(usuario_id: int, titulo: str, cuerpo: str, tipo: str = "MENSAJE_BODEGA"):
    """FCM data-only a las tablets de UN usuario concreto.

    `notificar_insumistas_por_fcm` reparte por bodega, que es lo que quiere un pedido:
    lo puede tomar cualquiera del turno. Un mensaje del supervisor no — va dirigido a
    una persona, y mandarlo a toda la bodega lo convertiría en un tablón de anuncios.

    Best-effort, como el otro: el mensaje ya está guardado y la app lo ve al refrescar
    aunque el push se pierda. Abre su propia sesión de BD porque FastAPI cierra la del
    request antes de ejecutar las BackgroundTasks.
    """
    if not firebase_admin._apps:
        log.warning("FCM no inicializado, saltando envío")
        return
    if not usuario_id:
        return

    db = SessionLocal()
    try:
        filas = (
            db.query(DispositivoFCMDB.token)
            .filter(
                DispositivoFCMDB.activo.is_(True),
                DispositivoFCMDB.usuario_id == usuario_id,
            )
            .all()
        )
        tokens = [t[0] for t in filas if t[0]]
        if not tokens:
            log.info(f"Sin tokens FCM para el usuario {usuario_id}")
            return

        mensaje = messaging.MulticastMessage(
            data={"tipo": tipo, "titulo": titulo, "cuerpo": cuerpo},
            android=messaging.AndroidConfig(priority="high", ttl=300),
            tokens=tokens,
        )
        respuesta = messaging.send_each_for_multicast(mensaje)
        log.info(
            f"📲 FCM dirigido al usuario {usuario_id}: "
            f"{respuesta.success_count} éxitos, {respuesta.failure_count} fallos"
        )
    except Exception as e:
        log.error(f"Error al enviar FCM al usuario {usuario_id}: {e}")
    finally:
        db.close()


def notificar_insumistas_por_fcm(
    tipo_insumo: str,         # "GRANEL" o "EMPAQUE"
    titulo: str,
    cuerpo: str,
    solicitud_id: int,
):
    """
    Envía un FCM data-only a todas las tablets registradas para insumistas
    del tipo indicado. Best-effort: si falla no rompe nada porque el pedido
    ya quedó persistido y el WS ya disparó.

    Abre su propia sesión de BD: NO recibe la sesión del request, porque
    FastAPI cierra esa sesión antes de ejecutar las BackgroundTasks.
    """
    if not firebase_admin._apps:
        log.warning("FCM no inicializado, saltando envío")
        return

    filtro_rol = "%Granel%" if tipo_insumo.upper() == "GRANEL" else "%Empaque%"

    db = SessionLocal()
    try:
        filas = (
            db.query(DispositivoFCMDB.token)
            .join(UsuarioDB, UsuarioDB.id == DispositivoFCMDB.usuario_id)
            .filter(
                DispositivoFCMDB.activo.is_(True),
                UsuarioDB.activo.is_(True),
                UsuarioDB.rol.ilike(filtro_rol),
            )
            .all()
        )
        tokens = [t[0] for t in filas if t[0]]

        if not tokens:
            log.info(f"Sin tokens FCM activos para tipo {tipo_insumo}")
            return

        mensaje = messaging.MulticastMessage(
            data={
                "tipo": "SOLICITUD_INSUMO",
                "tipo_insumo": tipo_insumo.upper(),
                "titulo": titulo,
                "cuerpo": cuerpo,
                "solicitudId": str(solicitud_id),
            },
            android=messaging.AndroidConfig(
                priority="high",
                ttl=60,
            ),
            tokens=tokens,
        )

        respuesta = messaging.send_each_for_multicast(mensaje)
        log.info(
            f"📲 FCM enviado a {len(tokens)} tablets ({tipo_insumo}): "
            f"{respuesta.success_count} éxitos, {respuesta.failure_count} fallos"
        )

        if respuesta.failure_count > 0:
            tokens_invalidos = []
            for i, r in enumerate(respuesta.responses):
                if not r.success and r.exception:
                    msg = str(r.exception).lower()
                    if (
                        "registration-token-not-registered" in msg
                        or "not a valid fcm registration token" in msg
                        or "invalid-argument" in msg
                    ):
                        tokens_invalidos.append(tokens[i])

            if tokens_invalidos:
                db.query(DispositivoFCMDB).filter(
                    DispositivoFCMDB.token.in_(tokens_invalidos)
                ).delete(synchronize_session=False)
                db.commit()
                log.info(f"🧹 {len(tokens_invalidos)} tokens FCM inválidos eliminados")

    except Exception as e:
        db.rollback()
        log.exception(f"Error enviando FCM: {e}")
    finally:
        db.close()
