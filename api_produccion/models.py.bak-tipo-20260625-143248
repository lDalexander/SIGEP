"""
Este módulo define los modelos de datos (tablas) utilizando SQLAlchemy.
Cada clase representa una tabla en la base de datos MySQL.
"""
from sqlalchemy import Column, Integer, BigInteger, String, DateTime, Float, Boolean, Date, Text, ForeignKey, UniqueConstraint, func
from sqlalchemy.orm import relationship
from datetime import datetime
from database import Base

class OperadorDB(Base):
    """Tabla para registrar a los operadores de las máquinas."""
    __tablename__ = "operadores"
    id = Column(Integer, primary_key=True, index=True)
    nombre = Column(String(150), unique=True)
    activo = Column(Boolean, default=True)

class UsuarioDB(Base):
    """Tabla para Insumistas y Supervisores (Requiere Login)"""
    __tablename__ = "usuarios"
    id = Column(Integer, primary_key=True, index=True)
    nombre = Column(String(100), unique=True)
    pin = Column(String(10)) # PIN de 4 dígitos para login rápido en planta
    rol = Column(String(50)) # Ej: "Insumista Empaque", "Insumista Granel", "Supervisor"
    activo = Column(Boolean, default=True)

class MaquinaDB(Base):
    """Tabla que define las máquinas disponibles en la planta."""
    __tablename__ = "maquinas"
    id = Column(Integer, primary_key=True, index=True)
    nombre = Column(String(100), unique=True)
    activa = Column(Boolean, default=True)

class MaquinaProductoDB(Base):
    """Jerarquía máquina → marca → presentación.

    Define QUÉ puede producir cada máquina: una fila por cada combinación válida
    (máquina, marca, presentación). La fragancia (Floral/Limón) es universal y NO
    forma parte de la jerarquía. Es la fuente de verdad que consume la app Android
    al iniciar turno (filtra los selectores) y la valida el backend.

    Se usa texto para marca/presentación (coherente con `recetas_productos` y la
    respuesta de /api/maquinas que ya consume la app). UNIQUE evita duplicados.
    """
    __tablename__ = "maquina_productos"
    __table_args__ = (
        UniqueConstraint("maquina_id", "marca", "presentacion", name="uq_maquina_marca_presentacion"),
    )
    id = Column(Integer, primary_key=True, index=True)
    maquina_id = Column(Integer, ForeignKey("maquinas.id"), index=True, nullable=False)
    marca = Column(String(100), nullable=False)
    presentacion = Column(String(100), nullable=False)
    activo = Column(Boolean, default=True)

class MarcaDB(Base):
    """Catálogo maestro de marcas (ULTREX, HIT, COMISARIATO, TORBELLINO, PQP...).

    Tabla creada manualmente en MySQL; el modelo la mapea para poder gestionarla
    desde la zona admin. Las marcas se asignan a máquinas vía `maquina_productos`."""
    __tablename__ = "marcas"
    id = Column(Integer, primary_key=True, index=True)
    nombre = Column(String(100), unique=True)
    activa = Column(Boolean, default=True)

class PresentacionDB(Base):
    """Catálogo maestro de presentaciones / gramajes (100 GR, 1 KG, 25 KG...)."""
    __tablename__ = "presentaciones"
    id = Column(Integer, primary_key=True, index=True)
    nombre = Column(String(100), unique=True)
    activa = Column(Boolean, default=True)

class InsumoDB(Base):
    """Catálogo de insumos (materiales) que se pueden solicitar a bodega."""
    __tablename__ = "insumos"
    id = Column(Integer, primary_key=True, index=True)
    nombre = Column(String(150), unique=True)
    categoria = Column(String(50))        # Ej: "Empaque Plástico", "Cartón"
    unidad_medida = Column(String(20))    # Ej: "Unidades", "Rollos", "Kg"
    activo = Column(Boolean, default=True)

