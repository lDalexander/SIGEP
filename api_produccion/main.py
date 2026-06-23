"""
Punto de entrada principal (Entrypoint) de la API SIGEP.
Configura FastAPI, middlewares, y registra todos los routers de la aplicación.
"""
import os
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from datetime import datetime
import asyncio
from contextlib import asynccontextmanager

# Importar configuración de base de datos y modelos
from database import engine, Base, logger
import models
from tasks import garbage_collector_turnos

# Importar routers
from routers import dashboard, operaciones, insumos, auth, reportes, supervisor, websocket_insumos, dispositivos, tablets, mantenimiento, admin

# Crear las tablas en la base de datos al iniciar
Base.metadata.create_all(bind=engine)
logger.info("Tablas de la base de datos verificadas/creadas.")


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Arrancar la tarea en segundo plano del Garbage Collector
    task = asyncio.create_task(garbage_collector_turnos())
    yield
    # Al apagar la app, cancelamos la tarea amablemente
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        logger.info("♻️ Garbage Collector detenido de forma segura.")

# Inicializar la aplicación FastAPI
app = FastAPI(
    title="SIGEP — Control de Producción Detcuador",
    description="API robusta y modular para el sistema de control de producción.",
    version="2.1.0", # Bump version due to architectural rewrite
    docs_url="/docs",
    redoc_url="/redoc",
    lifespan=lifespan
)

# Configurar CORS para permitir peticiones desde cualquier origen (Frontend React)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Registrar los Routers (Módulos de la API)
app.include_router(dashboard.router)
app.include_router(operaciones.router)
app.include_router(insumos.router)
app.include_router(auth.router)
app.include_router(reportes.router)
app.include_router(supervisor.router)
app.include_router(websocket_insumos.router, prefix="/api")
app.include_router(dispositivos.router)
app.include_router(tablets.router)
app.include_router(mantenimiento.router)
app.include_router(admin.router)

# Ruta absoluta al directorio static
STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")
os.makedirs(STATIC_DIR, exist_ok=True)

# Montar la carpeta estática para servir el APK (OTA)
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

@app.get("/api/config/version", tags=["Sistema"])
def get_version():
    """Endpoint para el sistema OTA (Over-The-Air) de la app Android.

    Actualiza estos valores en cada release del APK. `obligatorio` es un campo
    extra que la app ignora si no lo usa.

    >>> OTA DESHABILITADO TEMPORALMENTE <<<
    Se anuncia un version_code bajo y obligatorio=False para que ninguna tablet
    interprete que hay actualización disponible mientras se prepara el rollout
    remoto. Para REACTIVAR la actualización, restaurar los valores reales:
        "version_code": 3,
        "version_name": "0.16.5",
        "obligatorio": True,
    """
    return {
        "version_code": 1,
        "version_name": "0.16.5",
        "url_descarga": "/static/sigep_latest.apk",
        "obligatorio": False,
    }

# @app.get("/api/health", tags=["Sistema"])
# def health():
#   """Endpoint para verificar el estado de salud de la API."""
#    return {
#        "status": "ok", 
#        "timestamp": datetime.now().isoformat(),
#        "version": app.version
#    }

logger.info("🚀 API SIGEP iniciada correctamente con arquitectura modular.")