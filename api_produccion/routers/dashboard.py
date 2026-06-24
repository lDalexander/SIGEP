"""
Router para los endpoints del Dashboard.
Proporciona KPIs, estado operativo y gráficas de producción para los supervisores.
"""
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from sqlalchemy import func, case, or_
from datetime import datetime, timedelta
import pandas as pd
from io import BytesIO
from fastapi.responses import StreamingResponse

from database import get_db, logger
from models import PalletDB, SesionTrabajoDB

router = APIRouter(prefix="/api/dashboard", tags=["Dashboard"])


def _rango(desde, hasta):
    """(desde, hasta) en YYYY-MM-DD -> (inicio_dt, fin_dt_exclusivo). Default: hoy.

    Permite que TODO el dashboard muestre cualquier rango de fechas, no solo hoy.
    Si no se envían parámetros, equivale al día actual (comportamiento en vivo)."""
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
    return datetime.combine(d, datetime.min.time()), datetime.combine(h, datetime.min.time()) + timedelta(days=1)

@router.get("/kpis")
def obtener_kpis(desde: str = Query(None), hasta: str = Query(None), db: Session = Depends(get_db)):
    """KPIs (pacas/sacos/turnos activos) del rango seleccionado (default: hoy)."""
    try:
        inicio, fin = _rango(desde, hasta)
        # Las presentaciones de 15/25 Kg NO son pacas, son sacos -> se cuentan aparte.
        norm_pres = func.replace(func.upper(func.coalesce(SesionTrabajoDB.presentacion, "")), " ", "")
        es_saco = or_(norm_pres.like("%15KG%"), norm_pres.like("%25KG%"))
        fila = (
            db.query(
                func.coalesce(func.sum(case((es_saco, 0), else_=PalletDB.cantidad_pacas)), 0).label("pacas"),
                func.coalesce(func.sum(case((es_saco, PalletDB.cantidad_pacas), else_=0)), 0).label("sacos"),
            )
            .outerjoin(SesionTrabajoDB, SesionTrabajoDB.id == PalletDB.session_id)
            .filter(PalletDB.fecha_hora >= inicio, PalletDB.fecha_hora < fin)
            .first()
        )
        pacas_hoy = int(fila.pacas or 0) if fila else 0
        sacos_hoy = int(fila.sacos or 0) if fila else 0
        turnos_activos = (
            db.query(func.count(SesionTrabajoDB.id))
            .filter(SesionTrabajoDB.fin_turno.is_(None),
                    SesionTrabajoDB.inicio_turno >= inicio, SesionTrabajoDB.inicio_turno < fin)
            .scalar()
        )
        return {
            "pallets_hoy": pacas_hoy + sacos_hoy,
            "pacas_hoy": pacas_hoy,
            "sacos_hoy": sacos_hoy,
            "turnos_activos": int(turnos_activos),
            "eficiencia": "94.8%", 
        }
    except Exception as e:
        logger.error(f"Error en /dashboard/kpis: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener KPIs")

@router.get("/logs")
def obtener_logs_recientes(db: Session = Depends(get_db)):
    """Obtiene el historial reciente de pallets registrados."""
    try:
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
                "mensaje": f"PALLET REGISTRADO: {pallet.cantidad_pacas} pacas — {sesion.maquina} ({sesion.operador})",
                "tipo": "pallet",
            })
        return logs
    except Exception as e:
        logger.error(f"Error en /dashboard/logs: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener logs")

