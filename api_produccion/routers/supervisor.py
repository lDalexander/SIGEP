from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session
from typing import Optional

from database import get_db, logger
from models import (
    SesionTrabajoDB, PalletDB, ParoMaquinaDB, PedidoBodegaDB, UsuarioDB,
    ComentarioTurnoDB, EntregaProactivaDB, VelocidadMaquinaDB, AdministradorDB,
)
from sqlalchemy import func
from datetime import datetime, timedelta

from routers.admin import require_nivel, NIVELES_OPERATIVOS
from services import sesiones_usuario, notificaciones_pedido

# Cualquier admin operativo (planta, bodega o superadmin) puede actuar desde la tablet
# de supervisión. El reparto fino por áreas vive en la web; aquí el supervisor de turno
# es uno solo y necesita poder cerrar lo que tenga delante.
require_supervisor = require_nivel(*NIVELES_OPERATIVOS)

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
            # El producto real va DELANTE (2026-08-27): en la linea liquida, cloro y
            # suavizante pueden compartir marca y gramaje, y sin esto el supervisor no
            # puede distinguir que esta corriendo la maquina. Se antepone en vez de
            # sustituir porque el campo es contrato congelado con la app y la marca y
            # la presentacion siguen haciendo falta.
            if sesion.producto:
                producto = f"{sesion.producto} · {producto}" if producto.strip() else sesion.producto
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
                # `sesion_id` es NUEVO (v1.7.0) y es lo que permite que el panel del
                # supervisor sea pulsable: sin el id no había forma de pedir el detalle
                # de la máquina que se toca. Campo añadido, nada se quita.
                "sesion_id": sesion.id,
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


# ============================================================================
# Torre de control de la v1.7.0
# ============================================================================

