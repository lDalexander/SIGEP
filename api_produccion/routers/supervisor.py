from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from database import get_db, logger
from models import SesionTrabajoDB, PalletDB, ParoMaquinaDB, PedidoBodegaDB, UsuarioDB
from sqlalchemy import func
from datetime import datetime, timedelta

router = APIRouter(prefix="/api/supervisor", tags=["Torre de Control (Supervisor)"])

@router.get("/planta/estado_realtime")
def obtener_estado_planta(db: Session = Depends(get_db)):
    """Estado de Planta en Tiempo Real para la App de Supervisores"""
    try:
        # Sesiones activas (sin fin de turno)
        sesiones_activas = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.fin_turno == None).all()
        
        resultado = []
        for sesion in sesiones_activas:
            # Formatear producto: Marca Presentación (Fragancia)
            producto_partes = []
            if sesion.marca: producto_partes.append(sesion.marca)
            if sesion.presentacion: producto_partes.append(sesion.presentacion)
            producto_base = " ".join(producto_partes)
            
            producto = f"{producto_base} ({sesion.fragancia})" if sesion.fragancia else producto_base
            if not producto.strip():
                producto = "Desconocido"
            
            # Tiempo inicio "06:45 AM"
            tiempo_inicio = sesion.inicio_turno.strftime("%I:%M %p") if sesion.inicio_turno else ""
            
            # Pacas totales
            pacas_totales = db.query(func.sum(PalletDB.cantidad_pacas)).filter(PalletDB.session_id == sesion.id).scalar() or 0
            
            # Estado (ACTIVA o PARO)
            paro_activo = db.query(ParoMaquinaDB).filter(
                ParoMaquinaDB.session_id == sesion.id,
                ParoMaquinaDB.fin_paro == None
            ).first()
            estado = "PARO" if paro_activo else "ACTIVA"
            
            resultado.append({
                "maquina": sesion.maquina,
                "operador": sesion.operador,
                "producto": producto,
                "tiempo_inicio": tiempo_inicio,
                "pacas_totales": int(pacas_totales),
                "estado": estado
            })
            
        return resultado
    except Exception as e:
        logger.error(f"Error en estado_realtime: {e}")
        raise HTTPException(status_code=500, detail="Error interno del servidor")

@router.get("/bodega/historial_pedidos")
def obtener_historial_pedidos(db: Session = Depends(get_db)):
    """Historial de Logística (Bodega) de las últimas 24 horas"""
    try:
        limite_24h = datetime.now() - timedelta(hours=24)
        
        # Pedidos de las últimas 24 horas
        pedidos = db.query(PedidoBodegaDB).filter(
            PedidoBodegaDB.fecha_solicitud >= limite_24h
        ).order_by(PedidoBodegaDB.fecha_solicitud.desc()).all()
        
        resultado = []
        for pedido in pedidos:
            # Obtener nombre de la máquina desde la sesión
            sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == pedido.session_id).first()
            maquina = sesion.maquina if sesion else "Desconocida"
            
            # Solicitado a (Ej: BODEGA_QUIMICOS, BODEGA_EMPAQUE)
            solicitado_a = f"BODEGA_{pedido.categoria.upper()}" if pedido.categoria else "BODEGA_GENERAL"
            
            # Aceptado por
            aceptado_por = "N/A"
            if pedido.insumista_id:
                insumista = db.query(UsuarioDB).filter(UsuarioDB.id == pedido.insumista_id).first()
                if insumista:
                    aceptado_por = insumista.nombre
                    
            # Entregado en "2023-10-27 14:30:00"
            entregado_en = pedido.fecha_entrega.strftime("%Y-%m-%d %H:%M:%S") if pedido.fecha_entrega else None
            
            # Tiempo de respuesta "12 min"
            tiempo_respuesta = "N/A"
            if pedido.fecha_entrega and pedido.fecha_solicitud:
                diff = pedido.fecha_entrega - pedido.fecha_solicitud
                minutos = int(diff.total_seconds() / 60)
                tiempo_respuesta = f"{minutos} min"
                
            # Status: mapeado a NUEVO, ACEPTADO, ENTREGADO
            status_map = {
                "Pendiente": "NUEVO",
                "En Camino": "ACEPTADO",
                "Entregado": "ENTREGADO"
            }
            status = status_map.get(pedido.estado, "NUEVO")
            
            resultado.append({
                "id": pedido.id,
                "maquina": maquina,
                "insumo": pedido.detalle_pedido,
                "cantidad": pedido.cantidad_solicitada,
                "cantidad_entregada": pedido.cantidad_entregada,
                "cantidad_recibida": pedido.cantidad_recibida,
                "solicitado_a": solicitado_a,
                "aceptado_por": aceptado_por,
                "entregado_en": entregado_en,
                "tiempo_respuesta": tiempo_respuesta,
                "status": status
            })
            
        return resultado
    except Exception as e:
        logger.error(f"Error en historial_pedidos: {e}")
        raise HTTPException(status_code=500, detail="Error interno del servidor")
