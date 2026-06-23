import asyncio
from typing import Dict, List
from fastapi import WebSocket

class ConnectionManager:
    def __init__(self):
        self.active_connections: Dict[str, List[WebSocket]] = {
            "EMPAQUE": [], 
            "GRANEL": []
        }
        self.main_loop = None

    async def connect(self, websocket: WebSocket, tipo: str):
        if self.main_loop is None:
            self.main_loop = asyncio.get_running_loop()
            
        await websocket.accept()
        if tipo in self.active_connections:
            self.active_connections[tipo].append(websocket)

    def disconnect(self, websocket: WebSocket, tipo: str):
        if tipo in self.active_connections and websocket in self.active_connections[tipo]:
            self.active_connections[tipo].remove(websocket)

    async def broadcast_to_tipo(self, tipo: str, mensaje_dict: dict):
        if tipo in self.active_connections:
            conexiones = list(self.active_connections[tipo])
            for connection in conexiones:
                try:
                    await connection.send_json(mensaje_dict)
                except Exception:
                    self.disconnect(connection, tipo)

# 🔥 ESTO ES EL SINGLETON: Una única instancia para todo el proyecto
manager = ConnectionManager()