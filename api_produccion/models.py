"""
Este módulo define los modelos de datos (tablas) utilizando SQLAlchemy.
Cada clase representa una tabla en la base de datos MySQL.
"""
from sqlalchemy import Column, Integer, BigInteger, String, DateTime, Float, Boolean, Date, Text, ForeignKey, UniqueConstraint, func
from sqlalchemy.orm import relationship
from datetime import datetime
from database import Base

class OperadorDB(Base):
    """Tabla para registrar a los operadores de las máquinas.

    `tipo` clasifica al operario por línea (SOLIDO / LIQUIDO), con el mismo
    vocabulario que `MaquinaDB.tipo`, para que el selector de operador de la
    tablet solo muestre a los de su línea. Ver alter_operadores_tipo.sql.
    """
    __tablename__ = "operadores"
    id = Column(Integer, primary_key=True, index=True)
    nombre = Column(String(150), unique=True)
    tipo = Column(String(10), nullable=False, server_default="SOLIDO", default="SOLIDO")
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
    # Tipo de línea: "SOLIDO" | "LIQUIDO" (mayúsculas, sin tilde — estilo EMPAQUE/GRANEL).
    # Distinto del `tipo` de la sesión ("Sólido"/"Líquido"); el flujo de inicio de
    # turno filtra las máquinas por este valor. Columna añadida vía ALTER (ver CAMBIO 2).
    tipo = Column(String(20), nullable=False, default="SOLIDO")
    activa = Column(Boolean, default=True)

class ProductoDB(Base):
    """Catalogo de productos por linea de produccion.

    SOLIDO tiene uno solo (detergente en polvo); LIQUIDO tiene varios y el operario
    debe elegir cual al abrir turno, porque determina que marcas/presentaciones
    aplican. Es catalogo en BD y no una lista fija en la app: las tablets se
    actualizan a mano por sideload, asi que anadir un producto no puede exigir
    compilar, firmar y reinstalar un APK en toda la planta.
    """
    __tablename__ = "productos"
    id = Column(Integer, primary_key=True, index=True)
    nombre = Column(String(100), unique=True, nullable=False)
    tipo = Column(String(20), nullable=False)   # SOLIDO | LIQUIDO
    activo = Column(Boolean, default=True)

class MaquinaProductoDB(Base):
    """Jerarquía máquina → marca → presentación.

    Define QUÉ puede producir cada máquina: una fila por cada combinación válida
    (máquina, marca, presentación). La fragancia NO va aquí: cuelga de (máquina,
    marca) en `maquina_marca_fragancias`, porque no depende del gramaje. Es la
    fuente de verdad que consume la app Android al iniciar turno (filtra los
    selectores) y la valida el backend.

    Se usa texto para marca/presentación (coherente con `recetas_productos` y la
    respuesta de /api/maquinas que ya consume la app). UNIQUE evita duplicados.
    """
    __tablename__ = "maquina_productos"
    __table_args__ = (
        UniqueConstraint("maquina_id", "producto", "marca", "presentacion", name="uq_maq_prod_marca_pres"),
    )
    id = Column(Integer, primary_key=True, index=True)
    maquina_id = Column(Integer, ForeignKey("maquinas.id"), index=True, nullable=False)
    # Producto de la linea (ver ProductoDB). NULL = COMODIN: la fila vale para
    # cualquier producto. Es lo que evita que una jerarquia a medio configurar deje
    # una maquina sin poder abrir turno; mismo criterio que el fallback de fragancias.
    producto = Column(String(100), nullable=True)
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

class FraganciaDB(Base):
    """Catálogo maestro de fragancias (Floral, Limón...).

    La tabla ya existía en MySQL —creada a mano como `marcas` y `presentaciones`—
    pero nada la leía: la app Android llevaba su propia lista fija. Se empieza a
    usar como catálogo de la jerarquía de fragancias; su esquema no se ha tocado."""
    __tablename__ = "fragancias"
    id = Column(Integer, primary_key=True, index=True)
    nombre = Column(String(100), unique=True)
    activa = Column(Boolean, default=True)

