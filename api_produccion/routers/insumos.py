"""
Router para los endpoints del sistema MES (Insumos).
Permite la comunicación estilo "Uber" entre operadores de línea y personal de bodega.
"""
import os
from fastapi import APIRouter, Depends, HTTPException, BackgroundTasks, Form, File, UploadFile, Query
from sqlalchemy.orm import Session
from sqlalchemy import func
from datetime import datetime, timedelta
from database import get_db, logger
from models import PedidoBodegaDB, SesionTrabajoDB, MaquinaDB, EntregaProactivaDB, UsuarioDB
from schemas import NuevoPedidoRequest, PedidoAccionRequest
import schemas
import models
from ws_manager import manager
from services.fcm_service import notificar_insumistas_por_fcm
from services.email_service import notificar_pedido_insumo

ENTREGAS_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "static", "entregas"
)
os.makedirs(ENTREGAS_DIR, exist_ok=True)

router = APIRouter(prefix="/api/insumos", tags=["Insumos MES"])

@router.post("/pedido_dinamico")
def crear_pedido_dinamico(
    datos: NuevoPedidoRequest, 
    background_tasks: BackgroundTasks, # 🔥 1. Agregamos esto aquí
    db: Session = Depends(get_db)
):
    """Crea un pedido desde la tablet en piso hacia la bodega"""
    try:
        texto_pedido = datos.detalle_pedido.lower()
        keywords_granel = ["granel", "preten", "polvo"]
        es_granel = any(palabra in texto_pedido for palabra in keywords_granel)
        categoria_asignada = "Granel" if es_granel else "Empaque"

        nuevo_pedido = PedidoBodegaDB(
            session_id=datos.sesion_id,
            detalle_pedido=datos.detalle_pedido,
            cantidad_solicitada=datos.cantidad,
            categoria=categoria_asignada
        )
        db.add(nuevo_pedido)
        db.commit()
        db.refresh(nuevo_pedido)

        sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == datos.sesion_id).first()
        maquina_txt = sesion.maquina if sesion else "?"
        operador_txt = sesion.operador if sesion else "?"

        # 🔥 2. Usamos FastAPI nativo para enviar el mensaje asíncrono sin bloquear la BD
        background_tasks.add_task(
            manager.broadcast_to_tipo,
            categoria_asignada.upper(),
            {"evento": "nuevo_pedido", "solicitud_id": nuevo_pedido.id, "descripcion": nuevo_pedido.detalle_pedido}
        )

        # 🔔 3. Notificación FCM data-only para tablets en background/cerradas.
        background_tasks.add_task(
            notificar_insumistas_por_fcm,
            categoria_asignada.upper(),
            f"Nuevo pedido — {maquina_txt}",
            f"{datos.cantidad} x {datos.detalle_pedido} (operador: {operador_txt})",
            nuevo_pedido.id,
        )

        # 📧 4. Correo a administración/bodega (asíncrono y tolerante a fallos).
        background_tasks.add_task(
            notificar_pedido_insumo,
            maquina_txt, operador_txt, datos.detalle_pedido, datos.cantidad,
            categoria_asignada, nuevo_pedido.id,
        )

        logger.info(f"📦 PEDIDO ENRUTADO A [{categoria_asignada}]: {datos.cantidad} x {datos.detalle_pedido}")
        return {"mensaje": "Pedido enviado a bodega"}
        
    except Exception as e:
        db.rollback()
        logger.error(f"Error al crear pedido: {e}")
        raise HTTPException(status_code=500, detail="Error interno")
