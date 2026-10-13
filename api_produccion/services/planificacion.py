"""Planificación de producción y cobertura de insumos (v1.7.0).

EL PROBLEMA QUE RESUELVE. Hasta ahora el insumista solo se movía cuando un operario
pedía algo: el flujo entero era reactivo. Pero producción planifica el día de antemano,
y buena parte del material se puede dejar preparado ANTES de que la máquina arranque —
incluso sin saber todavía qué máquina lo va a usar.

De ahí las dos decisiones que mandan sobre el resto:

  * `planes_produccion.maquina` es NULL-able. Hay producción planificada que aún no
    tiene máquina asignada y el material se deja para que después cualquiera lo tome.
    Obligar a elegir máquina mataría justo ese caso.

  * LA COBERTURA SE CALCULA, NO SE GUARDA. Un contador `cantidad_entregada` en
    `plan_insumos` habría que mantenerlo sincronizado a mano y se desfasaría en cuanto
    el admin corrija una cantidad desde la pestaña «Insumos», que es algo que ya se
    puede hacer hoy. Se suma en el momento, que además es lo único que no puede mentir.

QUÉ CUENTA COMO ENTREGADO. Las dos vías por las que el material llega a la línea:
  - entregas proactivas atadas al plan (`entregas_proactivas.plan_id`),
  - pedidos atados al plan, por lo RECIBIDO si el operario ya confirmó, y si no por lo
    que el insumista declaró entregado. Lo solicitado no cuenta: pedir no es tener.
"""

from sqlalchemy import func

from models import EntregaProactivaDB, InsumoDB, PedidoBodegaDB, PlanInsumoDB, PlanProduccionDB
from services import bom

# Lo que se puede escribir en `plan_insumos.prioridad`. Es la palanca del supervisor
# cuando el plan está por arrancar y ese material no ha llegado.
PRIORIDADES = ("Normal", "Alta", "Urgente")
ESTADOS_PLAN = ("Planificado", "En Curso", "Completado", "Cancelado")


def combinacion_de(plan):
    """Las cinco dimensiones del plan, como las espera `services.bom`."""
    return {
        "maquina": plan.maquina,
        "producto": plan.producto,
        "marca": plan.marca,
        "presentacion": plan.presentacion,
        "fragancia": plan.fragancia,
    }


def explotar_bom(db, plan, sobrescribir=False):
    """Crea las filas de `plan_insumos` a partir del BOM. Devuelve cuántas añadió.

    NO pisa lo que ya hay salvo que se pida: quien planifica ajusta cantidades a mano y
    un recálculo no puede borrarle el trabajo. Con `sobrescribir=True` se recalculan las
    cantidades de las filas que vengan del BOM, pero tampoco se borran las que alguien
    añadió a mano: eso lo decide quien las añadió.

    LA CANTIDAD SALE DE `cantidad_sugerida` DE LA RECETA TAL CUAL, sin escalarla por
    `cantidad_planificada`. El BOM de hoy dice «cuánto se suele pedir de una vez», no
    «cuánto lleva cada unidad producida», así que no hay con qué multiplicar sin
    inventarse un dato. Queda como punto de partida editable.
    """
    sugeridas = bom.cantidades_sugeridas(db, **combinacion_de(plan))
    if not sugeridas:
        return 0

    activos = {
        i.id for i in db.query(InsumoDB.id)
        .filter(InsumoDB.id.in_(list(sugeridas.keys())), InsumoDB.activo.is_(True)).all()
    }

    existentes = {
        pi.insumo_id: pi for pi in
        db.query(PlanInsumoDB).filter(PlanInsumoDB.plan_id == plan.id).all()
    }

    nuevas = 0
    for insumo_id, cantidad in sugeridas.items():
        if insumo_id not in activos:
            continue
        fila = existentes.get(insumo_id)
        if fila is None:
            db.add(PlanInsumoDB(plan_id=plan.id, insumo_id=insumo_id, cantidad_requerida=cantidad))
            nuevas += 1
        elif sobrescribir:
            fila.cantidad_requerida = cantidad
    return nuevas