@router.get("/insumistas")
def insumistas_de_turno(db: Session = Depends(get_db)):
    """Quién está de turno en bodega ahora mismo, y con qué carga.

    Antes no se podía saber: no existía ningún registro de la sesión de un insumista.
    El supervisor veía los pedidos pero no a las personas, así que no tenía forma de
    decidir a quién pedirle algo ni de notar que una bodega se había quedado sin nadie.
    """
    try:
        filas = sesiones_usuario.de_turno(db)
        inicio_dia = datetime.combine(datetime.now().date(), datetime.min.time())

        resultado = []
        for sesion, usuario in filas:
            activos = db.query(func.count(PedidoBodegaDB.id)).filter(
                PedidoBodegaDB.insumista_id == usuario.id,
                PedidoBodegaDB.estado.in_(["En Camino", "Entregado_Insumista"]),
            ).scalar() or 0

            entregas = db.query(func.count(EntregaProactivaDB.id)).filter(
                EntregaProactivaDB.insumista_id == usuario.id,
                EntregaProactivaDB.fecha_hora >= inicio_dia,
            ).scalar() or 0

            resultado.append({
                "usuario_id": usuario.id,
                "nombre": usuario.nombre,
                "rol": usuario.rol,
                "bodega": "GRANEL" if "granel" in (usuario.rol or "").lower() else "EMPAQUE",
                "tablet": sesiones_usuario.describir_tablet(db, sesion.device_id),
                "desde": sesion.inicio.strftime("%H:%M") if sesion.inicio else None,
                # Minutos sin dar señales. Es lo que distingue «está trabajando» de
                # «dejó la tablet encendida encima de una caja».
                "inactivo_min": sesiones_usuario._minutos_desde(sesion.ultimo_visto),
                "pedidos_activos": int(activos),
                "entregas_hoy": int(entregas),
            })
        return resultado
    except Exception as e:
        logger.error(f"Error en /supervisor/insumistas: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.get("/sesion/{sesion_id}/detalle")
def detalle_de_sesion(sesion_id: int, db: Session = Depends(get_db)):
    """Todo lo de un turno en una sola llamada: es la ventana emergente del panel.

    Va en UNA llamada y no en cinco porque la abre un dedo sobre una tablet: encadenar
    peticiones aquí se nota como una pantalla que tarda en llenarse a trozos.
    """
    sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == sesion_id).first()
    if sesion is None:
        raise HTTPException(status_code=404, detail="Sesión no encontrada")
    try:
        pacas = db.query(func.sum(PalletDB.cantidad_pacas)).filter(
            PalletDB.session_id == sesion_id
        ).scalar() or 0

        paros = db.query(ParoMaquinaDB).filter(
            ParoMaquinaDB.session_id == sesion_id
        ).order_by(ParoMaquinaDB.inicio_paro.desc()).all()
        paro_abierto = next((p for p in paros if p.fin_paro is None), None)
        seg_parado = sum(int(p.duracion_segundos or 0) for p in paros)

        ultima_vel = db.query(VelocidadMaquinaDB).filter(
            VelocidadMaquinaDB.session_id == sesion_id
        ).order_by(VelocidadMaquinaDB.id.desc()).first()

        pedidos = db.query(PedidoBodegaDB).filter(
            PedidoBodegaDB.session_id == sesion_id
        ).order_by(PedidoBodegaDB.fecha_solicitud.desc()).all()

        nombres = {}
        ids = {p.insumista_id for p in pedidos if p.insumista_id}
        if ids:
            nombres = {u.id: u.nombre for u in db.query(UsuarioDB).filter(UsuarioDB.id.in_(ids)).all()}

        comentarios = db.query(ComentarioTurnoDB).filter(
            ComentarioTurnoDB.session_id == sesion_id
        ).order_by(ComentarioTurnoDB.creado_en.desc()).limit(10).all()

        def _hm(dt):
            return dt.strftime("%H:%M") if dt else None

        return {
            "sesion_id": sesion.id,
            "maquina": sesion.maquina,
            "operador": sesion.operador,
            "producto": sesion.producto,
            "marca": sesion.marca,
            "presentacion": sesion.presentacion,
            "fragancia": sesion.fragancia,
            "inicio": _hm(sesion.inicio_turno),
            "fin": _hm(sesion.fin_turno),
            "activa": sesion.fin_turno is None,
            "pacas": int(pacas),
            "velocidad": ultima_vel.velocidad if ultima_vel else None,
            "en_paro": paro_abierto is not None,
            "motivo_paro": paro_abierto.motivo if paro_abierto else None,
            "paros": len(paros),
            "minutos_parado": round(seg_parado / 60.0, 1),
            "observaciones": sesion.observaciones,
            "pedidos": [
                {
                    "id": p.id,
                    "insumo": p.detalle_pedido,
                    "cantidad": p.cantidad_solicitada,
                    "entregada": p.cantidad_entregada,
                    "recibida": p.cantidad_recibida,
                    "estado": p.estado,
                    "insumista": nombres.get(p.insumista_id),
                    "hora": _hm(p.fecha_solicitud),
                    # Lo que el supervisor puede cerrar en lugar del operario.
                    "por_confirmar": p.estado == "Entregado_Insumista",
                }
                for p in pedidos
            ],
            "comentarios": [
                {"texto": c.texto, "hora": _hm(c.creado_en)} for c in comentarios
            ],
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error en /supervisor/sesion/{sesion_id}/detalle: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


class ConfirmarSupervisorIn(BaseModel):
    cantidad_recibida: Optional[int] = None


@router.put("/pedidos/{pedido_id}/confirmar")
def confirmar_en_lugar_del_operario(
    pedido_id: int,
    datos: ConfirmarSupervisorIn,
    db: Session = Depends(get_db),
    ctx=Depends(require_supervisor),
):
    """El supervisor cierra un pedido que el operario no estaba para confirmar.

    Un pedido entregado se queda esperando al operario, y si no está en la máquina —se
    fue a almorzar, cambió el turno— ahí se queda: el insumista lo ve colgado en su
    bandeja y la cuenta de tiempos del dashboard nunca cierra.

    QUEDA ESCRITO QUIÉN LO CONFIRMÓ. Tiene que distinguirse de una confirmación del
    operario, porque no lo es: nadie en la máquina dijo haber recibido nada. Sin esa
    marca, el histórico diría que el operario lo confirmó y eso sería falso.
    """
    pedido = db.query(PedidoBodegaDB).filter(PedidoBodegaDB.id == pedido_id).first()
    if pedido is None:
        raise HTTPException(status_code=404, detail="El pedido no existe")
    if pedido.estado != "Entregado_Insumista":
        raise HTTPException(
            status_code=400,
            detail=f"El pedido no está pendiente de confirmación (estado: {pedido.estado})",
        )
    try:
        pedido.estado = "Entregado"
        pedido.fecha_confirmacion = datetime.now()
        pedido.cantidad_recibida = (
            datos.cantidad_recibida if datos.cantidad_recibida is not None
            else (pedido.cantidad_entregada
                  if pedido.cantidad_entregada is not None else pedido.cantidad_solicitada)
        )
        pedido.confirmado_por_tipo = "SUPERVISOR"
        admin = db.query(AdministradorDB).filter(
            AdministradorDB.username == (ctx or {}).get("username")
        ).first()
        pedido.confirmado_por_id = admin.id if admin else None
        db.commit()

        notificaciones_pedido.notificar(
            db, notificaciones_pedido.CONFIRMADO, pedido,
            descripcion="Confirmado por el supervisor",
        )
        logger.info(f"✅ Pedido {pedido_id} confirmado por el supervisor {(ctx or {}).get('username')}")
        return {"mensaje": "Recepción confirmada por el supervisor"}
    except Exception as e:
        db.rollback()
        logger.error(f"Error al confirmar el pedido {pedido_id}: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


class VerificarEntregaIn(BaseModel):
    estado: str
    observaciones: Optional[str] = None


@router.put("/entregas/{entrega_id}/verificar")
def verificar_entrega(
    entrega_id: int,
    datos: VerificarEntregaIn,
    db: Session = Depends(get_db),
    ctx=Depends(require_supervisor),
):
    """El supervisor da por buena (o rechaza) una entrega proactiva.

    Una entrega sin pedido previo no tiene a nadie al otro lado que acuse recibo: el
    insumista deja el material, saca la foto y ahí se acaba. Esto cierra el círculo.

    Una entrega RECHAZADA deja de contar para la cobertura del plan, que es lo que
    impide que un plan aparezca cubierto con material que no estaba donde decía.
    """
    if datos.estado not in ("Verificada", "Rechazada", "Registrada"):
        raise HTTPException(status_code=400, detail="Estado inválido: use Verificada o Rechazada")

    entrega = db.query(EntregaProactivaDB).filter(EntregaProactivaDB.id == entrega_id).first()
    if entrega is None:
        raise HTTPException(status_code=404, detail="La entrega no existe")
    try:
        admin = db.query(AdministradorDB).filter(
            AdministradorDB.username == (ctx or {}).get("username")
        ).first()
        entrega.estado = datos.estado
        entrega.verificado_por = admin.id if admin else None
        entrega.fecha_verificacion = datetime.now()
        if datos.observaciones:
            previo = (entrega.observaciones or "").strip()
            nota = f"[supervisor] {datos.observaciones}"
            entrega.observaciones = (f"{previo} · {nota}" if previo else nota)[:500]
        db.commit()
        logger.info(f"🔍 Entrega {entrega_id} marcada «{datos.estado}» por {(ctx or {}).get('username')}")
        return {"mensaje": f"Entrega marcada como {datos.estado}"}
    except Exception as e:
        db.rollback()
        logger.error(f"Error al verificar la entrega {entrega_id}: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.get("/entregas_pendientes")
def entregas_sin_verificar(db: Session = Depends(get_db)):
    """Entregas proactivas que el supervisor todavía no ha revisado (últimas 24 h)."""
    try:
        limite = datetime.now() - timedelta(hours=24)
        filas = db.query(EntregaProactivaDB).filter(
            EntregaProactivaDB.fecha_hora >= limite,
            EntregaProactivaDB.estado == "Registrada",
        ).order_by(EntregaProactivaDB.fecha_hora.desc()).all()

        ids = {e.insumista_id for e in filas if e.insumista_id}
        nombres = {}
        if ids:
            nombres = {u.id: u.nombre for u in db.query(UsuarioDB).filter(UsuarioDB.id.in_(ids)).all()}

        return [
            {
                "id": e.id,
                "insumo": e.insumo,
                "cantidad": e.cantidad,
                "maquina": e.maquina,
                "plan_id": e.plan_id,
                "insumista": nombres.get(e.insumista_id),
                "observaciones": e.observaciones,
                "foto_url": e.foto_path,
                "fecha_hora": e.fecha_hora.strftime("%Y-%m-%d %H:%M") if e.fecha_hora else None,
            }
            for e in filas
        ]
    except Exception as e:
        logger.error(f"Error en /supervisor/entregas_pendientes: {e}")
        raise HTTPException(status_code=500, detail="Error interno")
