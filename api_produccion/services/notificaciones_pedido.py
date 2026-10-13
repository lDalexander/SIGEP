"""Avisos del ciclo de vida de un pedido de insumo (v1.7.0).

EL PROBLEMA QUE RESUELVE. `BandejaInsumosActivity` escucha desde hace versiones los
eventos `operario_confirmo_recepcion`, `pedido_confirmado` y `pedido_entregado`, y el
backend **nunca los emitía**: `/entregar` y `/confirmar_recepcion` cerraban su parte del
flujo en silencio. De ahí salieron los dos parches de sondeo que hay hoy —el insumista
refrescando cada 15 s «por si el WS se cayó» y el operario cada 5 s— que machacan la API
para enterarse de algo que el servidor ya sabía.

Y el aviso estaba repartido: `/pedido_dinamico` hacía su propio `broadcast_to_tipo` más
su propio FCM, `/aceptar` otro `broadcast_to_tipo` distinto, y los otros dos nada. Cada
evento nuevo había que acordarse de enchufarlo en tres sitios.

DOS DESTINOS, PORQUE SON DOS CANALES DISTINTOS QUE YA EXISTEN:

  * El INSUMISTA escucha `/api/ws/insumos/{EMPAQUE|GRANEL}`, un canal por bodega. Es
    por donde ya llegaban `nuevo_pedido` y `pedido_aceptado`.

  * El OPERARIO no tenía ningún canal para esto, pero su tablet ya mantiene abierto el
    WebSocket de CAMBIO 4 (`/api/tablets/ws/tablets/{device_id}`, con el heartbeat de
    respaldo). Se reutiliza ese en vez de inventar transporte nuevo: la tablet se
    localiza por la máquina de la sesión, igual que hace `_push_ws_mensaje` con los
    mensajes del admin.

LOS DOS CANALES HABLAN DISTINTO y no se unifican aquí: el de insumos usa `evento` +
`solicitud_id` (`SigepWebSocketManager`) y el de tablets usa `tipo`
(`TabletsWebSocketManager`). Son contratos ya desplegados en planta; cambiarlos dejaría
sordas a las tablets que todavía no se actualizan.

ESTO NO SUSTITUYE AL SONDEO, LO RELEVA. El push es el camino rápido y el sondeo queda de
red de seguridad para cuando el WS está caído, que es precisamente cuando hace falta.
Nada aquí lanza excepciones hacia arriba: que un aviso no salga no puede tumbar la
operación que lo provocó — el pedido ya está guardado.
"""

from database import logger
from models import EstadoTabletDB, SesionTrabajoDB
from routers.tablets import enviar_payload_ws
from ws_manager import manager

# Eventos del ciclo de vida. Los nombres NO son libres: son los que las tablets ya
# tienen escritos en su `when`, así que se respetan tal cual.
NUEVO = "nuevo_pedido"
ACEPTADO = "pedido_aceptado"
ENTREGADO = "pedido_entregado"
CONFIRMADO = "operario_confirmo_recepcion"


def _avisar_insumistas(categoria, payload):
    """Canal por bodega. Se llama desde `BackgroundTasks` (es una corrutina)."""
    return manager.broadcast_to_tipo((categoria or "EMPAQUE").upper(), payload)


def _avisar_tablet_operario(db, pedido, payload):
    """Empuja a la(s) tablet(s) de la máquina del turno. True si alguna lo recibió."""
    sesion = (
        db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == pedido.session_id).first()
        if pedido.session_id else None
    )
    if sesion is None or not sesion.maquina:
        return False

    entregado = False
    for tablet in db.query(EstadoTabletDB).filter(EstadoTabletDB.maquina == sesion.maquina).all():
        try:
            if enviar_payload_ws(tablet.device_id, payload):
                entregado = True
        except Exception as e:
            # Una tablet con el socket a medio morir no puede impedir que el aviso
            # llegue a las demás ni romper la petición que lo originó.
            logger.warning(f"No se pudo avisar a la tablet {tablet.device_id}: {e}")
    return entregado


def notificar(db, evento, pedido, background_tasks=None, descripcion=None):
    """Reparte un evento de pedido a quien le corresponde.

    `background_tasks` se pasa cuando lo hay (el broadcast a insumistas es asíncrono y
    no debe bloquear la respuesta). Sin él, el aviso al insumista se omite y solo sale
    el de la tablet, que es síncrono: es el caso de los endpoints que todavía no
    reciben `BackgroundTasks`, y vale más un aviso a medias que ninguno.
    """
    try:
        payload_insumos = {
            "evento": evento,
            "solicitud_id": pedido.id,
            "descripcion": descripcion or pedido.detalle_pedido or "Actualización",
        }
        # El canal de tablets se enrutaba por `tipo`, no por `evento`: el `when` de
        # TabletsWebSocketManager mira esa clave. Se mandan las dos para que un mismo
        # payload sirva si algún día los canales se unifican.
        payload_tablet = {
            "tipo": evento,
            "evento": evento,
            "pedido_id": pedido.id,
            "estado": pedido.estado,
            "detalle": pedido.detalle_pedido,
        }

        if background_tasks is not None:
            background_tasks.add_task(_avisar_insumistas, pedido.categoria, payload_insumos)

        # Al operario solo le interesa lo que le pasa a SU pedido. `nuevo_pedido` lo
        # acaba de crear él: devolvérselo sería ruido.
        if evento != NUEVO:
            _avisar_tablet_operario(db, pedido, payload_tablet)

    except Exception as e:
        logger.error(f"Fallo al notificar «{evento}» del pedido {getattr(pedido, 'id', '?')}: {e}")
