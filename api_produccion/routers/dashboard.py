"""
Router para los endpoints del Dashboard.
Proporciona KPIs, estado operativo y gráficas de producción para los supervisores.
"""
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from sqlalchemy import func, case, or_, and_
from datetime import datetime, timedelta
from typing import List
import re
import pandas as pd
from io import BytesIO
from fastapi.responses import StreamingResponse

from database import get_db, logger
from models import PalletDB, SesionTrabajoDB, ParoMaquinaDB, MaquinaDB, ComentarioTurnoDB

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


def _parse_hora(valor):
    """"HH:MM" | "HH:MM:SS" | "HH" -> minutos desde medianoche. None si no es usable.

    Un valor no parseable se ignora en silencio en vez de devolver 400, igual que
    `?agrupar=` y `?tipo=`: una URL mal copiada o un cliente viejo no debe dejar el
    dashboard en blanco. Se acepta "24:00" como fin de día (1440)."""
    if valor is None:
        return None
    txt = str(valor).strip()
    if not txt:
        return None
    partes = txt.split(":")
    try:
        h = int(partes[0])
        m = int(partes[1]) if len(partes) > 1 and partes[1] != "" else 0
    except ValueError:
        return None
    if not (0 <= h <= 24 and 0 <= m <= 59):
        return None
    return min(h * 60 + m, 1440)


def _filtro_horas(columna, hora_desde, hora_hasta):
    """Condición SQL que limita `columna` a una franja horaria del día, o None.

    `hora_desde` es inclusivo y `hora_hasta` exclusivo, igual que el fin de `_rango`.
    Si `hora_desde` > `hora_hasta` la franja CRUZA MEDIANOCHE: 19:00→07:00 significa
    19:00-23:59 más 00:00-06:59, que es como funciona el turno de noche de la planta.
    Con ambos límites iguales (o ninguno) devuelve None y no se añade ningún WHERE, de
    modo que sin los parámetros la consulta es exactamente la de siempre."""
    ini = _parse_hora(hora_desde)
    fin = _parse_hora(hora_hasta)
    if ini is None and fin is None:
        return None
    if ini == fin:
        return None
    minutos = func.extract("hour", columna) * 60 + func.extract("minute", columna)
    if fin is None:
        return minutos >= ini
    if ini is None:
        return minutos < fin
    if ini < fin:
        return and_(minutos >= ini, minutos < fin)
    return or_(minutos >= ini, minutos < fin)


def _aplicar_horas(query, columna, hora_desde, hora_hasta):
    """Aplica `_filtro_horas` a una query si hay algo que filtrar."""
    cond = _filtro_horas(columna, hora_desde, hora_hasta)
    return query.filter(cond) if cond is not None else query


def _aplicar_filtros(query, maquina=None, operador=None, marca=None, presentacion=None, fragancia=None):
    """Aplica los segmentadores (multi-selección) sobre columnas de SesionTrabajoDB.

    Cada parámetro es una lista de valores; si viene vacío/None esa dimensión no filtra.
    Se usa `IN (...)` para permitir seleccionar varios valores por dimensión a la vez."""
    if maquina:
        query = query.filter(SesionTrabajoDB.maquina.in_(maquina))
    if operador:
        query = query.filter(SesionTrabajoDB.operador.in_(operador))
    if marca:
        query = query.filter(SesionTrabajoDB.marca.in_(marca))
    if presentacion:
        query = query.filter(SesionTrabajoDB.presentacion.in_(presentacion))
    if fragancia:
        query = query.filter(SesionTrabajoDB.fragancia.in_(fragancia))
    return query


# Parámetros de segmentación reutilizados por varios endpoints (multi-selección).
_FiltroMaquina = Query(None)
_FiltroOperador = Query(None)
_FiltroMarca = Query(None)
_FiltroPresentacion = Query(None)
_FiltroFragancia = Query(None)

# Franja horaria del día, opcional y compartida por todos los endpoints del dashboard.
# Sin estos parámetros la respuesta es idéntica a la de antes de existir el filtro, así
# que la app Android no necesita cambio alguno.
_FiltroHoraDesde = Query(None)
_FiltroHoraHasta = Query(None)