class RecetaProductoDB(Base):
    """BOM (Bill of Materials): qué insumos pertenecen a cada combinación de producto."""
    __tablename__ = "recetas_productos"
    id = Column(Integer, primary_key=True, index=True)
    marca = Column(String(100))
    presentacion = Column(String(100))
    fragancia = Column(String(100))
    insumo_id = Column(Integer, index=True)

class SesionTrabajoDB(Base):
    """Registro de cada turno/sesión de trabajo de un operador en una máquina."""
    __tablename__ = "sesiones_trabajo"
    id = Column(Integer, primary_key=True, index=True)
    tipo = Column(String(50))
    maquina = Column(String(100))
    operador = Column(String(150))
    marca = Column(String(100))
    presentacion = Column(String(100))
    fragancia = Column(String(100))
    inicio_turno = Column(DateTime, default=lambda: datetime.now())
    fin_turno = Column(DateTime, nullable=True)
    duracion_minutos = Column(Float, nullable=True)
    observaciones = Column(String(255), nullable=True)
    request_id = Column(String(50), unique=True, index=True, nullable=True)

class PalletDB(Base):
    """Registro de los pallets producidos asociados a una sesión de trabajo."""
    __tablename__ = "registro_pallets"
    id = Column(Integer, primary_key=True, index=True)
    session_id = Column(Integer, index=True)
    cantidad_pacas = Column(Integer)
    fecha_hora = Column(DateTime, default=lambda: datetime.now())
    request_id = Column(String(50), unique=True, index=True, nullable=True)

class ParoMaquinaDB(Base):
    """Registro de los tiempos muertos o paros de máquina durante una sesión."""
    __tablename__ = "paros_maquina"
    id = Column(Integer, primary_key=True, index=True)
    session_id = Column(Integer, index=True)
    motivo = Column(String(255))
    inicio_paro = Column(DateTime, default=lambda: datetime.now())
    fin_paro = Column(DateTime, nullable=True)
    duracion_segundos = Column(Float, nullable=True)

class PedidoBodegaDB(Base):
    """Tabla exclusiva para el MES: Solicitudes de operarios al Insumista"""
    __tablename__ = "pedidos_bodega"
    id = Column(Integer, primary_key=True, index=True)
    session_id = Column(Integer, index=True)
    detalle_pedido = Column(String(255))
    cantidad_solicitada = Column(Integer)
    cantidad_entregada = Column(Integer, nullable=True)   # lo que el insumista dice que entregó
    cantidad_recibida = Column(Integer, nullable=True)    # lo que el operario dice que recibió
    
    # --- LAS NUEVAS COLUMNAS PARA EL MODELO UBER ---
    categoria = Column(String(50)) # "Empaque" o "Granel" (Para enrutar la notificación)
    insumista_id = Column(Integer, nullable=True) # ID del Insumista que aceptó el pedido
    
    estado = Column(String(50), default="Pendiente") # Pendiente -> En Camino -> Entregado_Insumista -> Entregado
    fecha_solicitud = Column(DateTime, default=lambda: datetime.now())
    fecha_aceptacion = Column(DateTime, nullable=True)
    fecha_entrega = Column(DateTime, nullable=True)
    fecha_confirmacion = Column(DateTime, nullable=True)

class EntregaProactivaDB(Base):
    """Entregas de insumos registradas por el insumista sin pedido previo (planeación)."""
    __tablename__ = "entregas_proactivas"
    id = Column(Integer, primary_key=True, index=True)
    insumista_id = Column(Integer, index=True)
    tipo_producto = Column(String(50))
    insumo = Column(String(255))
    cantidad = Column(Integer)
    maquina = Column(String(100))
    observaciones = Column(String(500), nullable=True)
    foto_path = Column(String(500), nullable=True)
    fecha_hora = Column(DateTime, default=lambda: datetime.now())

class AdministradorDB(Base):
    """Tabla para administradores del sistema."""
    __tablename__ = "administradores"

    # Columnas para el login de administradores.
    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(50), unique=True, nullable=False)
    password = Column(String(255), nullable=False)
    nivel_acceso = Column(String(20), default="SUPERADMIN")
    activo = Column(Boolean, default=True)