def entregado_por_plan(db, plan_ids):
    """-> {(plan_id, insumo_id): cantidad}. Suma las dos vías de entrega."""
    if not plan_ids:
        return {}
    total = {}

    proactivas = (
        db.query(
            EntregaProactivaDB.plan_id,
            EntregaProactivaDB.insumo_id,
            func.sum(EntregaProactivaDB.cantidad),
        )
        .filter(
            EntregaProactivaDB.plan_id.in_(plan_ids),
            EntregaProactivaDB.insumo_id.isnot(None),
            # Una entrega rechazada por el supervisor no cubre nada.
            EntregaProactivaDB.estado != "Rechazada",
        )
        .group_by(EntregaProactivaDB.plan_id, EntregaProactivaDB.insumo_id)
        .all()
    )
    for plan_id, insumo_id, cantidad in proactivas:
        total[(plan_id, insumo_id)] = total.get((plan_id, insumo_id), 0) + int(cantidad or 0)

    # `coalesce`: lo recibido manda, y mientras el operario no confirme vale lo que el
    # insumista declaró entregado. Lo SOLICITADO no entra: pedir no es tener.
    pedidos = (
        db.query(
            PedidoBodegaDB.plan_id,
            PedidoBodegaDB.insumo_id,
            func.sum(func.coalesce(PedidoBodegaDB.cantidad_recibida, PedidoBodegaDB.cantidad_entregada)),
        )
        .filter(
            PedidoBodegaDB.plan_id.in_(plan_ids),
            PedidoBodegaDB.insumo_id.isnot(None),
        )
        .group_by(PedidoBodegaDB.plan_id, PedidoBodegaDB.insumo_id)
        .all()
    )
    for plan_id, insumo_id, cantidad in pedidos:
        total[(plan_id, insumo_id)] = total.get((plan_id, insumo_id), 0) + int(cantidad or 0)

    return total


def detalle_insumos(db, plan, entregado=None):
    """Lista de insumos del plan con lo requerido, lo entregado y lo que falta."""
    if entregado is None:
        entregado = entregado_por_plan(db, [plan.id])

    filas = (
        db.query(PlanInsumoDB, InsumoDB)
        .join(InsumoDB, InsumoDB.id == PlanInsumoDB.insumo_id)
        .filter(PlanInsumoDB.plan_id == plan.id)
        .order_by(InsumoDB.nombre.asc())
        .all()
    )

    detalle = []
    for pi, insumo in filas:
        puesto = entregado.get((plan.id, insumo.id), 0)
        requerido = pi.cantidad_requerida or 0
        detalle.append({
            "id": pi.id,
            "insumo_id": insumo.id,
            "insumo": insumo.nombre,
            "unidad": insumo.unidad_medida or "",
            "bodega": insumo.bodega or "EMPAQUE",
            "requerido": requerido,
            "entregado": puesto,
            "falta": max(0, requerido - puesto),
            "completo": puesto >= requerido,
            "prioridad": pi.prioridad or "Normal",
            "observaciones": pi.observaciones,
        })
    return detalle


def resumen(db, plan, entregado=None):
    """El plan con su cobertura, listo para pintar."""
    insumos = detalle_insumos(db, plan, entregado)
    requerido_total = sum(i["requerido"] for i in insumos)
    entregado_total = sum(min(i["entregado"], i["requerido"]) for i in insumos)

    return {
        "id": plan.id,
        "fecha": plan.fecha.isoformat() if plan.fecha else None,
        "turno": plan.turno,
        "maquina": plan.maquina,            # None = sin máquina asignada todavía
        "producto": plan.producto,
        "marca": plan.marca,
        "presentacion": plan.presentacion,
        "fragancia": plan.fragancia,
        "cantidad_planificada": plan.cantidad_planificada,
        "unidad": plan.unidad,
        "estado": plan.estado,
        "observaciones": plan.observaciones,
        "creado_por": plan.creado_por,
        "creado_en": plan.creado_en.strftime("%Y-%m-%d %H:%M:%S") if plan.creado_en else None,
        "insumos": insumos,
        # El porcentaje se mide sobre lo REQUERIDO y cada insumo tope en su requerido:
        # traer el triple de cinta no compensa que falten las fundas, y sin el tope un
        # plan con un insumo de sobra saldría «cubierto» con la mitad sin llegar.
        "cobertura": round(100.0 * entregado_total / requerido_total, 1) if requerido_total else None,
        "insumos_incompletos": sum(1 for i in insumos if not i["completo"]),
        "hay_urgentes": any(i["prioridad"] in ("Alta", "Urgente") and not i["completo"] for i in insumos),
    }


def planes_del_rango(db, desde, hasta, maquina=None, estado=None):
    """Planes del rango con su cobertura, en una sola pasada de cobertura."""
    q = db.query(PlanProduccionDB).filter(
        PlanProduccionDB.fecha >= desde, PlanProduccionDB.fecha <= hasta
    )
    if maquina:
        q = q.filter(PlanProduccionDB.maquina == maquina)
    if estado:
        q = q.filter(PlanProduccionDB.estado == estado)

    planes = q.order_by(PlanProduccionDB.fecha.asc(), PlanProduccionDB.id.asc()).all()
    entregado = entregado_por_plan(db, [p.id for p in planes])
    return [resumen(db, p, entregado) for p in planes]