class MaquinaMarcaFraganciaDB(Base):
    """Jerarquía (máquina, marca) → fragancia. Ver alter_fragancias_jerarquia.sql.

    Qué fragancias puede hacer cada máquina de cada marca. La fragancia era
    universal (la misma lista en las 21 tablets) hasta que la línea líquida entró
    en producción con fragancias propias por marca y por máquina.

    La presentación queda FUERA a propósito: ULTREX 1 KG y ULTREX 3 KG en la misma
    máquina llevan las mismas fragancias, así que colgarlo de `maquina_productos`
    habría multiplicado las filas a configurar sin ganar precisión.

    `activo` es baja lógica igual que en `MaquinaProductoDB`: quitarle una fragancia
    a una máquina no puede borrar el histórico de sesiones que la usaron.
    """
    __tablename__ = "maquina_marca_fragancias"
    __table_args__ = (
        UniqueConstraint("maquina_id", "marca", "fragancia", name="uq_maquina_marca_fragancia"),
    )
    id = Column(Integer, primary_key=True, index=True)
    maquina_id = Column(Integer, ForeignKey("maquinas.id"), index=True, nullable=False)
    marca = Column(String(100), nullable=False)
    fragancia = Column(String(100), nullable=False)
    activo = Column(Boolean, default=True)


class MaquinaPresentacionVelocidadDB(Base):
    """Velocidad ESTÁNDAR de (máquina, presentación), en unidades/min.

    Es el rango al que se espera que trabaje esa máquina con ese gramaje —Máquina 7 con
    1 KG entre 32 y 38, Máquina 9 con el mismo 1 KG entre 28 y 32— y existe para que la
    lectura que manda la tablet (`registro_velocidad`) se pueda leer como rápida o lenta
    en vez de como un número suelto: 30 unidades/min por sí solo no dice nada.

    La granularidad es (máquina, presentación) y NO lleva marca, por el mismo motivo por
    el que `maquina_marca_fragancias` no lleva presentación: lo que manda el ritmo es el
    gramaje y la máquina que lo llena, no la etiqueta. ULTREX 1 KG y TORBELLINO 1 KG en la
    Máquina 7 van al mismo ritmo, así que colgarlo de `maquina_productos` habría
    multiplicado las filas a configurar sin ganar precisión.

    UN RANGO Y NO UN VALOR ÚNICO: el ritmo real oscila con el material y con el operario,
    así que un objetivo puntual marcaría como desviación cualquier turno normal. `vel_min`
    es el suelo por debajo del cual hay que ir a mirar la máquina; `vel_max` el techo por
    encima del cual la lectura es sospechosa (o el operario tecleó de más).

    Se dice «unidades» y no «fundas» porque la línea líquida no llena fundas.

    `activo` es baja lógica, igual que en las otras dos jerarquías: quitar un estándar no
    puede borrar la referencia con la que se leyeron los turnos anteriores.
    """
    __tablename__ = "maquina_presentacion_velocidad"
    __table_args__ = (
        UniqueConstraint("maquina_id", "presentacion", name="uq_maquina_presentacion_velocidad"),
    )
    id = Column(Integer, primary_key=True, index=True)
    maquina_id = Column(Integer, ForeignKey("maquinas.id"), index=True, nullable=False)
    presentacion = Column(String(100), nullable=False)
    vel_min = Column(Float, nullable=False)
    vel_max = Column(Float, nullable=False)
    activo = Column(Boolean, default=True)


class InsumoDB(Base):
    """Catálogo de insumos (materiales) que se pueden solicitar a bodega.

    Desde la v1.7.0 es la ÚNICA fuente de verdad: antes convivía con dos listas
    quemadas en el APK (el modal del operario y el formulario de entrega proactiva
    del insumista), que ni coincidían entre sí ni con esta tabla.
    """
    __tablename__ = "insumos"
    id = Column(Integer, primary_key=True, index=True)
    # Clave de origen del ERP: sin ella, la segunda carga no sabe si una fila es
    # nueva o es la misma con el nombre retocado. UNIQUE pero admite varios NULL,
    # porque un insumo creado a mano desde el admin no tiene código.
    codigo_erp = Column(String(50), unique=True, nullable=True)
    nombre = Column(String(150), unique=True)
    categoria = Column(String(50))        # Ej: "Empaque Plástico", "Cartón"
    # EMPAQUE | GRANEL. Decide a qué insumista le suena la alarma. Antes eso salía
    # de buscar "granel"/"preten"/"polvo" DENTRO del texto libre del operario, así
    # que un pedido de "Lámina para polvo" se iba a Granel.
    bodega = Column(String(20), nullable=False, default="EMPAQUE")
    unidad_medida = Column(String(20))    # Ej: "Unidades", "Rollos", "Kg"
    activo = Column(Boolean, nullable=False, default=True)

