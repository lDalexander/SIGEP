"""
Router para los endpoints operacionales en piso de producción.
Maneja el inicio de turnos, registro de pallets, y paros de máquina.
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from datetime import datetime, timedelta

from database import get_db, logger
from models import OperadorDB, MaquinaDB, SesionTrabajoDB, PalletDB, ParoMaquinaDB, PedidoBodegaDB, UsuarioDB, InsumoDB, RecetaProductoDB
from schemas import IniciarTurno, RegistrarPalletRequest, FinalizarTurno, IniciarParo, FinalizarParo

router = APIRouter(prefix="/api", tags=["Operaciones"])

# Anti-ráfaga: defensa server-side contra vaciados masivos de cola desde la tablet.
# Aplica incluso cuando el cliente no envía request_id (compatibilidad con apps viejas).
# Un operador real registra 1 pallet cada 30-60 segundos, así que más de 3 inserts
# en 5 segundos a la misma sesión es siempre anomalía.
ANTI_RAFAGA_VENTANA_SEG = 5
ANTI_RAFAGA_MAX_INSERTS = 3

@router.get("/operadores")
def obtener_operadores(db: Session = Depends(get_db)):
    operadores = db.query(OperadorDB).filter(OperadorDB.activo.is_(True)).all()
    return [{"id": op.id, "nombre": op.nombre} for op in operadores]

@router.get("/maquinas")
def obtener_maquinas(db: Session = Depends(get_db)):
    """Lista las máquinas activas e incluye el catálogo de productos (marcas ->
    presentaciones) derivado del BOM (recetas_productos).

    El catálogo es global (no hay receta por máquina), así que se adjunta el mismo
    a cada máquina. La app lo usa para poblar el selector de producto al iniciar
    turno; solo se ofrecen combinaciones que SÍ tienen receta configurada.
    """
    maquinas = db.query(MaquinaDB).filter(MaquinaDB.activa.is_(True)).all()

    # marca -> set de presentaciones, según las combinaciones que existen en el BOM.
    marca_a_presentaciones: dict[str, list] = {}
    recetas = db.query(RecetaProductoDB.marca, RecetaProductoDB.presentacion).distinct().all()
    for marca, presentacion in recetas:
        if not marca:
            continue
        pres = marca_a_presentaciones.setdefault(marca, [])
        if presentacion and presentacion not in pres:
            pres.append(presentacion)

    catalogo_marcas = [
        {"nombre": marca, "presentaciones": sorted(presentaciones)}
        for marca, presentaciones in sorted(marca_a_presentaciones.items())
    ]

    return [
        {"id": maq.id, "nombre": maq.nombre, "marcas": catalogo_marcas}
        for maq in maquinas
    ]

@router.get("/sesion/{sesion_id}/detalle_pedidos")
def obtener_detalle_pedidos_sesion(sesion_id: int, db: Session = Depends(get_db)):
    """Historial completo de pedidos de una sesión con todos sus timestamps."""
    try:
        pedidos = db.query(PedidoBodegaDB)\
                    .filter(PedidoBodegaDB.session_id == sesion_id)\
                    .order_by(PedidoBodegaDB.fecha_solicitud.asc()).all()

        # Cargamos nombres de insumistas en una sola consulta para evitar N+1
        insumista_ids = {p.insumista_id for p in pedidos if p.insumista_id is not None}
        insumistas = {}
        if insumista_ids:
            usuarios = db.query(UsuarioDB).filter(UsuarioDB.id.in_(insumista_ids)).all()
            insumistas = {u.id: u.nombre for u in usuarios}

        def _fmt(dt):
            return dt.strftime("%H:%M:%S") if dt else None

        return [
            {
                "id": p.id,
                "detalle": p.detalle_pedido,
                "cantidad": p.cantidad_solicitada,
                "hora_solicitud": _fmt(p.fecha_solicitud),
                "hora_aceptacion": _fmt(p.fecha_aceptacion),
                "insumista": insumistas.get(p.insumista_id) if p.insumista_id else None,
                "hora_entrega": _fmt(p.fecha_entrega),
                "estado": p.estado,
            }
            for p in pedidos
        ]
    except Exception as e:
        logger.error(f"Error en /sesion/{sesion_id}/detalle_pedidos: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.get("/sesion/{sesion_id}/insumos_permitidos")
def obtener_insumos_permitidos(sesion_id: int, db: Session = Depends(get_db)):
    """Devuelve SOLO los insumos autorizados (BOM) para el producto del turno.

    Cruza la marca/presentación de la sesión contra recetas_productos. Si el
    producto no tiene receta configurada, devuelve lista vacía.
    """
    sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == sesion_id).first()
    if not sesion:
        raise HTTPException(status_code=404, detail="Sesión no encontrada")
    try:
        recetas = db.query(RecetaProductoDB).filter(
            RecetaProductoDB.marca == sesion.marca,
            RecetaProductoDB.presentacion == sesion.presentacion,
        ).all()
        ids_permitidos = [r.insumo_id for r in recetas]
        if not ids_permitidos:
            return []

        insumos = db.query(InsumoDB).filter(
            InsumoDB.id.in_(ids_permitidos),
            InsumoDB.activo == True,
        ).all()
        return [
            {"id": i.id, "nombre": i.nombre, "categoria": i.categoria, "unidad": i.unidad_medida}
            for i in insumos
        ]
    except Exception as e:
        logger.error(f"Error al obtener insumos permitidos (sesión {sesion_id}): {e}")
        raise HTTPException(status_code=500, detail="Error interno al buscar BOM")

@router.get("/sesion/{sesion_id}/historial")
def obtener_historial_sesion(sesion_id: int, db: Session = Depends(get_db)):
    try:
        pallets = db.query(PalletDB).filter(PalletDB.session_id == sesion_id).order_by(PalletDB.fecha_hora.desc()).all()
        return [{"cantidad_pacas": p.cantidad_pacas, "hora": p.fecha_hora.strftime("%H:%M:%S")} for p in pallets]
    except Exception as e:
        logger.error(f"Error en /api/sesion/historial: {e}")
        raise HTTPException(status_code=500, detail="Error al recuperar historial")

@router.post("/iniciar_turno")
def iniciar(datos: IniciarTurno, db: Session = Depends(get_db)):
    # --- Control de Idempotencia ---
    # Buscamos si ya existe un turno procesado con este mismo request_id
    turno_existente = db.query(SesionTrabajoDB).filter(
        SesionTrabajoDB.request_id == datos.request_id
    ).first()
    
    if turno_existente:
        raise HTTPException(
            status_code=409, 
            detail="Solicitud duplicada. Este turno ya fue procesado."
        )

    # --- Validaciones de Negocio (Prevención de duplicidad lógica) ---
    
    # 1. Validación de Operador
    turno_operador_activo = db.query(SesionTrabajoDB).filter(
        SesionTrabajoDB.operador == datos.operador,
        SesionTrabajoDB.fin_turno.is_(None)
    ).first()
    
    if turno_operador_activo:
        raise HTTPException(status_code=400, detail="Este operador ya tiene un turno activo sin finalizar.")

    # 2. Validación de Máquina
    turno_maquina_activo = db.query(SesionTrabajoDB).filter(
        SesionTrabajoDB.maquina == datos.maquina,
        SesionTrabajoDB.fin_turno.is_(None)
    ).first()
    
    if turno_maquina_activo:
        raise HTTPException(status_code=400, detail="Esta máquina ya tiene un turno activo.")

    # NOTA: iniciar_turno NO depende del checklist de mantenimiento.
    # El checklist es offline-first (se encola en la tablet y sincroniza por su
    # cuenta), así que puede aún no haber llegado al servidor cuando se inicia el
    # turno. Acoplarlo aquí provoca una carrera. Son flujos independientes.

    # --- Inserción en Base de Datos ---
    try:
        nueva_sesion = SesionTrabajoDB(
            tipo=datos.tipo,
            maquina=datos.maquina,
            operador=datos.operador,
            marca=datos.marca,
            presentacion=datos.presentacion,
            fragancia=datos.fragancia,
            request_id=datos.request_id,
            inicio_turno=datetime.now(),
        )
        db.add(nueva_sesion)
        db.commit()
        db.refresh(nueva_sesion)
        logger.info(f"Turno iniciado: sesión {nueva_sesion.id} — {datos.operador} en {datos.maquina} [RID: {datos.request_id}]")
        return {"sesion_id": nueva_sesion.id, "mensaje": "Turno iniciado"}
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/iniciar_turno: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.post("/registrar_pallet")
def registrar_pallet(datos: RegistrarPalletRequest, db: Session = Depends(get_db)):
    # --- Normalización de request_id ---
    # El cliente Gson manda request_id: String = "" por defecto. Un string vacío
    # NO es NULL, y como la columna es UNIQUE, dos pacas con "" colisionan
    # (Duplicate entry '' ...) y revientan en 500. Convertimos vacío/espacios a
    # None para que MySQL exima esos valores del UNIQUE (NULL no colisiona).
    rid = (datos.request_id or "").strip() or None

    # --- Control de Idempotencia ---
    # Si el cliente envía request_id (UUID), comprobamos que ese pallet no haya
    # sido insertado ya. Esto protege contra reintentos de tablets offline-first
    # que pueden mandar el mismo pallet varias veces si pierden la respuesta HTTP.
    if rid:
        pallet_existente = db.query(PalletDB).filter(
            PalletDB.request_id == rid
        ).first()
        if pallet_existente:
            logger.info(
                f"Pallet duplicado ignorado: RID {datos.request_id} ya registrado "
                f"(id {pallet_existente.id}, sesión {pallet_existente.session_id})"
            )
            return {
                "mensaje": "Pallet ya registrado previamente (idempotencia)",
                "pallet_id": pallet_existente.id,
                "duplicado": True,
            }

    sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == datos.sesion_id).first()
    if not sesion:
        raise HTTPException(status_code=404, detail="Sesión no encontrada")
    if sesion.fin_turno is not None:
        raise HTTPException(status_code=400, detail="No se puede registrar en un turno finalizado")

    # --- Anti-ráfaga (defensa de segundo nivel) ---
    # Si la sesión recibe más de ANTI_RAFAGA_MAX_INSERTS en una ventana de
    # ANTI_RAFAGA_VENTANA_SEG segundos, descartamos los excedentes. Esto cubre
    # el caso de tablets que vacían su cola local de golpe sin enviar request_id.
    ventana_inicio = datetime.now() - timedelta(seconds=ANTI_RAFAGA_VENTANA_SEG)
    inserts_recientes = db.query(PalletDB).filter(
        PalletDB.session_id == datos.sesion_id,
        PalletDB.fecha_hora >= ventana_inicio,
    ).count()
    if inserts_recientes >= ANTI_RAFAGA_MAX_INSERTS:
        logger.warning(
            f"⚠️  Anti-ráfaga: pallet de {datos.cantidad_pacas} pacas descartado en "
            f"sesión {datos.sesion_id} ({inserts_recientes} inserts en últimos "
            f"{ANTI_RAFAGA_VENTANA_SEG}s; tope {ANTI_RAFAGA_MAX_INSERTS})"
        )
        return {
            "mensaje": "Registro descartado: ráfaga detectada (anti-flood)",
            "duplicado": True,
            "razon": "anti_rafaga",
            "inserts_recientes": inserts_recientes,
        }

    try:
        nuevo_pallet = PalletDB(
            session_id=datos.sesion_id,
            cantidad_pacas=datos.cantidad_pacas,
            fecha_hora=datetime.now(),
            request_id=rid,
        )
        db.add(nuevo_pallet)
        db.commit()
        db.refresh(nuevo_pallet)
        rid_log = f" [RID: {rid}]" if rid else ""
        logger.info(
            f"Pallet registrado: {datos.cantidad_pacas} pacas — sesión {datos.sesion_id}{rid_log}"
        )
        return {"mensaje": "Pallet registrado correctamente", "pallet_id": nuevo_pallet.id}
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/registrar_pallet: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.post("/finalizar_turno")
def finalizar(datos: FinalizarTurno, db: Session = Depends(get_db)):
    sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == datos.sesion_id).first()
    if not sesion:
        raise HTTPException(status_code=404, detail="Sesión no encontrada")
    if sesion.fin_turno is not None:
        raise HTTPException(status_code=400, detail="Turno ya finalizado")
    try:
        paro_activo = db.query(ParoMaquinaDB).filter(ParoMaquinaDB.session_id == datos.sesion_id, ParoMaquinaDB.fin_paro.is_(None)).first()
        if paro_activo:
            paro_activo.fin_paro = datetime.now()
            paro_activo.duracion_segundos = round((paro_activo.fin_paro - paro_activo.inicio_paro).total_seconds(), 2)
            logger.info(f"Paro auto-cerrado: ID {paro_activo.id}")

        sesion.fin_turno = datetime.now()
        sesion.duracion_minutos = (sesion.fin_turno - sesion.inicio_turno).total_seconds() / 60
        db.commit()
        logger.info(f"Turno finalizado: sesión {sesion.id} — {round(sesion.duracion_minutos, 1)} min")
        return {"mensaje": "Turno finalizado", "duracion_minutos": round(sesion.duracion_minutos, 2)}
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/finalizar_turno: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.post("/paro/iniciar")
def iniciar_paro(datos: IniciarParo, db: Session = Depends(get_db)):
    sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == datos.sesion_id).first()
    if not sesion or sesion.fin_turno is not None:
        raise HTTPException(status_code=400, detail="Sesión inválida")
    paro_abierto = db.query(ParoMaquinaDB).filter(ParoMaquinaDB.session_id == datos.sesion_id, ParoMaquinaDB.fin_paro.is_(None)).first()
    if paro_abierto:
        raise HTTPException(status_code=409, detail="Ya existe un paro activo")
    try:
        nuevo_paro = ParoMaquinaDB(session_id=datos.sesion_id, motivo=datos.motivo, inicio_paro=datetime.now())
        db.add(nuevo_paro)
        db.commit()
        logger.info(f"Paro iniciado: sesión {datos.sesion_id} — {datos.motivo}")
        return {"paro_id": nuevo_paro.id, "mensaje": "Paro registrado"}
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/paro/iniciar: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.post("/paro/finalizar")
def finalizar_paro(datos: FinalizarParo, db: Session = Depends(get_db)):
    paro = db.query(ParoMaquinaDB).filter(ParoMaquinaDB.session_id == datos.sesion_id, ParoMaquinaDB.fin_paro.is_(None)).order_by(ParoMaquinaDB.inicio_paro.desc()).first()
    if not paro:
        raise HTTPException(status_code=404, detail="No hay paro activo")
    try:
        paro.fin_paro = datetime.now()
        paro.duracion_segundos = round((paro.fin_paro - paro.inicio_paro).total_seconds(), 2)
        db.commit()
        logger.info(f"Paro finalizado: {paro.duracion_segundos}s — sesión {datos.sesion_id}")
        return {"paro_id": paro.id, "mensaje": "Paro finalizado", "duracion_segundos": paro.duracion_segundos}
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/paro/finalizar: {e}")
        raise HTTPException(status_code=500, detail="Error interno")
