"""Prueba de extremo a extremo de los avisos del ciclo de vida de un pedido.

Abre el WebSocket del insumista (como hace la tablet), recorre el ciclo completo por
HTTP y comprueba qué eventos llegan realmente al canal. Es la única forma de verificar
esto: el endpoint responde 200 aunque el aviso se pierda por el camino, que es
exactamente lo que pasaba con la corrutina sin esperar.

    venv/bin/python _prueba_ws_pedidos.py
"""

import asyncio
import json
import sys

import urllib.request
import websockets

BASE = "http://127.0.0.1:8099/api"
WS = "ws://127.0.0.1:8099/api/ws/insumos/EMPAQUE"
SESION = 1003
INSUMO = 7      # Botella 1 LT, bodega EMPAQUE
INSUMISTA = 2

ESPERADOS = ["nuevo_pedido", "pedido_aceptado", "pedido_entregado", "operario_confirmo_recepcion"]


def pedir(metodo, ruta, cuerpo):
    req = urllib.request.Request(
        BASE + ruta,
        data=json.dumps(cuerpo).encode(),
        headers={"Content-Type": "application/json"},
        method=metodo,
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode())


async def ciclo():
    # Pausa corta antes de cada paso: el aviso al insumista sale por BackgroundTasks,
    # o sea DESPUÉS de que el endpoint haya respondido.
    await asyncio.sleep(0.6)
    rid = "ws-test-%d" % int(asyncio.get_running_loop().time() * 1000)
    p = pedir("POST", "/insumos/pedido_dinamico", {
        "sesion_id": SESION, "detalle_pedido": "prueba ws", "cantidad": 2,
        "insumo_id": INSUMO, "request_id": rid,
    })
    pid = p["id"]
    await asyncio.sleep(0.8)
    pedir("PUT", "/insumos/aceptar", {"pedido_id": pid, "insumista_id": INSUMISTA})
    await asyncio.sleep(0.8)
    pedir("PUT", "/insumos/entregar", {"pedido_id": pid, "insumista_id": INSUMISTA, "cantidad_entregada": 2})
    await asyncio.sleep(0.8)
    pedir("PUT", "/insumos/confirmar_recepcion", {"pedido_id": pid, "sesion_id": SESION, "cantidad_recibida": 2})
    await asyncio.sleep(1.2)
    return pid


async def main():
    recibidos = []
    async with websockets.connect(WS) as ws:
        tarea = asyncio.create_task(ciclo())
        try:
            while not tarea.done() or True:
                try:
                    crudo = await asyncio.wait_for(ws.recv(), timeout=1.5)
                except asyncio.TimeoutError:
                    if tarea.done():
                        break
                    continue
                msg = json.loads(crudo)
                recibidos.append(msg.get("evento"))
                print("  <- %-28s pedido %s" % (msg.get("evento"), msg.get("solicitud_id")))
        finally:
            pid = await tarea

    print("\nEventos recibidos por el canal del insumista: %d" % len(recibidos))
    faltan = [e for e in ESPERADOS if e not in recibidos]
    for e in ESPERADOS:
        print("  %s %s" % ("OK " if e in recibidos else "NO ", e))
    if faltan:
        print("\nFALTAN: %s" % ", ".join(faltan))
        return 1
    print("\nTodos los eventos del ciclo llegaron al canal. Pedido de prueba: %s" % pid)
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