class RecetaProductoDB(Base):
    """BOM (Bill of Materials): qué insumos pertenecen a cada combinación de producto.

    NULL = COMODÍN en maquina/producto/marca/presentacion/fragancia, el mismo criterio
    que `MaquinaProductoDB.producto`: la fila vale para cualquier valor de esa columna.
    Con 7 productos x 11 marcas x 36 presentaciones x 9 fragancias, una fila por
    combinación son decenas de miles que nadie mantendría a mano.
    """
    __tablename__ = "recetas_productos"
    id = Column(Integer, primary_key=True, index=True)
    # Hay diferencias que no se deducen del producto: la Máquina 7B usa sacos tejidos
    # donde las demás usan lámina en rollo. Eso era un `if` contra el literal
    # "Máquina 7B" quemado en el APK.
    maquina = Column(String(100), nullable=True)
    # Entró en la v1.7.0: la 1.6.0 metió el producto en la jerarquía del turno y el
    # BOM se quedó cruzando solo marca+presentación.
    producto = Column(String(100), nullable=True)
    marca = Column(String(100))
    presentacion = Column(String(100))
    fragancia = Column(String(100))
    insumo_id = Column(Integer, index=True)
    # Con cuánto se precarga el pedido del operario. Entero: se piden millares de
    # fundas o unidades de caja, nunca fracciones.
    cantidad_sugerida = Column(Integer, nullable=False, default=1)
    activo = Column(Boolean, nullable=False, default=True)

class SesionTrabajoDB(Base):
    """Registro de cada turno/sesión de trabajo de un operador en una máquina."""
    __tablename__ = "sesiones_trabajo"
    id = Column(Integer, primary_key=True, index=True)
    tipo = Column(String(50))
    # Producto con el que se trabajo el turno. En SOLIDO es siempre detergente en
    # polvo; en LIQUIDO distingue cloro / detergente liquido / quitamanchas / suavizante.
    producto = Column(String(100), nullable=True)
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

class VelocidadMaquinaDB(Base):
    """Velocidad a la que trabaja la máquina, en fundas/min, según la registra el operario.

    Una fila por lectura y no una columna en la sesión: el ritmo cambia durante el turno y
    lo que hace falta para medir eficiencia es la serie, no el último valor. `request_id`
    es UNIQUE por lo mismo que en los pallets — las tablets reintentan hasta que entra.
    """
    __tablename__ = "registro_velocidad"
    id = Column(Integer, primary_key=True, index=True)
    session_id = Column(Integer, index=True)
    velocidad = Column(Float)
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
    # Evidencia fotográfica OPCIONAL (2026-08-19). Se guarda la ruta servida
    # (`/static/paros/...`), nunca la imagen: mismo criterio que
    # `entregas_proactivas.foto_path`, para que los dumps de `backups/` sigan pesando
    # kilobytes. NULL = sin foto, que es el caso normal.
    # Requiere el ALTER de `alter_paros_foto_velocidad.sql`: create_all() no añade
    # columnas a una tabla que ya existe.
    foto_path = Column(String(500), nullable=True)

class PedidoBodegaDB(Base):
    """Tabla exclusiva para el MES: Solicitudes de operarios al Insumista"""
    __tablename__ = "pedidos_bodega"
    id = Column(Integer, primary_key=True, index=True)
    # Idempotencia de la cola offline (CLAUDE.md §5). Hasta la v1.7.0 pedir un insumo
    # era la ÚNICA operación del operario que exigía red: el botón se bloqueaba sin
    # conexión. UNIQUE para que un reintento no cree dos pedidos.
    request_id = Column(String(64), unique=True, index=True, nullable=True)
    session_id = Column(Integer, index=True)
    # `detalle_pedido` NO se toca: es contrato congelado con la app y es lo que hace
    # legible el histórico. Desde la v1.7.0 lo ESCRIBE EL SERVIDOR a partir de
    # `insumo_id`, en vez de recibirlo tecleado por el operario.
    detalle_pedido = Column(String(255))
    insumo_id = Column(Integer, nullable=True, index=True)
    cantidad_solicitada = Column(Integer)
    unidad = Column(String(20), nullable=True)
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

    # Producción planificada contra la que se pidió (v1.7.0), cuando aplica.
    plan_id = Column(Integer, nullable=True, index=True)
    # OPERARIO | SUPERVISOR. Si el operario no estaba en la máquina y confirmó el
    # supervisor, tiene que quedar escrito: hasta ahora el pedido cerrado parecía
    # confirmado por un operario que nunca lo vio.
    confirmado_por_tipo = Column(String(20), nullable=True)
    confirmado_por_id = Column(Integer, nullable=True)

