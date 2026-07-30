from fastapi import FastAPI, HTTPException, Depends
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from sqlalchemy import create_engine, Column, Integer, String, DateTime, Float, Boolean, func
from sqlalchemy.orm import declarative_base, sessionmaker, Session, joinedload
from datetime import datetime
import pandas as pd
from io import BytesIO
from fastapi.responses import StreamingResponse
import logging
import os
from dotenv import load_dotenv

load_dotenv()

# --- Logging ---
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("sigep")

# --- CONFIGURACIÓN DE DB ---
SQLALCHEMY_DATABASE_URL = os.getenv("DATABASE_URL", "mysql+mysqlconnector://root:password@localhost:3306/produccion_detg")

engine = create_engine(
    SQLALCHEMY_DATABASE_URL,
    pool_pre_ping=True,
    pool_recycle=3600,
    pool_size=10,
    max_overflow=20,
)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()

# --- APP ---
app = FastAPI(
    title="SIGEP — Control de Producción Detcuador",
    version="2.0.0",
    docs_url="/docs",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# --- MODELOS DE BASE DE DATOS (SQLAlchemy) ---

class OperadorDB(Base):
    __tablename__ = "operadores"
    id = Column(Integer, primary_key=True, index=True)
    nombre = Column(String(150), unique=True)
    activo = Column(Boolean, default=True)


class MaquinaDB(Base):
    __tablename__ = "maquinas"
    id = Column(Integer, primary_key=True, index=True)
    nombre = Column(String(100), unique=True)
    activa = Column(Boolean, default=True)


class SesionTrabajoDB(Base):
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


class PalletDB(Base):
    __tablename__ = "registro_pallets"
    id = Column(Integer, primary_key=True, index=True)
    session_id = Column(Integer, index=True)
    cantidad_pacas = Column(Integer)
    fecha_hora = Column(DateTime, default=lambda: datetime.now())


class ParoMaquinaDB(Base):
    __tablename__ = "paros_maquina"
    id = Column(Integer, primary_key=True, index=True)
    session_id = Column(Integer, index=True)
    motivo = Column(String(255))
    inicio_paro = Column(DateTime, default=lambda: datetime.now())
    fin_paro = Column(DateTime, nullable=True)
    duracion_segundos = Column(Float, nullable=True)

class InsumoDB(Base):
    __tablename__ = "insumos"
    id = Column(Integer, primary_key=True, index=True)
    nombre = Column(String(150), unique=True)
    categoria = Column(String(50)) # Ej: "Empaque Plástico", "Cartón", "Etiqueta"
    unidad_medida = Column(String(20)) # Ej: "Unidades", "Rollos", "Kg"
    activo = Column(Boolean, default=True)

class RecetaProductoDB(Base):
    """
    BOM (Bill of Materials). Define qué insumos pertenecen a qué combinación de producto.
    """
    __tablename__ = "recetas_productos"
    id = Column(Integer, primary_key=True, index=True)
    marca = Column(String(100))        # Ej: "ULTREX"
    presentacion = Column(String(100)) # Ej: "1 KG"
    fragancia = Column(String(100))    # Ej: "Limón"
    insumo_id = Column(Integer, index=True) # ID del InsumoDB permitido
    # Nota: Aquí más adelante se puede poner una columna de "cantidad_estandar"

class SolicitudInsumoDB(Base):
    __tablename__ = "solicitudes_insumos"
    id = Column(Integer, primary_key=True, index=True)
    session_id = Column(Integer, index=True) # Quién lo pide (Máquina/Operador)
    insumo_id = Column(Integer, index=True)  # Qué pide
    cantidad_solicitada = Column(Integer)
    estado = Column(String(50), default="Pendiente") # Pendiente, En Camino, Entregado, Cancelado
    fecha_solicitud = Column(DateTime, default=lambda: datetime.now())
    fecha_entrega = Column(DateTime, nullable=True)


# Crear tablas
Base.metadata.create_all(bind=engine)

# --- MODELOS DE ENTRADA (Pydantic) ---

class IniciarTurno(BaseModel):
    tipo: str
    maquina: str
    operador: str
    marca: str
    presentacion: str
    fragancia: str


class RegistrarPalletRequest(BaseModel):
    sesion_id: int
    cantidad_pacas: int


class FinalizarTurno(BaseModel):
    sesion_id: int


class IniciarParo(BaseModel):
    sesion_id: int
    motivo: str


class FinalizarParo(BaseModel):
    sesion_id: int


# --- Dependencia para la base de datos ---
def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


# ===========================================
#  DASHBOARD ENDPOINTS
# ===========================================

@app.get("/api/dashboard/kpis")
def obtener_kpis(db: Session = Depends(get_db)):
    """KPIs del día: pallets, turnos activos, eficiencia."""
    try:
        hoy = datetime.now().date()

        total_pallets = db.query(
            func.coalesce(func.sum(PalletDB.cantidad_pacas), 0)
        ).filter(
            func.date(PalletDB.fecha_hora) == hoy
        ).scalar()

        turnos_activos = db.query(
            func.count(SesionTrabajoDB.id)
        ).filter(
            SesionTrabajoDB.fin_turno.is_(None)
        ).scalar()

        return {
            "pallets_hoy": int(total_pallets),
            "turnos_activos": int(turnos_activos),
            "eficiencia": "94.8%",  # Placeholder — OEE real próximamente
        }
    except Exception as e:
        logger.error(f"Error en /dashboard/kpis: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener KPIs")


@app.get("/api/dashboard/logs")
def obtener_logs_recientes(db: Session = Depends(get_db)):
    """Últimos 15 registros de actividad para la terminal en vivo."""
    try:
        # JOIN en vez de N+1 queries
        ultimos_pallets = (
            db.query(PalletDB, SesionTrabajoDB)
            .join(SesionTrabajoDB, SesionTrabajoDB.id == PalletDB.session_id)
            .order_by(PalletDB.fecha_hora.desc())
            .limit(15)
            .all()
        )

        logs = []
        for pallet, sesion in ultimos_pallets:
            logs.append({
                "hora": pallet.fecha_hora.strftime("%H:%M:%S"),
                "mensaje": (
                    f"PALLET REGISTRADO: {pallet.cantidad_pacas} pacas "
                    f"— {sesion.maquina} ({sesion.operador})"
                ),
                "tipo": "pallet",
            })

        return logs
    except Exception as e:
        logger.error(f"Error en /dashboard/logs: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener logs")


@app.get("/api/dashboard/produccion_hora")
def obtener_produccion_hora(db: Session = Depends(get_db)):
    """Pallets registrados por hora del día actual (para el gráfico)."""
    try:
        hoy = datetime.now().date()

        resultados = (
            db.query(
                func.extract("hour", PalletDB.fecha_hora).label("hora"),
                func.coalesce(func.sum(PalletDB.cantidad_pacas), 0).label("pallets"),
            )
            .filter(func.date(PalletDB.fecha_hora) == hoy)
            .group_by(func.extract("hour", PalletDB.fecha_hora))
            .order_by(func.extract("hour", PalletDB.fecha_hora))
            .all()
        )

        data = []
        for row in resultados:
            h = int(row.hora)
            data.append({
                "hora": f"{h:02d}:00",
                "pallets": int(row.pallets),
            })

        return data
    except Exception as e:
        logger.error(f"Error en /dashboard/produccion_hora: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener producción por hora")


@app.get("/api/dashboard/estado_operativo")
def obtener_estado_operativo(db: Session = Depends(get_db)):
    """Operación en vivo: sesiones de hoy y sus KPIs técnicos."""
    try:
        hoy = datetime.now().date()
        sesiones = (
            db.query(SesionTrabajoDB)
            .filter(func.date(SesionTrabajoDB.inicio_turno) == hoy)
            .order_by(SesionTrabajoDB.inicio_turno.desc())
            .all()
        )
        
        resultados = []
        for s in sesiones:
            pallets_sesion = db.query(
                func.coalesce(func.sum(PalletDB.cantidad_pacas), 0)
            ).filter(PalletDB.session_id == s.id).scalar()
            
            p_marca = s.marca or ""
            p_frag = s.fragancia or ""
            p_pres = s.presentacion or ""
            producto = f"{p_marca} - {p_frag} - {p_pres}".strip(" -")
            
            if s.fin_turno:
                estado = "Finalizado"
                tiempo_transcurrido = int(s.duracion_minutos) if s.duracion_minutos else 0
            else:
                estado = "Activo"
                # Calculo de duracion en progreso
                tiempo_transcurrido = int((datetime.now() - s.inicio_turno).total_seconds() / 60)
                
            resultados.append({
                "sesion_id": s.id,
                "maquina": s.maquina,
                "operador": s.operador,
                "producto": producto,
                "inicio_turno": s.inicio_turno.strftime("%H:%M:%S") if s.inicio_turno else "",
                "tiempo_transcurrido": tiempo_transcurrido,
                "total_pacas": int(pallets_sesion),
                "estado": estado
            })
            
        return resultados
    except Exception as e:
        logger.error(f"Error en /dashboard/estado_operativo: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener estado operativo")


@app.get("/api/dashboard/top_produccion")
def obtener_top_produccion(db: Session = Depends(get_db)):
    """Producción consolidada por marca para gráfico."""
    try:
        hoy = datetime.now().date()
        resultados = (
            db.query(
                SesionTrabajoDB.marca,
                func.coalesce(func.sum(PalletDB.cantidad_pacas), 0).label("total")
            )
            .join(PalletDB, PalletDB.session_id == SesionTrabajoDB.id)
            .filter(func.date(PalletDB.fecha_hora) == hoy)
            .group_by(SesionTrabajoDB.marca)
            .order_by(func.sum(PalletDB.cantidad_pacas).desc())
            .all()
        )
        
        data = [{"name": r.marca if r.marca else "NA", "value": int(r.total)} for r in resultados]
        return data
    except Exception as e:
        logger.error(f"Error en /dashboard/top_produccion: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener top produccion")


@app.get("/api/reportes/excel")
def descargar_excel(db: Session = Depends(get_db)):
    """Descarga un reporte Excel con las sesiones y pallets del día."""
    try:
        hoy = datetime.now().date()

        sesiones = db.query(SesionTrabajoDB).filter(
            func.date(SesionTrabajoDB.inicio_turno) == hoy
        ).all()

        if not sesiones:
            raise HTTPException(status_code=404, detail="No hay datos para hoy")

        rows = []
        for s in sesiones:
            pallets_sesion = db.query(
                func.coalesce(func.sum(PalletDB.cantidad_pacas), 0)
            ).filter(PalletDB.session_id == s.id).scalar()

            rows.append({
                "ID Sesión": s.id,
                "Tipo": s.tipo,
                "Máquina": s.maquina,
                "Operador": s.operador,
                "Marca": s.marca,
                "Presentación": s.presentacion,
                "Fragancia": s.fragancia,
                "Inicio": s.inicio_turno.strftime("%H:%M:%S") if s.inicio_turno else "",
                "Fin": s.fin_turno.strftime("%H:%M:%S") if s.fin_turno else "En curso",
                "Duración (min)": round(s.duracion_minutos, 1) if s.duracion_minutos else "",
                "Pallets": int(pallets_sesion),
            })

        df = pd.DataFrame(rows)
        buffer = BytesIO()
        with pd.ExcelWriter(buffer, engine="openpyxl") as writer:
            df.to_excel(writer, index=False, sheet_name="Producción")
        buffer.seek(0)

        filename = f"reporte_produccion_{hoy.isoformat()}.xlsx"
        return StreamingResponse(
            buffer,
            media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            headers={"Content-Disposition": f'attachment; filename="{filename}"'},
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error en /reportes/excel: {e}")
        raise HTTPException(status_code=500, detail="Error al generar reporte")


# ===========================================
#  ENDPOINTS OPERACIONALES
# ===========================================
@app.get("/api/sesion/{sesion_id}/historial")
def obtener_historial_sesion(sesion_id: int, db: Session = Depends(get_db)):
    """Recupera el historial de pallets de una sesión específica (para cuando la tablet se reinicia)."""
    try:
        # Buscamos los pallets y los ordenamos del más nuevo al más viejo (DESC)
        pallets = db.query(PalletDB).filter(
            PalletDB.session_id == sesion_id
        ).order_by(PalletDB.fecha_hora.desc()).all()

        historial = []
        for p in pallets:
            historial.append({
                "cantidad_pacas": p.cantidad_pacas,
                "hora": p.fecha_hora.strftime("%H:%M:%S")
            })

        return historial
    except Exception as e:
        logger.error(f"Error en /api/sesion/historial: {e}")
        raise HTTPException(status_code=500, detail="Error al recuperar historial")

@app.post("/api/iniciar_turno")
def iniciar(datos: IniciarTurno, db: Session = Depends(get_db)):
    """Inicia un nuevo turno de trabajo."""
    try:
        nueva_sesion = SesionTrabajoDB(
            tipo=datos.tipo,
            maquina=datos.maquina,
            operador=datos.operador,
            marca=datos.marca,
            presentacion=datos.presentacion,
            fragancia=datos.fragancia,
            inicio_turno=datetime.now(),
        )
        db.add(nueva_sesion)
        db.commit()
        db.refresh(nueva_sesion)
        logger.info(f"Turno iniciado: sesión {nueva_sesion.id} — {datos.operador} en {datos.maquina}")
        return {"sesion_id": nueva_sesion.id, "mensaje": "Turno iniciado"}
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/iniciar_turno: {e}")
        raise HTTPException(status_code=500, detail="Error interno de base de datos. Intente nuevamente.")


@app.post("/api/registrar_pallet")
def registrar_pallet(datos: RegistrarPalletRequest, db: Session = Depends(get_db)):
    """Registra un pallet bajo una sesión activa."""
    sesion = db.query(SesionTrabajoDB).filter(
        SesionTrabajoDB.id == datos.sesion_id
    ).first()

    if not sesion:
        raise HTTPException(status_code=404, detail="Sesión no encontrada")

    if sesion.fin_turno is not None:
        raise HTTPException(status_code=400, detail="No se puede registrar en un turno finalizado")

    try:
        nuevo_pallet = PalletDB(
            session_id=datos.sesion_id,
            cantidad_pacas=datos.cantidad_pacas,
            fecha_hora=datetime.now(),
        )
        db.add(nuevo_pallet)
        db.commit()
        logger.info(f"Pallet registrado: {datos.cantidad_pacas} pacas — sesión {datos.sesion_id}")
        return {"mensaje": "Pallet registrado correctamente"}
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/registrar_pallet: {e}")
        raise HTTPException(status_code=500, detail="Error interno de base de datos. Intente nuevamente.")


@app.post("/api/finalizar_turno")
def finalizar(datos: FinalizarTurno, db: Session = Depends(get_db)):
    """Finaliza un turno y calcula la duración, cerrando paros activos si existen."""
    sesion = db.query(SesionTrabajoDB).filter(
        SesionTrabajoDB.id == datos.sesion_id
    ).first()

    if not sesion:
        raise HTTPException(status_code=404, detail="Sesión no encontrada")

    if sesion.fin_turno is not None:
        raise HTTPException(status_code=400, detail="Este turno ya fue finalizado")

    try:
        # ==========================================
        # NUEVO: AUTO-CIERRE DE PARO DE EMERGENCIA
        # ==========================================
        paro_activo = db.query(ParoMaquinaDB).filter(
            ParoMaquinaDB.session_id == datos.sesion_id,
            ParoMaquinaDB.fin_paro.is_(None)
        ).first()

        if paro_activo:
            paro_activo.fin_paro = datetime.now()
            paro_activo.duracion_segundos = round((paro_activo.fin_paro - paro_activo.inicio_paro).total_seconds(), 2)
            logger.info(f"Paro auto-cerrado por fin de turno: ID {paro_activo.id}")
        # ==========================================

        # Cierre normal del turno
        sesion.fin_turno = datetime.now()
        diferencia = sesion.fin_turno - sesion.inicio_turno
        sesion.duracion_minutos = diferencia.total_seconds() / 60

        db.commit()
        logger.info(f"Turno finalizado: sesión {sesion.id} — {round(sesion.duracion_minutos, 1)} min")
        return {
            "mensaje": "Turno finalizado (y paros cerrados)",
            "duracion_minutos": round(sesion.duracion_minutos, 2),
        }
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/finalizar_turno: {e}")
        raise HTTPException(status_code=500, detail="Error interno de base de datos. Intente nuevamente.")

# ===========================================
#  ENDPOINTS PARA DATOS DINÁMICOS
# ===========================================

@app.get("/api/operadores")
def obtener_operadores(db: Session = Depends(get_db)):
    """Lista todos los operadores activos."""
    operadores = db.query(OperadorDB).filter(OperadorDB.activo.is_(True)).all()
    return [{"id": op.id, "nombre": op.nombre} for op in operadores]


@app.get("/api/maquinas")
def obtener_maquinas(db: Session = Depends(get_db)):
    """Lista todas las máquinas activas."""
    maquinas = db.query(MaquinaDB).filter(MaquinaDB.activa.is_(True)).all()
    return [{"id": maq.id, "nombre": maq.nombre} for maq in maquinas]


# ===========================================
#  ENDPOINTS DE TIEMPOS MUERTOS (PAROS)
# ===========================================

@app.post("/api/paro/iniciar")
def iniciar_paro(datos: IniciarParo, db: Session = Depends(get_db)):
    """Inicia un cronómetro de paro para una sesión activa."""
    sesion = db.query(SesionTrabajoDB).filter(
        SesionTrabajoDB.id == datos.sesion_id
    ).first()

    if not sesion:
        raise HTTPException(status_code=404, detail="Sesión no encontrada")

    if sesion.fin_turno is not None:
        raise HTTPException(status_code=400, detail="No se puede registrar paro en un turno finalizado")

    # Verificar que no exista un paro ya abierto para esta sesión
    paro_abierto = db.query(ParoMaquinaDB).filter(
        ParoMaquinaDB.session_id == datos.sesion_id,
        ParoMaquinaDB.fin_paro.is_(None),
    ).first()

    if paro_abierto:
        raise HTTPException(status_code=409, detail="Ya existe un paro activo para esta sesión")

    try:
        nuevo_paro = ParoMaquinaDB(
            session_id=datos.sesion_id,
            motivo=datos.motivo,
            inicio_paro=datetime.now(),
        )
        db.add(nuevo_paro)
        db.commit()
        db.refresh(nuevo_paro)
        logger.info(f"Paro iniciado: ID {nuevo_paro.id} — sesión {datos.sesion_id} — {datos.motivo}")
        return {"paro_id": nuevo_paro.id, "mensaje": "Paro registrado"}
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/paro/iniciar: {e}")
        raise HTTPException(status_code=500, detail="Error interno de base de datos. Intente nuevamente.")


@app.post("/api/paro/finalizar")
def finalizar_paro(datos: FinalizarParo, db: Session = Depends(get_db)):
    """Finaliza el paro activo más reciente de una sesión y calcula duración."""
    paro = (
        db.query(ParoMaquinaDB)
        .filter(
            ParoMaquinaDB.session_id == datos.sesion_id,
            ParoMaquinaDB.fin_paro.is_(None),
        )
        .order_by(ParoMaquinaDB.inicio_paro.desc())
        .first()
    )

    if not paro:
        raise HTTPException(status_code=404, detail="No hay paro activo para esta sesión")

    try:
        paro.fin_paro = datetime.now()
        diferencia = paro.fin_paro - paro.inicio_paro
        paro.duracion_segundos = round(diferencia.total_seconds(), 2)

        db.commit()
        logger.info(f"Paro finalizado: ID {paro.id} — {paro.duracion_segundos}s — sesión {datos.sesion_id}")
        return {
            "paro_id": paro.id,
            "mensaje": "Paro finalizado",
            "duracion_segundos": paro.duracion_segundos,
        }
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/paro/finalizar: {e}")
        raise HTTPException(status_code=500, detail="Error interno de base de datos. Intente nuevamente.")


# ===========================================
#  HEALTHCHECK
# ===========================================

@app.get("/api/health")
def health():
    """Verifica que el servidor esté activo."""
    return {"status": "ok", "timestamp": datetime.now().isoformat()}

# ===========================================
#  GESTIÓN DE INSUMOS
# ===========================================

class SolicitarInsumoRequest(BaseModel):
    sesion_id: int
    insumo_id: int
    cantidad: int

@app.get("/api/sesion/{sesion_id}/insumos_permitidos")
def obtener_insumos_permitidos(sesion_id: int, db: Session = Depends(get_db)):
    """
    Lee la sesión actual, averigua qué producto están haciendo (BOM), 
    y devuelve SOLO los insumos autorizados para ese producto.
    """
    try:
        # 1. Buscamos qué está haciendo la máquina
        sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == sesion_id).first()
        if not sesion:
            raise HTTPException(status_code=404, detail="Sesión no encontrada")

        # 2. Buscamos la "receta" cruzando la Marca y Presentación
        recetas = db.query(RecetaProductoDB).filter(
            RecetaProductoDB.marca == sesion.marca,
            RecetaProductoDB.presentacion == sesion.presentacion
            # Nota: Si luego varía por fragancia, agregas la condición aquí.
        ).all()

        # Extraemos solo los IDs de los insumos permitidos
        ids_permitidos = [r.insumo_id for r in recetas]

        if not ids_permitidos:
            return [] # Si un producto aún no tiene receta configurada, devuelve vacío.

        # 3. Traemos la información detallada de esos insumos
        insumos = db.query(InsumoDB).filter(
            InsumoDB.id.in_(ids_permitidos), 
            InsumoDB.activo == True
        ).all()

        return [
            {
                "id": i.id, 
                "nombre": i.nombre, 
                "categoria": i.categoria, 
                "unidad": i.unidad_medida
            } for i in insumos
        ]
        
    except Exception as e:
        logger.error(f"Error al obtener insumos permitidos: {e}")
        raise HTTPException(status_code=500, detail="Error interno al buscar BOM")


@app.post("/api/insumos/solicitar")
def solicitar_insumo(datos: SolicitarInsumoRequest, db: Session = Depends(get_db)):
    """
    Registra el pedido de un operario para que lo vea el Insumista.
    """
    try:
        nueva_solicitud = SolicitudInsumoDB(
            session_id=datos.sesion_id,
            insumo_id=datos.insumo_id,
            cantidad_solicitada=datos.cantidad,
            estado="Pendiente",
            fecha_solicitud=datetime.now()
        )
        db.add(nueva_solicitud)
        db.commit()
        
        logger.info(f"🚨 NUEVO PEDIDO: Sesión {datos.sesion_id} pidió {datos.cantidad} del insumo {datos.insumo_id}")
        return {"mensaje": "Solicitud enviada a bodega con éxito"}
        
    except Exception as e:
        db.rollback()
        logger.error(f"Error al solicitar insumo: {e}")
        raise HTTPException(status_code=500, detail="Error al enviar la solicitud")

        # --- NUEVO MODELO PARA PEDIDOS DINÁMICOS ---
class PedidoBodegaDB(Base):
    __tablename__ = "pedidos_bodega"
    id = Column(Integer, primary_key=True, index=True)
    session_id = Column(Integer, index=True)
    detalle_pedido = Column(String(255)) # Aquí guardaremos: "Lámina - TORBELLINO - Floral - 1 KG"
    cantidad_solicitada = Column(Integer)
    estado = Column(String(50), default="Pendiente")
    fecha_solicitud = Column(DateTime, default=lambda: datetime.now())

# Crea la nueva tabla si no existe
Base.metadata.create_all(bind=engine)

class NuevoPedidoRequest(BaseModel):
    sesion_id: int
    detalle_pedido: str
    cantidad: int

@app.post("/api/insumos/pedido_dinamico")
def crear_pedido_dinamico(datos: NuevoPedidoRequest, db: Session = Depends(get_db)):
    try:
        nuevo_pedido = PedidoBodegaDB(
            session_id=datos.sesion_id,
            detalle_pedido=datos.detalle_pedido,
            cantidad_solicitada=datos.cantidad
        )
        db.add(nuevo_pedido)
        db.commit()
        logger.info(f"📦 PEDIDO CREADO: {datos.cantidad} x {datos.detalle_pedido} (Sesión: {datos.sesion_id})")
        return {"mensaje": "Pedido enviado a bodega"}
    except Exception as e:
        db.rollback()
        logger.error(f"Error al crear pedido: {e}")
        raise HTTPException(status_code=500, detail="Error interno")