@router.get("/produccion_hora")
def obtener_produccion_hora(desde: str = Query(None), hasta: str = Query(None), db: Session = Depends(get_db)):
    """Producción por hora para la gráfica, con DETALLE por hora para el tooltip.

    Cada punto trae `detalle`: lista de {maquina, operario, producto, pacas} de lo
    producido en esa hora (agrupado por sesión). Para un rango de varios días, la
    hora agrega lo de esa hora del reloj a lo largo de todo el rango."""
    try:
        inicio, fin = _rango(desde, hasta)
        hora_col = func.extract("hour", PalletDB.fecha_hora)
        filas = (
            db.query(
                hora_col.label("hora"),
                SesionTrabajoDB.maquina.label("maquina"),
                SesionTrabajoDB.operador.label("operador"),
                SesionTrabajoDB.marca.label("marca"),
                SesionTrabajoDB.presentacion.label("presentacion"),
                SesionTrabajoDB.fragancia.label("fragancia"),
                func.coalesce(func.sum(PalletDB.cantidad_pacas), 0).label("pacas"),
            )
            .outerjoin(SesionTrabajoDB, SesionTrabajoDB.id == PalletDB.session_id)
            .filter(PalletDB.fecha_hora >= inicio, PalletDB.fecha_hora < fin)
            .group_by(hora_col, SesionTrabajoDB.maquina, SesionTrabajoDB.operador,
                      SesionTrabajoDB.marca, SesionTrabajoDB.presentacion, SesionTrabajoDB.fragancia)
            .order_by(hora_col)
            .all()
        )
        por_hora = {}
        for r in filas:
            hk = int(r.hora)
            bucket = por_hora.setdefault(hk, {"pallets": 0, "detalle": []})
            pacas = int(r.pacas or 0)
            bucket["pallets"] += pacas
            producto = " · ".join([x for x in [r.marca, r.presentacion, r.fragancia] if x]) or "—"
            bucket["detalle"].append({
                "maquina": r.maquina or "—",
                "operario": r.operador or "—",
                "producto": producto,
                "pacas": pacas,
            })
        data = []
        for hk in sorted(por_hora.keys()):
            b = por_hora[hk]
            b["detalle"].sort(key=lambda x: x["pacas"], reverse=True)
            data.append({"hora": f"{hk:02d}:00", "pallets": b["pallets"], "detalle": b["detalle"]})
        return data
    except Exception as e:
        logger.error(f"Error en /dashboard/produccion_hora: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener producción por hora")

@router.get("/estado_operativo")
def obtener_estado_operativo(desde: str = Query(None), hasta: str = Query(None), db: Session = Depends(get_db)):
    """Estado de las máquinas y turnos del rango seleccionado (default: hoy)."""
    try:
        inicio, fin = _rango(desde, hasta)
        sesiones = db.query(SesionTrabajoDB).filter(
            SesionTrabajoDB.inicio_turno >= inicio, SesionTrabajoDB.inicio_turno < fin
        ).order_by(SesionTrabajoDB.inicio_turno.desc()).all()
        resultados = []
        for s in sesiones:
            pallets_sesion = db.query(func.coalesce(func.sum(PalletDB.cantidad_pacas), 0)).filter(PalletDB.session_id == s.id).scalar()
            producto = f"{s.marca or ''} - {s.fragancia or ''} - {s.presentacion or ''}".strip(" -")
            
            if s.fin_turno:
                estado = "Finalizado"
                tiempo_transcurrido = int(s.duracion_minutos) if s.duracion_minutos else 0
            else:
                estado = "Activo"
                tiempo_transcurrido = int((datetime.now() - s.inicio_turno).total_seconds() / 60)
                
            resultados.append({
                "sesion_id": s.id, "maquina": s.maquina, "operador": s.operador, "producto": producto,
                "inicio_turno": s.inicio_turno.strftime("%H:%M:%S") if s.inicio_turno else "",
                "tiempo_transcurrido": tiempo_transcurrido, "total_pacas": int(pallets_sesion), "estado": estado
            })
        return resultados
    except Exception as e:
        logger.error(f"Error en /dashboard/estado_operativo: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener estado operativo")