@router.get("/pendientes/{rol}")
def obtener_pedidos_pendientes(rol: str, db: Session = Depends(get_db)):
    """Obtiene los pedidos según el rol del insumista"""
    try:
        rol_seguro = rol.lower()
        if "granel" in rol_seguro or "mezcla" in rol_seguro:
            categoria_buscada = "Granel"
        else:
            categoria_buscada = "Empaque"
            
        query = db.query(PedidoBodegaDB, SesionTrabajoDB)\
                  .join(SesionTrabajoDB, SesionTrabajoDB.id == PedidoBodegaDB.session_id)\
                  .filter(PedidoBodegaDB.estado == "Pendiente")
                  
        if "supervisor" not in rol_seguro:
            query = query.filter(PedidoBodegaDB.categoria == categoria_buscada)
            
        pedidos = query.order_by(PedidoBodegaDB.fecha_solicitud.asc()).all()

        resultados = []
        for pedido, sesion in pedidos:
            resultados.append({
                "id": pedido.id,
                "maquina": sesion.maquina,
                "operador": sesion.operador,
                "detalle": pedido.detalle_pedido,
                "cantidad": pedido.cantidad_solicitada,
                "hora": pedido.fecha_solicitud.strftime("%H:%M:%S")
            })
        return resultados
    except Exception as e:
        logger.error(f"Error al obtener pendientes: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.put("/aceptar")
def aceptar_pedido(
    datos: schemas.PedidoAccionRequest, 
    background_tasks: BackgroundTasks, # 🔥 1. Agregamos el BackgroundTasks
    db: Session = Depends(get_db)
):
    pedido = db.query(models.PedidoBodegaDB).filter(models.PedidoBodegaDB.id == datos.pedido_id).first()
    
    if not pedido:
        raise HTTPException(status_code=404, detail="El pedido no existe")

    if pedido.estado.strip().lower() != "pendiente":
        raise HTTPException(status_code=400, detail=f"Estado actual: {pedido.estado}")

    pedido.estado = "En Camino"
    pedido.insumista_id = datos.insumista_id
    pedido.fecha_aceptacion = datetime.now()
    
    db.commit()

    # 🔥 2. EL GRITO GLOBAL: Le avisamos a todas las tablets de este canal que el pedido fue tomado
    background_tasks.add_task(
        manager.broadcast_to_tipo,
        pedido.categoria.upper(),
        {"evento": "pedido_aceptado", "solicitud_id": pedido.id}
    )

    return {"status": "ok", "message": "Pedido aceptado", "mensaje": "Pedido aceptado"}

@router.put("/entregar")
def entregar_pedido(datos: PedidoAccionRequest, db: Session = Depends(get_db)):
    pedido = db.query(models.PedidoBodegaDB).filter(models.PedidoBodegaDB.id == datos.pedido_id).first()

    if not pedido or pedido.estado != "En Camino":
        raise HTTPException(status_code=400, detail="El pedido no está en camino")

    pedido.estado = "Entregado_Insumista"
    pedido.fecha_entrega = datetime.now()
    pedido.cantidad_entregada = (
        datos.cantidad_entregada if datos.cantidad_entregada is not None else pedido.cantidad_solicitada
    )

    db.commit()
    return {"status": "ok", "message": "Entrega registrada, esperando confirmación del operador", "mensaje": "Entrega registrada, esperando confirmación del operador"}

@router.put("/confirmar_recepcion")
def confirmar_recepcion(datos: schemas.ConfirmarRecepcionRequest, db: Session = Depends(get_db)):
    """El operador confirma que recibió el insumo, cerrando definitivamente el pedido."""
    pedido = db.query(models.PedidoBodegaDB).filter(models.PedidoBodegaDB.id == datos.pedido_id).first()

    if not pedido:
        raise HTTPException(status_code=404, detail="Pedido no encontrado")
    if pedido.estado != "Entregado_Insumista":
        raise HTTPException(status_code=400, detail=f"El pedido no está pendiente de confirmación. Estado actual: {pedido.estado}")
    if pedido.session_id != datos.sesion_id:
        raise HTTPException(status_code=403, detail="Esta sesión no corresponde al pedido")

    pedido.estado = "Entregado"
    pedido.fecha_confirmacion = datetime.now()
    pedido.cantidad_recibida = (
        datos.cantidad_recibida if datos.cantidad_recibida is not None
        else (pedido.cantidad_entregada if pedido.cantidad_entregada is not None else pedido.cantidad_solicitada)
    )

    db.commit()
    return {"status": "ok", "message": "Recepción confirmada", "mensaje": "Recepción confirmada"}

@router.get("/pendientes_confirmacion/{sesion_id}")
def pedidos_pendientes_confirmacion(sesion_id: int, db: Session = Depends(get_db)):
    """Pedidos en estado Entregado_Insumista que el operador aún no ha confirmado."""
    try:
        pedidos = db.query(models.PedidoBodegaDB, models.SesionTrabajoDB)\
                    .join(models.SesionTrabajoDB, models.SesionTrabajoDB.id == models.PedidoBodegaDB.session_id)\
                    .filter(
                        models.PedidoBodegaDB.session_id == sesion_id,
                        models.PedidoBodegaDB.estado == "Entregado_Insumista"
                    )\
                    .order_by(models.PedidoBodegaDB.fecha_solicitud.asc()).all()

        return [
            {
                "id": p.id,
                "maquina": s.maquina,
                "operador": s.operador,
                "detalle": p.detalle_pedido,
                "cantidad": p.cantidad_solicitada,
                "cantidad_entregada": p.cantidad_entregada,
                "cantidad_recibida": p.cantidad_recibida,
                "hora": p.fecha_solicitud.strftime("%H:%M:%S"),
                "estado": p.estado,
            }
            for p, s in pedidos
        ]
    except Exception as e:
        logger.error(f"Error al obtener pendientes de confirmación: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.get("/asignados/{insumista_id}")
def obtener_pedidos_asignados(insumista_id: int, db: Session = Depends(get_db)):
    """Pedidos que un insumista tiene activos: en camino o esperando confirmación del operador."""
    try:
        pedidos = db.query(PedidoBodegaDB, SesionTrabajoDB)\
                    .join(SesionTrabajoDB, SesionTrabajoDB.id == PedidoBodegaDB.session_id)\
                    .filter(
                        PedidoBodegaDB.insumista_id == insumista_id,
                        PedidoBodegaDB.estado.in_(["En Camino", "Entregado_Insumista"])
                    )\
                    .order_by(PedidoBodegaDB.fecha_solicitud.asc()).all()

        return [
            {
                "id": p.id,
                "maquina": s.maquina,
                "operador": s.operador,
                "detalle": p.detalle_pedido,
                "cantidad": p.cantidad_solicitada,
                "cantidad_entregada": p.cantidad_entregada,
                "cantidad_recibida": p.cantidad_recibida,
                "hora": p.fecha_solicitud.strftime("%H:%M:%S"),
                "estado": p.estado,
            }
            for p, s in pedidos
        ]
    except Exception as e:
        logger.error(f"Error al obtener asignados: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.post("/entrega_proactiva", status_code=201)
def registrar_entrega_proactiva(
    insumista_id: int = Form(...),
    tipo_producto: str = Form(...),
    insumo: str = Form(...),
    cantidad: int = Form(...),
    maquina: str = Form(...),
    observaciones: str = Form(None),
    foto: UploadFile = File(None),
    db: Session = Depends(get_db)
):
    """Registra una entrega de insumo sin pedido previo (planeación), con foto opcional."""
    if tipo_producto not in ("Sólido", "Líquido"):
        raise HTTPException(status_code=400, detail="tipo_producto debe ser 'Sólido' o 'Líquido'")

    maquina_db = db.query(MaquinaDB).filter(
        MaquinaDB.nombre == maquina, MaquinaDB.activa.is_(True)
    ).first()
    if not maquina_db:
        raise HTTPException(status_code=404, detail=f"Máquina '{maquina}' no encontrada o inactiva")

    foto_content = None
    foto_ext = None
    if foto and foto.filename:
        if foto.content_type not in ("image/jpeg", "image/png"):
            raise HTTPException(status_code=400, detail="La foto debe ser JPG o PNG")
        foto_content = foto.file.read()
        if len(foto_content) > 5 * 1024 * 1024:
            raise HTTPException(status_code=400, detail="La foto no debe superar 5 MB")
        foto_ext = "jpg" if foto.content_type == "image/jpeg" else "png"

    try:
        nueva_entrega = EntregaProactivaDB(
            insumista_id=insumista_id,
            tipo_producto=tipo_producto,
            insumo=insumo,
            cantidad=cantidad,
            maquina=maquina,
            observaciones=observaciones,
        )
        db.add(nueva_entrega)
        db.commit()
        db.refresh(nueva_entrega)

        foto_url = None
        if foto_content is not None:
            timestamp = nueva_entrega.fecha_hora.strftime("%Y%m%d%H%M%S")
            filename = f"{nueva_entrega.id}_{insumista_id}_{timestamp}.{foto_ext}"
            with open(os.path.join(ENTREGAS_DIR, filename), "wb") as f:
                f.write(foto_content)
            nueva_entrega.foto_path = f"/static/entregas/{filename}"
            db.commit()
            foto_url = nueva_entrega.foto_path

        logger.info(f"📸 ENTREGA PROACTIVA: {cantidad} x {insumo} → {maquina} (insumista {insumista_id})")
        return {"id": nueva_entrega.id, "mensaje": "Entrega registrada correctamente", "foto_url": foto_url}

    except HTTPException:
        raise
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /entrega_proactiva: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.get("/entregas_proactivas/{insumista_id}")
def obtener_entregas_proactivas(insumista_id: int, db: Session = Depends(get_db)):
    """Últimas 20 entregas proactivas registradas por un insumista."""
    try:
        entregas = db.query(EntregaProactivaDB)\
                     .filter(EntregaProactivaDB.insumista_id == insumista_id)\
                     .order_by(EntregaProactivaDB.fecha_hora.desc())\
                     .limit(20).all()
        return [
            {
                "id": e.id,
                "insumista_id": e.insumista_id,
                "tipo_producto": e.tipo_producto,
                "insumo": e.insumo,
                "cantidad": e.cantidad,
                "maquina": e.maquina,
                "observaciones": e.observaciones,
                "foto_path": e.foto_path,
                "fecha_hora": e.fecha_hora.strftime("%Y-%m-%d %H:%M:%S"),
            }
            for e in entregas
        ]
    except Exception as e:
        logger.error(f"Error en /entregas_proactivas/{insumista_id}: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


# ============================================================================
# DASHBOARD DE INSUMOS (vista web abierta de solo lectura)
# ============================================================================
# Agrega, para un rango de fechas: tiempos de reacción del flujo de pedidos
# (solicitud → aceptación → entrega → recepción), comparación de cantidades
# (solicitada / entregada / recibida) y las entregas proactivas con su evidencia
# fotográfica. Sin auth (igual que el dashboard de producción).

def _rango_insumos(desde, hasta):
    """desde/hasta (YYYY-MM-DD) -> (d, h, inicio_dt, fin_dt_exclusivo). Default: hoy."""
    hoy = datetime.now().date()
    try:
        d = datetime.strptime(desde, "%Y-%m-%d").date() if desde else hoy
    except ValueError:
        d = hoy
    try:
        h = datetime.strptime(hasta, "%Y-%m-%d").date() if hasta else hoy
    except ValueError:
        h = hoy
    if h < d:
        d, h = h, d
    inicio = datetime.combine(d, datetime.min.time())
    fin = datetime.combine(h, datetime.min.time()) + timedelta(days=1)
    return d, h, inicio, fin


def _delta_seg(a, b):
    """Segundos entre dos datetimes (b - a) o None si falta alguno o sale negativo."""
    if not a or not b:
        return None
    s = (b - a).total_seconds()
    return int(s) if s >= 0 else None


@router.get("/dashboard")
def dashboard_insumos(desde: str = Query(None), hasta: str = Query(None), db: Session = Depends(get_db)):
    """Datos del dashboard de insumos: pedidos (tiempos + cantidades) y entregas proactivas."""
    try:
        d, h, inicio, fin = _rango_insumos(desde, hasta)

        # --- Pedidos del rango (por fecha de solicitud) + sesión + insumista ---
        filas = (
            db.query(PedidoBodegaDB, SesionTrabajoDB)
            .outerjoin(SesionTrabajoDB, SesionTrabajoDB.id == PedidoBodegaDB.session_id)
            .filter(PedidoBodegaDB.fecha_solicitud >= inicio, PedidoBodegaDB.fecha_solicitud < fin)
            .order_by(PedidoBodegaDB.fecha_solicitud.asc())
            .all()
        )
        insumista_ids = {p.insumista_id for p, _ in filas if p.insumista_id is not None}
        nombres = {}
        if insumista_ids:
            for u in db.query(UsuarioDB).filter(UsuarioDB.id.in_(insumista_ids)).all():
                nombres[u.id] = u.nombre

        def _hms(dt):
            return dt.strftime("%H:%M:%S") if dt else None

        pedidos = []
        suma_resp = 0
        n_resp = 0
        con_discrepancia = 0
        for p, s in filas:
            seg_total = _delta_seg(p.fecha_solicitud, p.fecha_confirmacion) \
                or _delta_seg(p.fecha_solicitud, p.fecha_entrega)
            seg_sol_ent = _delta_seg(p.fecha_solicitud, p.fecha_entrega)
            if seg_sol_ent is not None:
                suma_resp += seg_sol_ent
                n_resp += 1
            ent, rec, sol = p.cantidad_entregada, p.cantidad_recibida, p.cantidad_solicitada
            hay_disc = (
                (ent is not None and rec is not None and ent != rec)
                or (ent is not None and sol is not None and ent != sol)
            )
            if hay_disc:
                con_discrepancia += 1
            pedidos.append({
                "id": p.id,
                "maquina": s.maquina if s else None,
                "operador": s.operador if s else None,
                "insumo": p.detalle_pedido,
                "categoria": p.categoria,
                "insumista": nombres.get(p.insumista_id) if p.insumista_id else None,
                "estado": p.estado,
                "hora_solicitud": _hms(p.fecha_solicitud),
                "hora_aceptacion": _hms(p.fecha_aceptacion),
                "hora_entrega": _hms(p.fecha_entrega),
                "hora_confirmacion": _hms(p.fecha_confirmacion),
                "seg_aceptacion": _delta_seg(p.fecha_solicitud, p.fecha_aceptacion),
                "seg_entrega": _delta_seg(p.fecha_aceptacion, p.fecha_entrega),
                "seg_recepcion": _delta_seg(p.fecha_entrega, p.fecha_confirmacion),
                "seg_total": seg_total,
                "solicitada": sol,
                "entregada": ent,
                "recibida": rec,
            })

        # --- Entregas proactivas del rango (con evidencia fotográfica) ---
        entregas_q = (
            db.query(EntregaProactivaDB)
            .filter(EntregaProactivaDB.fecha_hora >= inicio, EntregaProactivaDB.fecha_hora < fin)
            .order_by(EntregaProactivaDB.fecha_hora.desc())
            .all()
        )
        ep_ids = {e.insumista_id for e in entregas_q if e.insumista_id is not None}
        ep_nombres = {}
        if ep_ids:
            for u in db.query(UsuarioDB).filter(UsuarioDB.id.in_(ep_ids)).all():
                ep_nombres[u.id] = u.nombre
        entregas = [
            {
                "id": e.id,
                "insumista": ep_nombres.get(e.insumista_id) if e.insumista_id else None,
                "tipo_producto": e.tipo_producto,
                "insumo": e.insumo,
                "cantidad": e.cantidad,
                "maquina": e.maquina,
                "observaciones": e.observaciones,
                "foto_url": e.foto_path,  # /static/entregas/...  (None si no hay)
                "fecha_hora": e.fecha_hora.strftime("%Y-%m-%d %H:%M:%S") if e.fecha_hora else None,
            }
            for e in entregas_q
        ]

        return {
            "rango": {"desde": d.isoformat(), "hasta": h.isoformat()},
            "kpis": {
                "total_pedidos": len(pedidos),
                "tiempo_resp_prom_seg": int(suma_resp / n_resp) if n_resp else None,
                "con_discrepancia": con_discrepancia,
                "entregas_proactivas": len(entregas),
            },
            "pedidos": pedidos,
            "entregas": entregas,
        }
    except Exception as e:
        logger.error(f"Error en /api/insumos/dashboard: {e}")
        raise HTTPException(status_code=500, detail="Error al generar dashboard de insumos")