class EntregaProactivaDB(Base):
    """Entregas de insumos registradas por el insumista sin pedido previo (planeación)."""
    __tablename__ = "entregas_proactivas"
    id = Column(Integer, primary_key=True, index=True)
    request_id = Column(String(64), unique=True, index=True, nullable=True)
    insumista_id = Column(Integer, index=True)
    tipo_producto = Column(String(50))
    insumo = Column(String(255))
    insumo_id = Column(Integer, nullable=True, index=True)
    cantidad = Column(Integer)
    maquina = Column(String(100))
    # Hasta la v1.7.0 una entrega proactiva solo guardaba el NOMBRE de la máquina: el
    # operario de esa máquina ni se enteraba de que le habían dejado material.
    session_id = Column(Integer, nullable=True, index=True)
    # Producción planificada sin máquina todavía asignada: se deja el material para
    # que después cualquier máquina tome lo que necesite.
    plan_id = Column(Integer, nullable=True, index=True)
    observaciones = Column(String(500), nullable=True)
    foto_path = Column(String(500), nullable=True)
    # Registrada | Verificada | Rechazada — el supervisor confirma que lo entregado
    # está donde dice la foto.
    estado = Column(String(20), nullable=False, default="Registrada")
    verificado_por = Column(Integer, nullable=True)
    fecha_verificacion = Column(DateTime, nullable=True)
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


class MensajeTabletDB(Base):
    """Mensajes que el admin web envía a una sesión/tablet activa (CAMBIO 4).

    El destino se identifica por `maquina` (la tablet reporta su máquina en el
    heartbeat). Se entregan por WebSocket de tablets al instante y por el heartbeat
    como respaldo; la tablet confirma (`leido`) al mostrarlos para no repetir.
    """
    __tablename__ = "mensajes_tablet"
    id = Column(Integer, primary_key=True, index=True)
    origen = Column(String(100), nullable=False)          # admin que lo envió
    sesion_id = Column(Integer, nullable=True)            # sesión destino
    maquina = Column(String(100), nullable=False, index=True)  # máquina destino (targeting)
    operador = Column(String(150), nullable=True)         # operario destino (informativo)
    texto = Column(String(500), nullable=False)
    creado_en = Column(DateTime, default=lambda: datetime.now())
    leido = Column(Boolean, nullable=False, default=False)
    leido_en = Column(DateTime, nullable=True)
    device_id = Column(String(100), nullable=True)        # tablet que confirmó (ACK)


class ComentarioTurnoDB(Base):
    """Comentarios libres del turno escritos por el operario (CAMBIO 5).

    Offline-first: la tablet los encola y los envía el SyncWorker; idempotente por
    `request_id` (UNIQUE), igual que pallets/checklist.
    """
    __tablename__ = "comentarios_turno"
    id = Column(Integer, primary_key=True, index=True)
    session_id = Column(Integer, nullable=True, index=True)  # turno asociado (cuando aplica)
    maquina = Column(String(100), nullable=True)
    operador = Column(String(150), nullable=True)
    texto = Column(String(1000), nullable=False)
    request_id = Column(String(64), unique=True, index=True, nullable=True)
    creado_en = Column(DateTime, default=lambda: datetime.now())


class ReporteAppDB(Base):
    """Reportes de fallas/problemas de la aplicación enviados por el operario (CAMBIO 5).

    Mismo patrón offline-first e idempotencia por `request_id` que los comentarios.
    Puede llegar sin turno (session_id NULL).
    """
    __tablename__ = "reportes_app"
    id = Column(Integer, primary_key=True, index=True)
    session_id = Column(Integer, nullable=True, index=True)
    maquina = Column(String(100), nullable=True)
    operador = Column(String(150), nullable=True)
    texto = Column(String(1000), nullable=False)
    request_id = Column(String(64), unique=True, index=True, nullable=True)
    creado_en = Column(DateTime, default=lambda: datetime.now())

    # Seguimiento desde /admin (2026-08-07). Columnas AÑADIDAS a una tabla que ya
    # existía, así que `create_all` no las crea: hace falta el ALTER de
    # `alter_reportes_atendido.sql` ANTES de desplegar este código.
    #
    # No se borra el reporte al atenderlo: el historial de qué falló en las tablets es
    # justo lo que se pierde hoy en el buzón de correo.
    atendido = Column(Boolean, nullable=False, default=False)
    atendido_en = Column(DateTime, nullable=True)
    atendido_por = Column(String(150), nullable=True)