@router.get("/kpis")
def obtener_kpis(desde: str = Query(None), hasta: str = Query(None),
                 hora_desde: str = _FiltroHoraDesde, hora_hasta: str = _FiltroHoraHasta,
                 maquina: List[str] = _FiltroMaquina, operador: List[str] = _FiltroOperador,
                 marca: List[str] = _FiltroMarca, presentacion: List[str] = _FiltroPresentacion,
                 fragancia: List[str] = _FiltroFragancia, db: Session = Depends(get_db)):
    """KPIs (pacas/sacos/turnos activos) del rango seleccionado (default: hoy).

    `hora_desde`/`hora_hasta` (opcionales, "HH:MM") recortan además a una franja del
    día: las pacas/sacos por la hora del pallet y los turnos activos por su hora de
    inicio. Ver `_filtro_horas` para el cruce de medianoche."""
    try:
        inicio, fin = _rango(desde, hasta)
        # Las presentaciones de 15/25 Kg NO son pacas, son sacos -> se cuentan aparte.
        norm_pres = func.replace(func.upper(func.coalesce(SesionTrabajoDB.presentacion, "")), " ", "")
        es_saco = or_(norm_pres.like("%15KG%"), norm_pres.like("%25KG%"))
        q = (
            db.query(
                func.coalesce(func.sum(case((es_saco, 0), else_=PalletDB.cantidad_pacas)), 0).label("pacas"),
                func.coalesce(func.sum(case((es_saco, PalletDB.cantidad_pacas), else_=0)), 0).label("sacos"),
            )
            .outerjoin(SesionTrabajoDB, SesionTrabajoDB.id == PalletDB.session_id)
            .filter(PalletDB.fecha_hora >= inicio, PalletDB.fecha_hora < fin)
        )
        q = _aplicar_filtros(q, maquina, operador, marca, presentacion, fragancia)
        q = _aplicar_horas(q, PalletDB.fecha_hora, hora_desde, hora_hasta)
        fila = q.first()
        pacas_hoy = int(fila.pacas or 0) if fila else 0
        sacos_hoy = int(fila.sacos or 0) if fila else 0
        q_turnos = (
            db.query(func.count(SesionTrabajoDB.id))
            .filter(SesionTrabajoDB.fin_turno.is_(None),
                    SesionTrabajoDB.inicio_turno >= inicio, SesionTrabajoDB.inicio_turno < fin)
        )
        q_turnos = _aplicar_filtros(q_turnos, maquina, operador, marca, presentacion, fragancia)
        q_turnos = _aplicar_horas(q_turnos, SesionTrabajoDB.inicio_turno, hora_desde, hora_hasta)
        turnos_activos = q_turnos.scalar()
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
def obtener_produccion_hora(desde: str = Query(None), hasta: str = Query(None),
                            agrupar: str = Query(None),
                            hora_desde: str = _FiltroHoraDesde, hora_hasta: str = _FiltroHoraHasta,
                            maquina: List[str] = _FiltroMaquina, operador: List[str] = _FiltroOperador,
                            marca: List[str] = _FiltroMarca, presentacion: List[str] = _FiltroPresentacion,
                            fragancia: List[str] = _FiltroFragancia, db: Session = Depends(get_db)):
    """Producción para la gráfica, con DETALLE por punto para el tooltip.

    Cada punto trae `detalle`: lista de {maquina, operario, producto, pacas} de lo
    producido en ese punto (agrupado por sesión).

    `agrupar` (opcional) elige el eje de la serie:
      - ausente o "hora" (por defecto): un punto por hora del reloj, etiqueta "HH:00".
        Para un rango de varios días, la hora agrega lo de esa hora a lo largo de todo
        el rango — es el comportamiento histórico y el que consume la app Android.
      - "dia": un punto por fecha natural, etiqueta "YYYY-MM-DD".
    Un valor desconocido se trata como "hora", igual que `?tipo=` en /operadores: se
    ignora en vez de romper al cliente.

    `hora_desde`/`hora_hasta` (opcionales, "HH:MM") recortan a una franja del día por
    la hora del pallet, para poder aislar un turno. Combinan con las dos agrupaciones:
    con `agrupar=dia` cada punto es lo producido ese día DENTRO de la franja."""
    try:
        inicio, fin = _rango(desde, hasta)
        por_dia = (agrupar or "").strip().lower() == "dia"
        hora_col = func.date(PalletDB.fecha_hora) if por_dia else func.extract("hour", PalletDB.fecha_hora)
        q = (
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
        )
        q = _aplicar_filtros(q, maquina, operador, marca, presentacion, fragancia)
        q = _aplicar_horas(q, PalletDB.fecha_hora, hora_desde, hora_hasta)
        filas = (
            q.group_by(hora_col, SesionTrabajoDB.maquina, SesionTrabajoDB.operador,
                       SesionTrabajoDB.marca, SesionTrabajoDB.presentacion, SesionTrabajoDB.fragancia)
             .order_by(hora_col)
             .all()
        )
        por_hora = {}
        for r in filas:
            hk = str(r.hora) if por_dia else int(r.hora)
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
            etiqueta = hk if por_dia else f"{hk:02d}:00"
            data.append({"hora": etiqueta, "pallets": b["pallets"], "detalle": b["detalle"]})
        return data
    except Exception as e:
        logger.error(f"Error en /dashboard/produccion_hora: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener producción por hora")

