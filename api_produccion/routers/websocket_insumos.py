import asyncio
from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from typing import Dict, List
from ws_manager import manager

router = APIRouter(tags=["WebSockets Insumos"])

@router.websocket("/ws/insumos/{tipo_insumo}")
async def websocket_endpoint(websocket: WebSocket, tipo_insumo: str):
    tipo = tipo_insumo.upper()
    
    if tipo not in ["EMPAQUE", "GRANEL"]:
        await websocket.close(code=1008)
        return
        
    await manager.connect(websocket, tipo)
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        manager.disconnect(websocket, tipo)
    except Exception:
        manager.disconnect(websocket, tipo)


def broadcast_nuevo_pedido(tipo: str, pedido_data: dict):
    """
    Función helper que se puede llamar desde endpoints síncronos (def) 
    sin bloquear la API ni requerir BackgroundTasks.
    """
    if manager.main_loop and manager.main_loop.is_running():
        # Envía la tarea asíncrona de vuelta al Event Loop principal de Uvicorn
        asyncio.run_coroutine_threadsafe(
            manager.broadcast_to_tipo(tipo, pedido_data), 
            manager.main_loop
        )