class ConfigCorreoDB(Base):
    """Configuración del correo saliente, editable desde /admin → Correo (2026-08-07).

    **Fila única, `id = 1`.** No es una tabla de registros: es un ajuste del sistema, y
    tenerlo en la BD (en vez de en el `.env`) es lo que permite cambiar destinatarios sin
    tocar el servidor ni redesplegar.

    Todos los campos son NULL a propósito: un valor sin poner significa «usa el del
    `.env`», que es de donde salía todo hasta ahora. Así la tabla puede crearse vacía y el
    correo sigue funcionando exactamente igual que antes hasta que alguien la edite.

    Las listas van como texto separado por comas, el mismo formato del `.env`, para que
    los dos orígenes se lean con la misma función y no haya dos formatos que mantener.

    OJO con `smtp_pass`: SMTP necesita la contraseña en claro para autenticarse, así que
    no puede hashearse como las de `administradores`. Se guarda tal cual, **nunca se
    devuelve por la API** y solo se escribe si el admin la cambia; mientras esté vacía
    manda la del `.env`, que es el caso normal. Quien pueda leer la BD podrá leerla: es la
    razón de que esta pantalla sea solo para SUPERADMIN.
    """
    __tablename__ = "config_correo"
    id = Column(Integer, primary_key=True, index=True)

    smtp_host = Column(String(200), nullable=True)
    smtp_port = Column(Integer, nullable=True)
    smtp_user = Column(String(200), nullable=True)
    smtp_pass = Column(String(300), nullable=True)
    smtp_from = Column(String(200), nullable=True)

    # Una lista TO y una CC por cada tipo de correo que manda el sistema.
    pedidos_to = Column(Text, nullable=True)
    pedidos_cc = Column(Text, nullable=True)
    reportes_to = Column(Text, nullable=True)
    reportes_cc = Column(Text, nullable=True)
    semanal_to = Column(Text, nullable=True)
    semanal_cc = Column(Text, nullable=True)

    # Reporte semanal de paros: interruptor y marca del último envío. La marca es lo que
    # hace idempotente al programador: sobrevive a reinicios y evita el envío doble.
    semanal_activo = Column(Boolean, nullable=False, default=True)
    semanal_ultimo_envio = Column(DateTime, nullable=True)
    semanal_ultima_ventana = Column(DateTime, nullable=True)  # corte de la ventana enviada

    actualizado_en = Column(DateTime, nullable=True)
    actualizado_por = Column(String(150), nullable=True)


# =============================================================================
# Planificación de producción e insumos (v1.7.0)
# =============================================================================

class PlanProduccionDB(Base):
    """Lo que producción planifica para un día.

    `maquina` es NULL-able A PROPÓSITO y es el punto del que nace toda la función:
    hay producción planificada que todavía no tiene máquina asignada, y el insumista
    deja el material para que después cualquier máquina tome lo que necesite. Obligar
    a elegir máquina aquí mataría justo ese caso.

    producto/marca/presentacion/fragancia van como TEXTO y no como FK a los catálogos,
    igual que `SesionTrabajoDB`: un plan es la foto de lo que se iba a hacer ese día y
    no puede cambiar de contenido porque alguien renombre una marca.
    """
    __tablename__ = "planes_produccion"
    id = Column(Integer, primary_key=True, index=True)
    fecha = Column(Date, nullable=False, index=True)
    turno = Column(String(20), nullable=True)
    maquina = Column(String(100), nullable=True)
    producto = Column(String(100), nullable=True)
    marca = Column(String(100), nullable=True)
    presentacion = Column(String(100), nullable=True)
    fragancia = Column(String(100), nullable=True)
    cantidad_planificada = Column(Integer, nullable=True)
    unidad = Column(String(20), nullable=True)
    # Planificado | En Curso | Completado | Cancelado
    estado = Column(String(20), nullable=False, default="Planificado")
    observaciones = Column(String(500), nullable=True)
    creado_por = Column(String(100), nullable=True)
    creado_en = Column(DateTime, default=lambda: datetime.now())