@router.get("/estado_operativo")
def obtener_estado_operativo(desde: str = Query(None), hasta: str = Query(None),
                             hora_desde: str = _FiltroHoraDesde, hora_hasta: str = _FiltroHoraHasta,
                             maquina: List[str] = _FiltroMaquina, operador: List[str] = _FiltroOperador,
                             marca: List[str] = _FiltroMarca, presentacion: List[str] = _FiltroPresentacion,
                             fragancia: List[str] = _FiltroFragancia, db: Session = Depends(get_db)):
    """Estado de las máquinas y turnos del rango seleccionado (default: hoy).

    `hora_desde`/`hora_hasta` (opcionales, "HH:MM") filtran por la HORA DE INICIO del
    turno, no por la de los pallets: la fila representa una sesión entera, así que
    `total_pacas` sigue siendo el total de la sesión aunque parte quede fuera de la
    franja. Es el mismo criterio con el que ya se filtra por fecha aquí."""
    try:
        inicio, fin = _rango(desde, hasta)
        q = db.query(SesionTrabajoDB).filter(
            SesionTrabajoDB.inicio_turno >= inicio, SesionTrabajoDB.inicio_turno < fin
        )
        q = _aplicar_filtros(q, maquina, operador, marca, presentacion, fragancia)
        q = _aplicar_horas(q, SesionTrabajoDB.inicio_turno, hora_desde, hora_hasta)
        sesiones = q.order_by(SesionTrabajoDB.inicio_turno.desc()).all()
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
def obtener_top_produccion(desde: str = Query(None), hasta: str = Query(None),
                           hora_desde: str = _FiltroHoraDesde, hora_hasta: str = _FiltroHoraHasta,
                           maquina: List[str] = _FiltroMaquina, operador: List[str] = _FiltroOperador,
                           marca: List[str] = _FiltroMarca, presentacion: List[str] = _FiltroPresentacion,
                           fragancia: List[str] = _FiltroFragancia, db: Session = Depends(get_db)):
    """Ranking de marcas más producidas en el rango seleccionado (default: hoy).

    `hora_desde`/`hora_hasta` (opcionales, "HH:MM") recortan por la hora del pallet."""
    try:
        inicio, fin = _rango(desde, hasta)
        q = (
            db.query(SesionTrabajoDB.marca, func.coalesce(func.sum(PalletDB.cantidad_pacas), 0).label("total"))
            .join(PalletDB, PalletDB.session_id == SesionTrabajoDB.id)
            .filter(PalletDB.fecha_hora >= inicio, PalletDB.fecha_hora < fin)
        )
        q = _aplicar_filtros(q, maquina, operador, marca, presentacion, fragancia)
        q = _aplicar_horas(q, PalletDB.fecha_hora, hora_desde, hora_hasta)
        resultados = (
            q.group_by(SesionTrabajoDB.marca)
             .order_by(func.sum(PalletDB.cantidad_pacas).desc())
             .all()
        )
        return [{"name": r.marca if r.marca else "NA", "value": int(r.total)} for r in resultados]
    except Exception as e:
        logger.error(f"Error en /dashboard/top_produccion: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener top produccion")


