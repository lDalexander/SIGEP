import asyncio
import websockets

async def test_ws():
    uri = "ws://localhost:8000/ws/insumos/EMPAQUE"
    print(f"Conectando a {uri}...")
    try:
        async with websockets.connect(uri) as websocket:
            print("¡Conexión WebSocket exitosa al tipo EMPAQUE!")
            # We can wait for a bit
            await asyncio.sleep(1)
            print("Cerrando conexión.")
    except Exception as e:
        print(f"Error de conexión: {e}")

asyncio.run(test_ws())