class DispositivoFCMDB(Base):
    """Tokens FCM de dispositivos Android para notificaciones push."""
    __tablename__ = "dispositivos_fcm"
    id = Column(Integer, primary_key=True, index=True)
    token = Column(String(255), unique=True, nullable=False, index=True)
    plataforma = Column(String(20), nullable=False, default="android")
    usuario_id = Column(Integer, nullable=True)
    activo = Column(Boolean, default=True)
    creado_en = Column(DateTime, default=lambda: datetime.now())
    actualizado_en = Column(DateTime, default=lambda: datetime.now(), onupdate=lambda: datetime.now())


class EstadoTabletDB(Base):
    """Estado en tiempo real de cada tablet (heartbeat + pendientes en cache local)."""
    __tablename__ = "estado_tablets"
    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String(100), unique=True, nullable=False, index=True)
    nombre = Column(String(150), nullable=True)
    maquina = Column(String(100), nullable=True)
    pendientes = Column(Integer, default=0)
    ultimo_heartbeat = Column(DateTime, default=lambda: datetime.now(), onupdate=lambda: datetime.now())
    ultima_sincronizacion = Column(DateTime, nullable=True)
    en_linea_reportado = Column(Boolean, default=True)
    sync_solicitada = Column(Boolean, default=False)


# ============================================================================
# CHECKLIST DE MANTENIMIENTO (ENTRADA / SALIDA de turno)
# ============================================================================
# Estas tablas YA existen en MySQL (creadas manualmente). Los modelos deben
# mapearlas EXACTAMENTE; no cambies nombres de columnas ni tipos. La app Android
# está congelada contra este contrato.

class MantenimientoChecklistDB(Base):
    """Cabecera del checklist de mantenimiento que envía la tablet (ENTRADA/SALIDA).

    Idempotente por `request_id` (UNIQUE): un mismo request_id que llega más de
    una vez NO crea un registro duplicado. `fecha_turno` y `codigo_turno` los
    recalcula el servidor con su reloj (America/Guayaquil); `fecha`/`hora` son el
    reloj de la tablet y se guardan tal cual los envía.
    """
    __tablename__ = "mantenimiento_checklist"
    id = Column(BigInteger, primary_key=True, index=True)
    request_id = Column(String(64), unique=True, index=True, nullable=False)
    maquina = Column(String(100), nullable=False)
    operador = Column(String(100), nullable=False)
    momento = Column(String(20), nullable=False)        # "ENTRADA" | "SALIDA"
    fecha_turno = Column(Date, nullable=False)            # recalculado por el server
    codigo_turno = Column(String(50), nullable=False)     # "DIA" | "NOCHE" (server)
    fecha = Column(Date, nullable=False)                  # reloj de la tablet (yyyy-MM-dd)
    hora = Column(String(20), nullable=False)             # reloj de la tablet (HH:mm)
    supervisor = Column(String(150), nullable=True)       # requiere ALTER TABLE (ver SQL)
    comentarios = Column(Text, nullable=True)             # requiere ALTER TABLE (ver SQL)
    creado_en = Column(DateTime, server_default=func.current_timestamp())

    items = relationship(
        "MantenimientoChecklistItemDB",
        back_populates="checklist",
        cascade="all, delete-orphan",
    )


class MantenimientoChecklistItemDB(Base):
    """Ítem individual (etiqueta + marcado) de un checklist de mantenimiento."""
    __tablename__ = "mantenimiento_checklist_item"
    id = Column(BigInteger, primary_key=True, index=True)
    checklist_id = Column(BigInteger, ForeignKey("mantenimiento_checklist.id"), index=True, nullable=False)
    etiqueta = Column(String(255), nullable=False)
    marcado = Column(Boolean, nullable=False)             # TINYINT en MySQL

    checklist = relationship("MantenimientoChecklistDB", back_populates="items")