@router.get("/top_produccion")
def obtener_top_produccion(desde: str = Query(None), hasta: str = Query(None), db: Session = Depends(get_db)):
    """Ranking de marcas más producidas en el rango seleccionado (default: hoy)."""
    try:
        inicio, fin = _rango(desde, hasta)
        resultados = (
            db.query(SesionTrabajoDB.marca, func.coalesce(func.sum(PalletDB.cantidad_pacas), 0).label("total"))
            .join(PalletDB, PalletDB.session_id == SesionTrabajoDB.id)
            .filter(PalletDB.fecha_hora >= inicio, PalletDB.fecha_hora < fin)
            .group_by(SesionTrabajoDB.marca)
            .order_by(func.sum(PalletDB.cantidad_pacas).desc())
            .all()
        )
        return [{"name": r.marca if r.marca else "NA", "value": int(r.total)} for r in resultados]
    except Exception as e:
        logger.error(f"Error en /dashboard/top_produccion: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener top produccion")


@router.get("/estadisticas")
def obtener_estadisticas(dim: str = "maquina", rango: str = "semana",
                         desde: str = Query(None), hasta: str = Query(None),
                         db: Session = Depends(get_db)):
    """Estadisticas de produccion agregadas por dimension y rango temporal.

    dim   : "maquina" | "operario" | "marca_presentacion" | "marca_presentacion_fragancia"
    Rango: si se envían desde/hasta (YYYY-MM-DD) se usan esas fechas (lo que permite
    que el dashboard comparta el mismo rango global); si no, se usa el preset `rango`
    ("hoy" | "semana" | "mes" | "todo").
    Devuelve {dim, rango, total_pacas, total_sesiones, items:[{etiqueta,pacas,sesiones,pct}]}.
    """
    try:
        ahora = datetime.now()
        desde_dt = None
        hasta_dt = None
        if desde or hasta:
            desde_dt, hasta_dt = _rango(desde, hasta)
        elif rango == "hoy":
            desde_dt = ahora.replace(hour=0, minute=0, second=0, microsecond=0)
        elif rango == "semana":
            desde_dt = ahora - timedelta(days=7)
        elif rango == "mes":
            desde_dt = ahora - timedelta(days=30)
        # "todo" -> sin filtro

        if dim == "operario":
            etiqueta = SesionTrabajoDB.operador
        elif dim == "marca_presentacion":
            etiqueta = func.concat(
                func.coalesce(SesionTrabajoDB.marca, "Sin marca"),
                " · ",
                func.coalesce(SesionTrabajoDB.presentacion, "Sin present."),
            )
        elif dim == "marca_presentacion_fragancia":
            etiqueta = func.concat(
                func.coalesce(SesionTrabajoDB.marca, "Sin marca"),
                " · ",
                func.coalesce(SesionTrabajoDB.presentacion, "Sin present."),
                " · ",
                func.coalesce(SesionTrabajoDB.fragancia, "Sin fragancia"),
            )
        elif dim == "maquina":
            etiqueta = SesionTrabajoDB.maquina
        else:
            raise HTTPException(status_code=400, detail=f"dim invalido: {dim}")

        suma = func.coalesce(func.sum(PalletDB.cantidad_pacas), 0)
        q = (
            db.query(
                etiqueta.label("etiqueta"),
                suma.label("pacas"),
                func.count(SesionTrabajoDB.id.distinct()).label("sesiones"),
            )
            .outerjoin(PalletDB, PalletDB.session_id == SesionTrabajoDB.id)
        )
        if desde_dt is not None:
            q = q.filter(SesionTrabajoDB.inicio_turno >= desde_dt)
        if hasta_dt is not None:
            q = q.filter(SesionTrabajoDB.inicio_turno < hasta_dt)
        filas = q.group_by(etiqueta).order_by(suma.desc()).all()

        items = []
        for r in filas:
            items.append({
                "etiqueta": r.etiqueta if r.etiqueta else "—",
                "pacas": int(r.pacas or 0),
                "sesiones": int(r.sesiones or 0),
            })
        total = sum(i["pacas"] for i in items)
        for i in items:
            i["pct"] = round(i["pacas"] / total * 100, 1) if total else 0.0

        return {
            "dim": dim,
            "rango": rango,
            "total_pacas": total,
            "total_sesiones": sum(i["sesiones"] for i in items),
            "items": items,
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error en /dashboard/estadisticas: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener estadisticas")