class PlanInsumoDB(Base):
    """Insumos que necesita un plan. Se generan explotando el BOM y quedan editables.

    NO lleva `cantidad_entregada`: se calcula sumando las entregas de ese plan. Un
    contador guardado habría que mantenerlo sincronizado a mano y se desfasaría en
    cuanto el admin corrija una cantidad desde la web, que es justo lo que la pestaña
    «Insumos» permite hacer hoy.
    """
    __tablename__ = "plan_insumos"
    id = Column(Integer, primary_key=True, index=True)
    plan_id = Column(Integer, ForeignKey("planes_produccion.id", ondelete="CASCADE"), nullable=False)
    insumo_id = Column(Integer, ForeignKey("insumos.id"), nullable=False, index=True)
    cantidad_requerida = Column(Integer, nullable=False, default=0)
    # Normal | Alta | Urgente — la palanca del supervisor cuando el plan está por
    # arrancar y ese material no ha llegado.
    prioridad = Column(String(20), nullable=False, default="Normal")
    observaciones = Column(String(500), nullable=True)
    creado_en = Column(DateTime, default=lambda: datetime.now())

    __table_args__ = (UniqueConstraint("plan_id", "insumo_id", name="uq_plan_insumo"),)


class SesionUsuarioDB(Base):
    """Turno de un insumista/supervisor en una tablet. Resuelve tres cosas a la vez:

    a) LOGIN ÚNICO. `ModelosRed.kt` decía que `device_id` bloqueaba el segundo login
       del mismo usuario desde otra tablet y que el server auto-liberaba a las 9 h.
       Nada de eso existía: `/api/login` ni miraba el campo.
    b) AUTORIZACIÓN. `GET /api/insumos/pendientes/{rol}` recibía el rol COMO TEXTO
       desde el cliente. Con el token, el servidor deduce quién es y qué bodega le
       toca en vez de creerle.
    c) PRESENCIA. «Qué insumistas están de turno», que el supervisor necesita ver.

    El token va EN LA BASE y no en memoria como el `_TOKENS` del admin, que se vacía en
    cada `systemctl restart sigep`: a un admin en su escritorio volver a loguearse le
    cuesta nada; a un insumista con la tablet en el bolsillo a media jornada, no.

    Historial en filas y no columnas en `UsuarioDB`: la sesión ya cerrada es justo el
    dato que hace falta para saber quién estuvo de turno ayer.
    """
    __tablename__ = "sesiones_usuario"
    id = Column(Integer, primary_key=True, index=True)
    usuario_id = Column(Integer, ForeignKey("usuarios.id"), nullable=False, index=True)
    device_id = Column(String(100), nullable=False)
    token = Column(String(64), unique=True, nullable=False, index=True)
    rol = Column(String(50), nullable=True)
    inicio = Column(DateTime, default=lambda: datetime.now())
    ultimo_visto = Column(DateTime, default=lambda: datetime.now())
    # NULL = sesión abierta. Es el filtro de «quién está de turno ahora».
    fin = Column(DateTime, nullable=True)
    # LOGOUT | RELEVO | EXPIRACION | GC
    cerrada_por = Column(String(30), nullable=True)


class MensajeBodegaDB(Base):
    """Chat supervisor <-> insumista, con historial.

    Tabla aparte de `MensajeTabletDB` (CAMBIO 4) porque aquella es admin -> tablet de
    PRODUCCIÓN, de un solo sentido y colgada de `sesion_id`. Esta es bidireccional y
    entre personas, no entre una persona y una máquina.

    Los dos extremos viven en tablas DISTINTAS y por eso hay dos columnas y no una:
    los supervisores entran por `administradores` (`/api/admin/login`) y los insumistas
    por `usuarios` (`/api/login`, por PIN).

    `insumista_id` NULL = difusión a todos los de turno. La conversación es el par
    (supervisor_id, insumista_id), así que cada insumista ve solo la suya.

    Sin borrado: el historial es el punto — para eso se pidió trazabilidad.
    """
    __tablename__ = "mensajes_bodega"
    id = Column(Integer, primary_key=True, index=True)
    supervisor_id = Column(Integer, ForeignKey("administradores.id"), nullable=True, index=True)
    insumista_id = Column(Integer, ForeignKey("usuarios.id"), nullable=True, index=True)
    # SUPERVISOR | INSUMISTA
    emisor = Column(String(20), nullable=False)
    texto = Column(String(500), nullable=False)
    creado_en = Column(DateTime, default=lambda: datetime.now())
    leido_en = Column(DateTime, nullable=True)
