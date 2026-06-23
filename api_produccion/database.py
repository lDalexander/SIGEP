"""
Este módulo maneja la configuración y conexión a la base de datos MySQL.
Implementa el patrón Singleton para la sesión y utiliza SQLAlchemy.
"""
import os
import logging
from sqlalchemy import create_engine
from sqlalchemy.orm import declarative_base, sessionmaker
from dotenv import load_dotenv

# Cargar variables de entorno (por ej. DATABASE_URL)
load_dotenv()

# Configuración básica de Logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("sigep")

# URL de la base de datos (por defecto a localhost si no está en el .env)
SQLALCHEMY_DATABASE_URL = os.getenv(
    "DATABASE_URL", 
    "mysql+mysqlconnector://root:password@localhost:3306/produccion_detg"
)

# Configuración del Engine de SQLAlchemy con pool de conexiones
engine = create_engine(
    SQLALCHEMY_DATABASE_URL,
    pool_pre_ping=True,  # Verifica la conexión antes de usarla
    pool_recycle=3600,   # Recicla conexiones cada hora para evitar timeouts
    pool_size=10,        # Tamaño base del pool de conexiones
    max_overflow=20,     # Conexiones extra permitidas en picos de tráfico
)

# Fábrica de sesiones de base de datos
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

# Clase base declarativa de la que heredarán los modelos
Base = declarative_base()

def get_db():
    """
    Dependencia de FastAPI para obtener la sesión de la base de datos.
    Asegura que la conexión se cierre al finalizar cada request.
    """
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
