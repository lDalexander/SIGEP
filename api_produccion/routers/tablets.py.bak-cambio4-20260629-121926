"""
Router para sincronización y monitoreo de tablets.

Cada tablet Android:
  1. Hace POST /api/tablets/heartbeat cada N segundos (~30s) reportando su estado
     y la cantidad de pendientes en su cache local.
  2. (Opcional) Mantiene un WebSocket abierto en /ws/tablets/{device_id} para
     recibir señales de sync en tiempo real.

La web:
  - GET  /api/tablets/estado          -> lista todas las tablets
  - POST /api/tablets/sincronizar/{device_id}  -> pide sync a una tablet
  - POST /api/tablets/sincronizar_todas        -> pide sync a todas

La señal de "sync" se entrega por dos vías (la primera que aplique):
  a) Push inmediato vía WebSocket si la tablet tiene el WS abierto.
  b) Flag persistente en BD; en el siguiente heartbeat la tablet recibe la orden
     y procede a vaciar su cache local.
"""
import asyncio
from datetime import datetime
from typing import Dict

from fastapi import APIRouter, Depends, HTTPException, WebSocket, WebSocketDisconnect
from sqlalchemy.orm import Session

from database import get_db, logger
from models import EstadoTabletDB
from schemas import (
    HeartbeatTabletRequest,
    HeartbeatTabletResponse,
    EstadoTabletResponse,
    SincronizarTabletResponse,
    SincronizarTodasResponse,
)

router = APIRouter(prefix="/api/tablets", tags=["Tablets / Sincronización"])

UMBRAL_OFFLINE_SEGUNDOS = 60  # si no hay heartbeat en este tiempo, se considera offline


class TabletConnectionManager:
    """Conexiones WebSocket activas, indexadas por device_id."""

    def __init__(self):
        self.connections: Dict[str, WebSocket] = {}
        self.main_loop = None

    async def connect(self, websocket: WebSocket, device_id: str):
        if self.main_loop is None:
            self.main_loop = asyncio.get_running_loop()
        await websocket.accept()
        # Si ya había una conexión previa, la cerramos
        previa = self.connections.get(device_id)
        if previa is not None:
            try:
                await previa.close()
            except Exception:
                pass
        self.connections[device_id] = websocket

    def disconnect(self, device_id: str, websocket: WebSocket | None = None):
        actual = self.connections.get(device_id)
        if actual is None:
            return
        if websocket is not None and actual is not websocket:
            return
        self.connections.pop(device_id, None)

    async def send_sync(self, device_id: str) -> bool:
        ws = self.connections.get(device_id)
        if ws is None:
            return False
        try:
            await ws.send_json({"tipo": "sync_now", "timestamp": datetime.now().isoformat()})
            return True
        except Exception:
            self.disconnect(device_id, ws)
            return False


tablet_manager = TabletConnectionManager()


def _formatear_estado(tablet: EstadoTabletDB) -> EstadoTabletResponse:
    ahora = datetime.now()
    segundos = None
    en_linea = False
    if tablet.ultimo_heartbeat is not None:
        segundos = int((ahora - tablet.ultimo_heartbeat).total_seconds())
        en_linea = segundos <= UMBRAL_OFFLINE_SEGUNDOS and bool(tablet.en_linea_reportado)

    return EstadoTabletResponse(
        device_id=tablet.device_id,
        nombre=tablet.nombre,
        maquina=tablet.maquina,
        pendientes=tablet.pendientes or 0,
        ultimo_heartbeat=tablet.ultimo_heartbeat.isoformat() if tablet.ultimo_heartbeat else None,
        ultima_sincronizacion=tablet.ultima_sincronizacion.isoformat() if tablet.ultima_sincronizacion else None,
        en_linea=en_linea,
        segundos_desde_heartbeat=segundos,
    )