@router.get("/opciones_filtros")
def obtener_opciones_filtros(desde: str = Query(None), hasta: str = Query(None), db: Session = Depends(get_db)):
    """Valores distintos disponibles por dimensión, para llenar los segmentadores.

    Si se envían desde/hasta se limita a las sesiones de ese rango; si no, devuelve
    el catálogo completo (todas las sesiones históricas) para que los menús nunca
    aparezcan vacíos aunque hoy no haya producción."""
    try:
        base = db.query(SesionTrabajoDB)
        if desde or hasta:
            inicio, fin = _rango(desde, hasta)
            base = base.filter(SesionTrabajoDB.inicio_turno >= inicio,
                               SesionTrabajoDB.inicio_turno < fin)

        def distintos(col):
            filas = base.with_entities(col).distinct().all()
            return sorted({r[0] for r in filas if r[0] not in (None, "")})

        return {
            "maquina": distintos(SesionTrabajoDB.maquina),
            "operador": distintos(SesionTrabajoDB.operador),
            "marca": distintos(SesionTrabajoDB.marca),
            "presentacion": distintos(SesionTrabajoDB.presentacion),
            "fragancia": distintos(SesionTrabajoDB.fragancia),
        }
    except Exception as e:
        logger.error(f"Error en /dashboard/opciones_filtros: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener opciones de filtros")

