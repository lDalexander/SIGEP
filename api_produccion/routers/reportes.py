"""
Router para los endpoints de generación de reportes (Excel).

  - GET /api/reportes/excel             -> reporte de PRODUCCIÓN por sesión.
  - GET /api/reportes/formularios_excel -> reporte de CHECKLISTS de mantenimiento.

Ambos aceptan ?desde=YYYY-MM-DD&hasta=YYYY-MM-DD (por defecto: el día de hoy).
"""
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from sqlalchemy import func
from datetime import datetime, timedelta
import pandas as pd
from io import BytesIO
from fastapi.responses import StreamingResponse

from database import get_db, logger
from models import SesionTrabajoDB, PalletDB, ParoMaquinaDB, MantenimientoChecklistDB, PedidoBodegaDB

router = APIRouter(prefix="/api/reportes", tags=["Reportes"])


def _rango(desde, hasta):
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


def _es_saco(presentacion):
    """Las presentaciones de 15/25 Kg son sacos, no pacas."""
    norm = (presentacion or "").upper().replace(" ", "")
    return "15KG" in norm or "25KG" in norm


def _excel(df_principal, hoja, filename, hojas_extra=None):
    buffer = BytesIO()
    with pd.ExcelWriter(buffer, engine="openpyxl") as writer:
        df_principal.to_excel(writer, index=False, sheet_name=hoja)
        for nombre, df in (hojas_extra or []):
            df.to_excel(writer, index=False, sheet_name=nombre)
    buffer.seek(0)
    return StreamingResponse(
        buffer,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@router.get("/excel")
def descargar_excel(desde: str = Query(None), hasta: str = Query(None), db: Session = Depends(get_db)):
    """Reporte de producción por sesión: pacas/sacos, paros, tiempo trabajado y productividad."""
    try:
        d, h, inicio, fin = _rango(desde, hasta)
        sesiones = (
            db.query(SesionTrabajoDB)
            .filter(SesionTrabajoDB.inicio_turno >= inicio, SesionTrabajoDB.inicio_turno < fin)
            .order_by(SesionTrabajoDB.inicio_turno.asc())
            .all()
        )
        if not sesiones:
            raise HTTPException(status_code=404, detail="No hay datos en el rango seleccionado")

        ahora = datetime.now()
        rows = []
        for s in sesiones:
            total = int(db.query(func.coalesce(func.sum(PalletDB.cantidad_pacas), 0))
                        .filter(PalletDB.session_id == s.id).scalar() or 0)
            saco = _es_saco(s.presentacion)
            pacas = 0 if saco else total
            sacos = total if saco else 0

            paros = db.query(ParoMaquinaDB).filter(ParoMaquinaDB.session_id == s.id).all()
            seg_paros = 0.0
            for p in paros:
                if p.duracion_segundos:
                    seg_paros += p.duracion_segundos
                elif p.fin_paro is None and p.inicio_paro:
                    seg_paros += (ahora - p.inicio_paro).total_seconds()
            min_paros = seg_paros / 60.0

            if s.fin_turno and s.duracion_minutos:
                dur_min = float(s.duracion_minutos)
            elif s.inicio_turno:
                dur_min = ((s.fin_turno or ahora) - s.inicio_turno).total_seconds() / 60.0
            else:
                dur_min = 0.0
            trabajado_min = max(0.0, dur_min - min_paros)
            prod_h = round(total / (trabajado_min / 60.0), 1) if trabajado_min > 0 else 0.0

            rows.append({
                "ID Sesión": s.id,
                "Fecha": s.inicio_turno.strftime("%Y-%m-%d") if s.inicio_turno else "",
                "Máquina": s.maquina,
                "Operador": s.operador,
                "Marca": s.marca,
                "Presentación": s.presentacion,
                "Fragancia": s.fragancia,
                "Inicio": s.inicio_turno.strftime("%H:%M:%S") if s.inicio_turno else "",
                "Fin": s.fin_turno.strftime("%H:%M:%S") if s.fin_turno else "En curso",
                "Estado": "Finalizado" if s.fin_turno else "Activo",
                "Pacas": pacas,
                "Sacos": sacos,
                "N° Paros": len(paros),
                "Tiempo Paros (min)": round(min_paros, 1),
                "Duración (min)": round(dur_min, 1),
                "Tiempo Trabajado (min)": round(trabajado_min, 1),
                "Productividad (pacas/h)": prod_h,
            })

        nombre = f"reporte_produccion_{d.isoformat()}_a_{h.isoformat()}.xlsx"
        logger.info(f"Reporte producción {d}..{h}: {len(rows)} sesiones")
        return _excel(pd.DataFrame(rows), "Producción", nombre)
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error en /reportes/excel: {e}")
        raise HTTPException(status_code=500, detail="Error al generar reporte")


def _hoja_checklists(grupo):
    """Construye un DataFrame con una fila por checklist y una COLUMNA por cada ítem
    (X si fue marcado, vacío si no), seguido del indicador y los comentarios.

    Recibe checklists de un MISMO momento (ENTRADA o SALIDA) para que las columnas
    de ítems sean homogéneas y 'vacío' signifique realmente 'no marcado'.
    """
    # Etiquetas de ítems en orden de primera aparición (mismo orden que ve el operario).
    etiquetas, vistas = [], set()
    for c in grupo:
        for it in c.items:
            if it.etiqueta not in vistas:
                vistas.add(it.etiqueta)
                etiquetas.append(it.etiqueta)

    base = ["ID", "Fecha Turno", "Turno", "Máquina", "Operador", "Supervisor", "Hora"]
    columnas = base + etiquetas + ["Ítems marcados", "Comentarios"]

    filas = []
    for c in grupo:
        marcado = {it.etiqueta: it.marcado for it in c.items}
        total = len(c.items)
        ok = sum(1 for it in c.items if it.marcado)
        fila = {
            "ID": c.id,
            "Fecha Turno": c.fecha_turno.isoformat() if c.fecha_turno else "",
            "Turno": c.codigo_turno,
            "Máquina": c.maquina,
            "Operador": c.operador,
            "Supervisor": c.supervisor or "",
            "Hora": c.hora,
        }
        for et in etiquetas:
            fila[et] = "X" if marcado.get(et) else ""
        fila["Ítems marcados"] = f"{ok}/{total}"
        fila["Comentarios"] = c.comentarios or ""
        filas.append(fila)

    return pd.DataFrame(filas, columns=columnas)


@router.get("/formularios_excel")
def descargar_formularios_excel(desde: str = Query(None), hasta: str = Query(None), db: Session = Depends(get_db)):
    """Reporte de checklists: una hoja por momento (ENTRADA / SALIDA), con cada ítem
    como columna (X = marcado, vacío = no marcado) + indicador + comentarios."""
    try:
        d, h, _, _ = _rango(desde, hasta)
        checklists = (
            db.query(MantenimientoChecklistDB)
            .filter(MantenimientoChecklistDB.fecha_turno >= d, MantenimientoChecklistDB.fecha_turno <= h)
            .order_by(MantenimientoChecklistDB.fecha_turno.asc(), MantenimientoChecklistDB.id.asc())
            .all()
        )
        if not checklists:
            raise HTTPException(status_code=404, detail="No hay checklists en el rango seleccionado")

        hojas = []
        for momento in ("ENTRADA", "SALIDA"):
            grupo = [c for c in checklists if (c.momento or "").upper() == momento]
            if grupo:
                hojas.append((momento, _hoja_checklists(grupo)))
        if not hojas:
            raise HTTPException(status_code=404, detail="No hay checklists en el rango seleccionado")

        nombre = f"reporte_formularios_{d.isoformat()}_a_{h.isoformat()}.xlsx"
        logger.info(f"Reporte formularios {d}..{h}: {len(checklists)} checklists, {len(hojas)} hoja(s)")
        principal_nombre, principal_df = hojas[0]
        return _excel(principal_df, principal_nombre, nombre, hojas_extra=hojas[1:])
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error en /reportes/formularios_excel: {e}")
        raise HTTPException(status_code=500, detail="Error al generar reporte de formularios")


@router.get("/insumos_excel")
def descargar_insumos_excel(desde: str = Query(None), hasta: str = Query(None), db: Session = Depends(get_db)):
    """Reporte de solicitudes de insumos: solicitada vs entregada vs recibida + discrepancia."""
    try:
        d, h, inicio, fin = _rango(desde, hasta)
        pedidos = (
            db.query(PedidoBodegaDB, SesionTrabajoDB)
            .outerjoin(SesionTrabajoDB, SesionTrabajoDB.id == PedidoBodegaDB.session_id)
            .filter(PedidoBodegaDB.fecha_solicitud >= inicio, PedidoBodegaDB.fecha_solicitud < fin)
            .order_by(PedidoBodegaDB.fecha_solicitud.asc())
            .all()
        )
        if not pedidos:
            raise HTTPException(status_code=404, detail="No hay solicitudes de insumos en el rango seleccionado")

        rows = []
        for p, s in pedidos:
            ent, rec = p.cantidad_entregada, p.cantidad_recibida
            discrepancia = ""
            if ent is not None and rec is not None and ent != rec:
                discrepancia = f"{rec - ent:+d}"
            rows.append({
                "ID": p.id,
                "Fecha": p.fecha_solicitud.strftime("%Y-%m-%d %H:%M") if p.fecha_solicitud else "",
                "Máquina": s.maquina if s else "",
                "Operador": s.operador if s else "",
                "Insumo": p.detalle_pedido,
                "Categoría": p.categoria,
                "Solicitada": p.cantidad_solicitada,
                "Entregada": ent if ent is not None else "",
                "Recibida": rec if rec is not None else "",
                "Discrepancia (rec-ent)": discrepancia,
                "Estado": p.estado,
            })

        nombre = f"reporte_insumos_{d.isoformat()}_a_{h.isoformat()}.xlsx"
        logger.info(f"Reporte insumos {d}..{h}: {len(rows)} pedidos")
        return _excel(pd.DataFrame(rows), "Insumos", nombre)
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error en /reportes/insumos_excel: {e}")
        raise HTTPException(status_code=500, detail="Error al generar reporte de insumos")