@router.post("/heartbeat", response_model=HeartbeatTabletResponse)
def heartbeat(datos: HeartbeatTabletRequest, db: Session = Depends(get_db)):
    """La tablet reporta su estado. Si hay sync pendiente, se lo notificamos en la respuesta."""
    try:
        tablet = db.query(EstadoTabletDB).filter(EstadoTabletDB.device_id == datos.device_id).first()
        sync_solicitada_previa = False

        if tablet is None:
            tablet = EstadoTabletDB(
                device_id=datos.device_id,
                nombre=datos.nombre,
                maquina=datos.maquina,
                pendientes=datos.pendientes,
                en_linea_reportado=datos.en_linea,
                ultimo_heartbeat=datetime.now(),
            )
            db.add(tablet)
        else:
            sync_solicitada_previa = bool(tablet.sync_solicitada)
            tablet.nombre = datos.nombre or tablet.nombre
            tablet.maquina = datos.maquina or tablet.maquina
            tablet.pendientes = datos.pendientes
            tablet.en_linea_reportado = datos.en_linea
            tablet.ultimo_heartbeat = datetime.now()
            # Si la tablet reporta 0 pendientes, asumimos que ya sincronizó
            if datos.pendientes == 0:
                tablet.ultima_sincronizacion = datetime.now()
            # Consumimos la señal de sync (one-shot)
            if sync_solicitada_previa:
                tablet.sync_solicitada = False

        db.commit()
        return HeartbeatTabletResponse(
            mensaje="Heartbeat recibido",
            sync_solicitada=sync_solicitada_previa,
        )
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/tablets/heartbeat: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.get("/estado", response_model=list[EstadoTabletResponse])
def listar_estado(db: Session = Depends(get_db)):
    """Lista el estado de todas las tablets registradas."""
    tablets = db.query(EstadoTabletDB).order_by(EstadoTabletDB.maquina.is_(None), EstadoTabletDB.maquina.asc()).all()
    return [_formatear_estado(t) for t in tablets]


def _solicitar_sync(tablet: EstadoTabletDB) -> SincronizarTabletResponse:
    """Intenta enviar la señal por WS; si no, deja el flag en BD para el próximo heartbeat."""
    # Vía A: WebSocket inmediato
    enviado_por_ws = False
    if tablet_manager.main_loop and tablet_manager.main_loop.is_running():
        future = asyncio.run_coroutine_threadsafe(
            tablet_manager.send_sync(tablet.device_id),
            tablet_manager.main_loop,
        )
        try:
            enviado_por_ws = future.result(timeout=2)
        except Exception:
            enviado_por_ws = False

    # Vía B: flag persistente (también si WS funcionó, para garantizar entrega)
    tablet.sync_solicitada = True

    if enviado_por_ws:
        motivo = "Señal enviada por WebSocket"
    else:
        motivo = "Tablet sin WS activo; se entregará en el próximo heartbeat"

    return SincronizarTabletResponse(
        device_id=tablet.device_id,
        enviada=True,
        motivo=motivo,
    )


@router.post("/sincronizar/{device_id}", response_model=SincronizarTabletResponse)
def sincronizar_tablet(device_id: str, db: Session = Depends(get_db)):
    """Pide a una tablet específica que sincronice sus pendientes."""
    tablet = db.query(EstadoTabletDB).filter(EstadoTabletDB.device_id == device_id).first()
    if tablet is None:
        raise HTTPException(status_code=404, detail="Tablet no registrada")
    try:
        resultado = _solicitar_sync(tablet)
        db.commit()
        logger.info(f"🔄 Sync solicitada a tablet {device_id} — {resultado.motivo}")
        return resultado
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/tablets/sincronizar: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.post("/sincronizar_todas", response_model=SincronizarTodasResponse)
def sincronizar_todas(db: Session = Depends(get_db)):
    """Pide a TODAS las tablets registradas que sincronicen sus pendientes."""
    tablets = db.query(EstadoTabletDB).all()
    detalle: list[SincronizarTabletResponse] = []
    try:
        for t in tablets:
            detalle.append(_solicitar_sync(t))
        db.commit()
        enviadas = sum(1 for d in detalle if d.enviada)
        logger.info(f"🔄 Sync masiva: {enviadas}/{len(tablets)} tablets notificadas")
        return SincronizarTodasResponse(
            total=len(tablets),
            enviadas=enviadas,
            detalle=detalle,
        )
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/tablets/sincronizar_todas: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.websocket("/ws/tablets/{device_id}")
async def websocket_tablet(websocket: WebSocket, device_id: str):
    """
    Canal push para tablets. La tablet abre este socket al iniciar la app y se
    queda escuchando mensajes del tipo {"tipo": "sync_now"}.
    Puede enviar pings periódicos como texto para mantener viva la conexión.
    """
    await tablet_manager.connect(websocket, device_id)
    try:
        while True:
            # Esperamos mensajes (pings, acks); no procesamos contenido específico aún
            await websocket.receive_text()
    except WebSocketDisconnect:
        tablet_manager.disconnect(device_id, websocket)
    except Exception:
        tablet_manager.disconnect(device_id, websocket)
