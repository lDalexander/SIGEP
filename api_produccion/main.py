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
SQLALCHEMY_DATABASE_URL = os.getenv("DATABASE_URL", "mysql+mysqlconnector://root:password@localhost:3306/db_produccion")

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

@app.post("/api/iniciar_turno")
def iniciar(datos: IniciarTurno, db: Session = Depends(get_db)):
    """Inicia un nuevo turno de trabajo."""
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

    nuevo_pallet = PalletDB(
        session_id=datos.sesion_id,
        cantidad_pacas=datos.cantidad_pacas,
        fecha_hora=datetime.now(),
    )
    db.add(nuevo_pallet)
    db.commit()
    logger.info(f"Pallet registrado: {datos.cantidad_pacas} pacas — sesión {datos.sesion_id}")
    return {"mensaje": "Pallet registrado correctamente"}


@app.post("/api/finalizar_turno")
def finalizar(datos: FinalizarTurno, db: Session = Depends(get_db)):
    """Finaliza un turno y calcula la duración."""
    sesion = db.query(SesionTrabajoDB).filter(
        SesionTrabajoDB.id == datos.sesion_id
    ).first()

    if not sesion:
        raise HTTPException(status_code=404, detail="Sesión no encontrada")

    if sesion.fin_turno is not None:
        raise HTTPException(status_code=400, detail="Este turno ya fue finalizado")

    sesion.fin_turno = datetime.now()
    diferencia = sesion.fin_turno - sesion.inicio_turno
    sesion.duracion_minutos = diferencia.total_seconds() / 60

    db.commit()
    logger.info(f"Turno finalizado: sesión {sesion.id} — {round(sesion.duracion_minutos, 1)} min")
    return {
        "mensaje": "Turno finalizado",
        "duracion_minutos": round(sesion.duracion_minutos, 2),
    }


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
#  HEALTHCHECK
# ===========================================

@app.get("/api/health")
def health():
    """Verifica que el servidor esté activo."""
    return {"status": "ok", "timestamp": datetime.now().isoformat()}