@router.get("/estadisticas")
def obtener_estadisticas(dim: str = "maquina", rango: str = "semana",
                         desde: str = Query(None), hasta: str = Query(None),
                         hora_desde: str = _FiltroHoraDesde, hora_hasta: str = _FiltroHoraHasta,
                         db: Session = Depends(get_db)):
    """Estadisticas de produccion agregadas por dimension y rango temporal.

    dim   : "maquina" | "operario" | "marca_presentacion" | "marca_presentacion_fragancia"
    Rango: si se envían desde/hasta (YYYY-MM-DD) se usan esas fechas (lo que permite
    que el dashboard comparta el mismo rango global); si no, se usa el preset `rango`
    ("hoy" | "semana" | "mes" | "todo").
    Devuelve {dim, rango, total_pacas, total_sesiones, items:[{etiqueta,pacas,sesiones,pct}]}.

    `hora_desde`/`hora_hasta` (opcionales, "HH:MM") recortan a una franja del día. Ojo
    al criterio, que aquí es mixto a propósito: el rango de FECHAS filtra por
    `inicio_turno` de la sesión (comportamiento histórico de este endpoint), mientras la
    franja HORARIA filtra por `fecha_hora` del pallet, que es lo que responde a «quién
    produjo más en el turno de noche». Como consecuencia, con franja activa las sesiones
    que no produjeron nada dentro de ella desaparecen del ranking en lugar de aparecer
    con 0 pacas: el `outerjoin` se comporta como un `join`. Sin los parámetros nada de
    esto se activa y la consulta es la de siempre.
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
        q = _aplicar_horas(q, PalletDB.fecha_hora, hora_desde, hora_hasta)
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


# ---------------------------------------------------------------------------
# Paros de máquina y comentarios de turno
#
# Rutas NUEVAS (no modifican ninguna respuesta existente), añadidas para la vista
# de monitoreo de paros de la web. Los datos ya los escribían las tablets vía
# POST /api/paro/iniciar y /api/paro/finalizar; hasta ahora solo se podían leer
# dentro de la hoja "Paros" del Excel de producción.
# ---------------------------------------------------------------------------

# La tablet manda el motivo como "[Categoría] - comentario libre".
_RE_MOTIVO = re.compile(r"^\[(?P<cat>[^\]]{1,60})\]\s*[-–—:]?\s*(?P<txt>.*)$", re.S)


def _desglosar_motivo(motivo):
    """"[Bodega] - sacar palet" -> ("BODEGA", "sacar palet").

    Cuando no hay corchetes el motivo entero ES la categoría y no hay comentario: es el
    caso de "ALMUERZO", que la app envía tal cual. No se inventa texto — sin comentario
    libre se devuelve None y la UI muestra "—"."""
    txt = (motivo or "").strip()
    if not txt:
        return "SIN MOTIVO", None
    m = _RE_MOTIVO.match(txt)
    if m:
        cat = m.group("cat").strip().upper()
        com = m.group("txt").strip()
        return (cat or "SIN MOTIVO"), (com or None)
    return txt.upper(), None


def _estado_paro(paro, sesion, ahora):
    """(estado, fin_efectivo, estimada) de un paro. Ver la nota de "SIN CIERRE".

    - "CERRADO":    tiene `fin_paro`; la duración es la registrada.
    - "EN CURSO":   sin `fin_paro` y con el turno todavía abierto -> la máquina está
                    parada AHORA y la duración corre contra el reloj.
    - "SIN CIERRE": sin `fin_paro` pero con el turno ya cerrado. Ocurre de verdad: el
                    garbage collector de `tasks.py` cierra los turnos colgados a las 13h
                    sin cerrar sus paros (así quedó el paro 105). No se puede afirmar
                    que la máquina siga parada, así que la duración se acota al fin del
                    turno y se marca como estimada en vez de dejarla crecer sin fin."""
    if paro.fin_paro:
        return "CERRADO", paro.fin_paro, False
    if sesion is not None and sesion.fin_turno is None:
        return "EN CURSO", ahora, False
    fin_turno = sesion.fin_turno if sesion is not None else None
    return "SIN CIERRE", fin_turno, True


def _dur_segundos(paro, fin_efectivo):
    """Duración en segundos, o None si no hay con qué calcularla (nunca un 0 falso)."""
    if paro.fin_paro and paro.duracion_segundos is not None:
        return round(float(paro.duracion_segundos), 1)
    if paro.inicio_paro and fin_efectivo:
        return round(max(0.0, (fin_efectivo - paro.inicio_paro).total_seconds()), 1)
    return None


def _fmt(dt):
    return dt.strftime("%Y-%m-%d %H:%M:%S") if dt else None


@router.get("/paros")
def obtener_paros(desde: str = Query(None), hasta: str = Query(None),
                  hora_desde: str = _FiltroHoraDesde, hora_hasta: str = _FiltroHoraHasta,
                  maquina: List[str] = _FiltroMaquina, operador: List[str] = _FiltroOperador,
                  db: Session = Depends(get_db)):
    """Paros de máquina del rango (default: hoy) + estado EN VIVO de cada máquina.

    Devuelve `{kpis, maquinas, paros, por_categoria}`:

    - `paros`: un renglón por paro cuyo `inicio_paro` cae en el rango, del más reciente
      al más antiguo, con categoría y comentario desglosados del motivo, duración y
      estado (`CERRADO` | `EN CURSO` | `SIN CIERRE`, ver `_estado_paro`).
    - `maquinas`: semáforo por máquina. Ojo al criterio mixto, deliberado: `estado`,
      `operador` y `paro_actual` son de AHORA (la máquina está parada o no en este
      instante, sea cual sea el rango consultado), mientras `paros` y `segundos` son los
      acumulados DEL RANGO. Incluye las máquinas activas del catálogo y, además,
      cualquier máquina que aparezca en los paros del rango aunque ya esté dada de baja.
    - `kpis`: totales del rango, más las máquinas paradas/produciendo ahora mismo.

    `hora_desde`/`hora_hasta` (opcionales, "HH:MM") recortan por la hora de INICIO del
    paro, igual que `estado_operativo` lo hace por la de inicio del turno; un paro que
    empieza dentro de la franja cuenta entero aunque termine fuera. Cruzan medianoche
    (ver `_filtro_horas`)."""
    try:
        inicio, fin = _rango(desde, hasta)
        ahora = datetime.now()

        q = (
            db.query(ParoMaquinaDB, SesionTrabajoDB)
            .outerjoin(SesionTrabajoDB, SesionTrabajoDB.id == ParoMaquinaDB.session_id)
            .filter(ParoMaquinaDB.inicio_paro >= inicio, ParoMaquinaDB.inicio_paro < fin)
        )
        q = _aplicar_filtros(q, maquina, operador)
        q = _aplicar_horas(q, ParoMaquinaDB.inicio_paro, hora_desde, hora_hasta)
        filas = q.order_by(ParoMaquinaDB.inicio_paro.desc()).all()

        paros = []
        # Acumulados del rango por máquina y por categoría.
        acum_maq = {}
        acum_cat = {}
        for p, s in filas:
            estado, fin_efectivo, estimada = _estado_paro(p, s, ahora)
            dur = _dur_segundos(p, fin_efectivo)
            categoria, comentario = _desglosar_motivo(p.motivo)
            nombre_maq = (s.maquina if s is not None else None) or "—"
            producto = " · ".join([x for x in [
                s.marca if s is not None else None,
                s.presentacion if s is not None else None,
                s.fragancia if s is not None else None,
            ] if x]) or "—"
            paros.append({
                "id": p.id,
                "sesion_id": p.session_id,
                "maquina": nombre_maq,
                "operador": (s.operador if s is not None else None) or "—",
                "producto": producto,
                "categoria": categoria,
                "comentario": comentario,
                "motivo": p.motivo or "",
                "inicio": _fmt(p.inicio_paro),
                "fin": _fmt(p.fin_paro),
                "fin_estimado": _fmt(fin_efectivo) if estimada else None,
                "estado": estado,
                "en_curso": estado == "EN CURSO",
                "duracion_segundos": dur,
                "duracion_estimada": estimada,
                "inicio_turno": _fmt(s.inicio_turno) if s is not None else None,
                "fin_turno": _fmt(s.fin_turno) if s is not None else None,
            })
            a = acum_maq.setdefault(nombre_maq, {"paros": 0, "segundos": 0.0})
            a["paros"] += 1
            a["segundos"] += dur or 0.0
            c = acum_cat.setdefault(categoria, {"paros": 0, "segundos": 0.0})
            c["paros"] += 1
            c["segundos"] += dur or 0.0

        # --- Semáforo en vivo: turno abierto y paro abierto de cada máquina ---
        sesiones_abiertas = {}
        for s in db.query(SesionTrabajoDB).filter(SesionTrabajoDB.fin_turno.is_(None)).all():
            # Si una máquina tuviera dos turnos abiertos (no debería), gana el más reciente.
            previa = sesiones_abiertas.get(s.maquina)
            if previa is None or (s.inicio_turno and previa.inicio_turno and s.inicio_turno > previa.inicio_turno):
                sesiones_abiertas[s.maquina] = s
        paros_abiertos = {}
        if sesiones_abiertas:
            ids = [s.id for s in sesiones_abiertas.values()]
            for p in (db.query(ParoMaquinaDB)
                        .filter(ParoMaquinaDB.session_id.in_(ids), ParoMaquinaDB.fin_paro.is_(None))
                        .order_by(ParoMaquinaDB.inicio_paro.desc()).all()):
                paros_abiertos.setdefault(p.session_id, p)

        nombres = {m.nombre for m in db.query(MaquinaDB).filter(MaquinaDB.activa == True).all()}  # noqa: E712
        tipos = {m.nombre: m.tipo for m in db.query(MaquinaDB).all()}
        nombres |= {k for k in acum_maq.keys() if k != "—"}
        nombres |= set(sesiones_abiertas.keys()) - {None}
        if maquina:
            nombres &= set(maquina)

        maquinas = []
        for nombre in sorted(nombres):
            s = sesiones_abiertas.get(nombre)
            p_act = paros_abiertos.get(s.id) if s is not None else None
            if p_act is not None:
                cat, com = _desglosar_motivo(p_act.motivo)
                paro_actual = {
                    "id": p_act.id,
                    "categoria": cat,
                    "comentario": com,
                    "motivo": p_act.motivo or "",
                    "inicio": _fmt(p_act.inicio_paro),
                    "duracion_segundos": _dur_segundos(p_act, ahora),
                }
            else:
                paro_actual = None
            acum = acum_maq.get(nombre, {"paros": 0, "segundos": 0.0})
            maquinas.append({
                "maquina": nombre,
                "tipo": tipos.get(nombre),
                "estado": "PARO" if paro_actual else ("PRODUCIENDO" if s is not None else "SIN TURNO"),
                "sesion_id": s.id if s is not None else None,
                "operador": (s.operador if s is not None else None) or "—",
                "inicio_turno": _fmt(s.inicio_turno) if s is not None else None,
                "paro_actual": paro_actual,
                "paros": acum["paros"],
                "segundos": round(acum["segundos"], 1),
            })

        conocidas = [p["duracion_segundos"] for p in paros if p["duracion_segundos"] is not None]
        total_seg = round(sum(conocidas), 1)
        return {
            "kpis": {
                "total_paros": len(paros),
                "en_curso": sum(1 for p in paros if p["estado"] == "EN CURSO"),
                "sin_cierre": sum(1 for p in paros if p["estado"] == "SIN CIERRE"),
                "segundos_total": total_seg,
                "segundos_promedio": round(total_seg / len(conocidas), 1) if conocidas else None,
                "maquinas_paradas": sum(1 for m in maquinas if m["estado"] == "PARO"),
                "maquinas_produciendo": sum(1 for m in maquinas if m["estado"] == "PRODUCIENDO"),
            },
            "maquinas": maquinas,
            "paros": paros,
            "por_categoria": sorted(
                [{"categoria": k, "paros": v["paros"], "segundos": round(v["segundos"], 1)}
                 for k, v in acum_cat.items()],
                key=lambda x: x["segundos"], reverse=True,
            ),
        }
    except Exception as e:
        logger.error(f"Error en /dashboard/paros: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener paros")


@router.get("/comentarios_turno")
def obtener_comentarios_turno(desde: str = Query(None), hasta: str = Query(None),
                              limit: int = Query(30), db: Session = Depends(get_db)):
    """Comentarios libres que los operarios escriben desde la tablet, del más reciente.

    Mismo criterio que `/mantenimiento/checklist`: con `desde`/`hasta` devuelve los del
    rango; sin ellos, los `limit` últimos (default 30) sin importar la fecha, para que la
    tarjeta del dashboard nunca salga vacía si hoy no hubo comentarios. `limit` se aplica
    también al rango como tope de seguridad."""
    try:
        lim = max(1, min(int(limit or 30), 200))
        q = db.query(ComentarioTurnoDB)
        if desde or hasta:
            inicio, fin = _rango(desde, hasta)
            q = q.filter(ComentarioTurnoDB.creado_en >= inicio, ComentarioTurnoDB.creado_en < fin)
        filas = q.order_by(ComentarioTurnoDB.creado_en.desc()).limit(lim).all()
        return [{
            "id": c.id,
            "sesion_id": c.session_id,
            "maquina": c.maquina or "—",
            "operador": c.operador or "—",
            "texto": c.texto or "",
            "creado_en": _fmt(c.creado_en),
            "fecha": c.creado_en.strftime("%Y-%m-%d") if c.creado_en else None,
            "hora": c.creado_en.strftime("%H:%M:%S") if c.creado_en else None,
        } for c in filas]
    except Exception as e:
        logger.error(f"Error en /dashboard/comentarios_turno: {e}")
        raise HTTPException(status_code=500, detail="Error al obtener comentarios de turno")
