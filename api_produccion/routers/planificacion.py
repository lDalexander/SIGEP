"""Planificación de producción del día y cobertura de sus insumos (v1.7.0).

Producción planifica el día de antemano. Hasta ahora eso no existía en SIGEP y el
insumista solo se movía cuando un operario pedía algo: todo el flujo era reactivo. Con
el plan cargado, el material se puede dejar preparado ANTES de que la máquina arranque,
y el supervisor puede ver de un vistazo si lo que se va a producir está cubierto.

LAS LECTURAS VAN ABIERTAS y las escrituras piden token de admin. Es el mismo criterio
que ya sigue `GET /api/insumos/dashboard`: el plan del día lo tienen que poder leer la
tablet del insumista y la del supervisor, que no manejan token de admin.
"""

from datetime import date, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy.orm import Session
from typing import List, Optional

from database import get_db, logger
from models import InsumoDB, PlanInsumoDB, PlanProduccionDB
from routers.admin import require_planta
from services import planificacion

router = APIRouter(prefix="/api/planificacion", tags=["Planificación de producción"])


# ──────────────────────────────────────────────────────────────────────────────
# Entradas
# ──────────────────────────────────────────────────────────────────────────────

class PlanIn(BaseModel):
    """Un plan del día.

    `maquina` es opcional A PROPÓSITO: hay producción planificada que todavía no tiene
    máquina asignada y el material se deja para que después cualquiera lo tome. Es el
    caso del que nace toda esta función, así que no puede ser obligatorio.
    """
    fecha: date
    turno: Optional[str] = None
    maquina: Optional[str] = None
    producto: Optional[str] = None
    marca: Optional[str] = None
    presentacion: Optional[str] = None
    fragancia: Optional[str] = None
    cantidad_planificada: Optional[int] = None
    unidad: Optional[str] = None
    estado: Optional[str] = None
    observaciones: Optional[str] = None


class PlanInsumoIn(BaseModel):
    insumo_id: Optional[int] = None
    cantidad_requerida: Optional[int] = None
    prioridad: Optional[str] = None
    observaciones: Optional[str] = None


def _rango(desde, hasta):
    """Por defecto, de hoy a 7 días: el plan es cosa de la semana en curso."""
    hoy = date.today()
    try:
        d = datetime.strptime(desde, "%Y-%m-%d").date() if desde else hoy
    except ValueError:
        d = hoy
    try:
        h = datetime.strptime(hasta, "%Y-%m-%d").date() if hasta else (d + timedelta(days=7))
    except ValueError:
        h = d + timedelta(days=7)
    return (h, d) if h < d else (d, h)


def _buscar_plan(db, plan_id):
    plan = db.query(PlanProduccionDB).filter(PlanProduccionDB.id == plan_id).first()
    if plan is None:
        raise HTTPException(status_code=404, detail="El plan no existe")
    return plan


def _validar_estado(estado):
    if estado and estado not in planificacion.ESTADOS_PLAN:
        raise HTTPException(
            status_code=400,
            detail="Estado inválido. Use uno de: %s" % ", ".join(planificacion.ESTADOS_PLAN),
        )


# ──────────────────────────────────────────────────────────────────────────────
# Lectura (abierta)
# ──────────────────────────────────────────────────────────────────────────────

@router.get("/planes")
def listar_planes(
    desde: str = Query(None), hasta: str = Query(None),
    maquina: str = Query(None), estado: str = Query(None),
    db: Session = Depends(get_db),
):
    """Planes del rango con su cobertura de insumos."""
    try:
        d, h = _rango(desde, hasta)
        return {
            "rango": {"desde": d.isoformat(), "hasta": h.isoformat()},
            "planes": planificacion.planes_del_rango(db, d, h, maquina, estado),
        }
    except Exception as e:
        logger.error(f"Error al listar planes: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.get("/planes/{plan_id}")
def ver_plan(plan_id: int, db: Session = Depends(get_db)):
    return planificacion.resumen(db, _buscar_plan(db, plan_id))


# ──────────────────────────────────────────────────────────────────────────────
# Escritura (token de admin con permisos de planta)
# ──────────────────────────────────────────────────────────────────────────────

@router.post("/planes", status_code=201)
def crear_plan(datos: PlanIn, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    """Crea el plan y EXPLOTA EL BOM: deja la lista de insumos lista para revisar.

    Explotarlo al crear y no al consultar es lo que permite que quien planifica ajuste
    las cantidades: si se recalculara en cada lectura, cualquier ajuste se perdería.
    """
    _validar_estado(datos.estado)
    try:
        plan = PlanProduccionDB(
            fecha=datos.fecha,
            turno=datos.turno,
            maquina=datos.maquina,
            producto=datos.producto,
            marca=datos.marca,
            presentacion=datos.presentacion,
            fragancia=datos.fragancia,
            cantidad_planificada=datos.cantidad_planificada,
            unidad=datos.unidad,
            estado=datos.estado or "Planificado",
            observaciones=datos.observaciones,
            # `require_admin` deja el admin autenticado en `ctx["username"]`.
            creado_por=(ctx or {}).get("username"),
        )
        db.add(plan)
        db.flush()

        creadas = planificacion.explotar_bom(db, plan)
        db.commit()
        db.refresh(plan)

        if creadas == 0:
            logger.warning(
                f"Plan {plan.id} creado SIN insumos: el BOM no tiene recetas para "
                f"{plan.maquina} / {plan.producto} / {plan.marca} / {plan.presentacion} / {plan.fragancia}."
            )
        logger.info(f"📋 Plan {plan.id} creado para el {plan.fecha} con {creadas} insumo(s) del BOM")
        return planificacion.resumen(db, plan)
    except HTTPException:
        db.rollback()
        raise
    except Exception as e:
        db.rollback()
        logger.error(f"Error al crear plan: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.put("/planes/{plan_id}")
def editar_plan(plan_id: int, datos: PlanIn, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    """Edita el plan. NO vuelve a explotar el BOM.

    Cambiar la marca o la presentación cambia lo que hace falta, pero recalcular solo
    borraría los ajustes de quien planificó. El recálculo es un botón aparte y explícito.
    """
    _validar_estado(datos.estado)
    plan = _buscar_plan(db, plan_id)
    try:
        for campo in ("fecha", "turno", "maquina", "producto", "marca", "presentacion",
                      "fragancia", "cantidad_planificada", "unidad", "estado", "observaciones"):
            valor = getattr(datos, campo)
            if valor is not None:
                setattr(plan, campo, valor)
        db.commit()
        return planificacion.resumen(db, plan)
    except Exception as e:
        db.rollback()
        logger.error(f"Error al editar el plan {plan_id}: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.delete("/planes/{plan_id}")
def borrar_plan(plan_id: int, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    """Borra el plan y su lista de insumos (cascada de la FK).

    Las entregas y pedidos que apuntaban a él NO se borran: son hechos que ocurrieron.
    Se quedan con `plan_id` apuntando a un plan que ya no está, que es lo honesto.
    """
    plan = _buscar_plan(db, plan_id)
    try:
        db.delete(plan)
        db.commit()
        logger.info(f"🗑️ Plan {plan_id} eliminado")
        return {"mensaje": "Plan eliminado"}
    except Exception as e:
        db.rollback()
        logger.error(f"Error al borrar el plan {plan_id}: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.post("/planes/{plan_id}/recalcular")
def recalcular_bom(
    plan_id: int,
    sobrescribir: bool = Query(False, description="Si es true, repone también las cantidades."),
    db: Session = Depends(get_db),
    ctx=Depends(require_planta),
):
    """Vuelve a explotar el BOM sobre un plan existente.

    Para cuando se corrige una receta o se carga el catálogo del ERP después de haber
    creado el plan. Por defecto solo AÑADE lo que falta; `sobrescribir=true` repone
    además las cantidades a lo que diga la receta.
    """
    plan = _buscar_plan(db, plan_id)
    try:
        creadas = planificacion.explotar_bom(db, plan, sobrescribir=sobrescribir)
        db.commit()
        return {"mensaje": "BOM recalculado", "insumos_anadidos": creadas,
                "plan": planificacion.resumen(db, plan)}
    except Exception as e:
        db.rollback()
        logger.error(f"Error al recalcular el BOM del plan {plan_id}: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.post("/planes/{plan_id}/insumos", status_code=201)
def anadir_insumo(plan_id: int, datos: PlanInsumoIn, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    """Añade a mano un insumo que el BOM no contempla."""
    plan = _buscar_plan(db, plan_id)
    if not datos.insumo_id:
        raise HTTPException(status_code=400, detail="Falta `insumo_id`")
    if datos.prioridad and datos.prioridad not in planificacion.PRIORIDADES:
        raise HTTPException(status_code=400, detail="Prioridad inválida")

    insumo = db.query(InsumoDB).filter(InsumoDB.id == datos.insumo_id).first()
    if insumo is None:
        raise HTTPException(status_code=404, detail="El insumo no existe")
    if db.query(PlanInsumoDB).filter(
        PlanInsumoDB.plan_id == plan_id, PlanInsumoDB.insumo_id == datos.insumo_id
    ).first():
        raise HTTPException(status_code=409, detail=f"«{insumo.nombre}» ya está en el plan")

    try:
        db.add(PlanInsumoDB(
            plan_id=plan_id,
            insumo_id=datos.insumo_id,
            cantidad_requerida=datos.cantidad_requerida or 0,
            prioridad=datos.prioridad or "Normal",
            observaciones=datos.observaciones,
        ))
        db.commit()
        return planificacion.resumen(db, plan)
    except Exception as e:
        db.rollback()
        logger.error(f"Error al añadir insumo al plan {plan_id}: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.put("/planes/{plan_id}/insumos/{item_id}")
def editar_insumo(plan_id: int, item_id: int, datos: PlanInsumoIn,
                  db: Session = Depends(get_db), ctx=Depends(require_planta)):
    """Ajusta cantidad, prioridad u observación de una línea del plan."""
    plan = _buscar_plan(db, plan_id)
    fila = db.query(PlanInsumoDB).filter(
        PlanInsumoDB.id == item_id, PlanInsumoDB.plan_id == plan_id
    ).first()
    if fila is None:
        raise HTTPException(status_code=404, detail="Esa línea no es de este plan")
    if datos.prioridad and datos.prioridad not in planificacion.PRIORIDADES:
        raise HTTPException(status_code=400, detail="Prioridad inválida")
    if datos.cantidad_requerida is not None and datos.cantidad_requerida < 0:
        raise HTTPException(status_code=400, detail="La cantidad no puede ser negativa")

    try:
        if datos.cantidad_requerida is not None:
            fila.cantidad_requerida = datos.cantidad_requerida
        if datos.prioridad:
            fila.prioridad = datos.prioridad
        if datos.observaciones is not None:
            fila.observaciones = datos.observaciones or None
        db.commit()
        return planificacion.resumen(db, plan)
    except Exception as e:
        db.rollback()
        logger.error(f"Error al editar la línea {item_id} del plan {plan_id}: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.delete("/planes/{plan_id}/insumos/{item_id}")
def quitar_insumo(plan_id: int, item_id: int, db: Session = Depends(get_db), ctx=Depends(require_planta)):
    plan = _buscar_plan(db, plan_id)
    fila = db.query(PlanInsumoDB).filter(
        PlanInsumoDB.id == item_id, PlanInsumoDB.plan_id == plan_id
    ).first()
    if fila is None:
        raise HTTPException(status_code=404, detail="Esa línea no es de este plan")
    try:
        db.delete(fila)
        db.commit()
        return planificacion.resumen(db, plan)
    except Exception as e:
        db.rollback()
        logger.error(f"Error al quitar la línea {item_id} del plan {plan_id}: {e}")
        raise HTTPException(status_code=500, detail="Error interno")
