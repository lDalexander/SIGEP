"""
Router del Checklist de Mantenimiento (ENTRADA / SALIDA de turno).

Endpoint único: POST /api/mantenimiento/checklist
  - Sin cabecera de auth (igual que el resto de la app).
  - Idempotente por `request_id` (UNIQUE en BD): un mismo request_id que llega
    más de una vez NO crea un registro duplicado.
  - El servidor recalcula `fecha_turno`/`codigo_turno` con su reloj
    (America/Guayaquil). NO confía en la fecha/hora del cliente.

Contrato de códigos HTTP que la app offline-first depende:
  - 2xx               -> éxito (la app elimina la operación de la cola).
  - 409               -> la app lo trata como ÉXITO (idempotencia).
  - otro 4xx (400...) -> FATAL, NO reintenta. Úsalo SOLO para payload inválido.
  - 5xx / sin resp.   -> reintenta hasta 10 veces (fallos transitorios).
"""
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session
from sqlalchemy.exc import IntegrityError
from datetime import datetime

from database import get_db, logger
from models import MantenimientoChecklistDB, MantenimientoChecklistItemDB
from schemas import MantenimientoChecklistRequest, MantenimientoChecklistResponse
from services.turnos import ahora_gye, calcular_turno

router = APIRouter(prefix="/api/mantenimiento", tags=["Mantenimiento"])

MOMENTOS_VALIDOS = {"ENTRADA", "SALIDA"}


@router.post("/checklist", response_model=MantenimientoChecklistResponse)
def registrar_checklist(datos: MantenimientoChecklistRequest, db: Session = Depends(get_db)):
    """Registra el checklist de mantenimiento enviado por la tablet.

    Devuelve siempre {"id": <int>, "mensaje": "ok"}. La app solo mira el código
    HTTP; el body es informativo.
    """
    # ------------------------------------------------------------------
    # 1) Idempotencia: si el request_id ya fue procesado, NO duplicar.
    #    Respondemos 200 con el registro existente (la app lo toma como éxito).
    # ------------------------------------------------------------------
    existente = (
        db.query(MantenimientoChecklistDB)
        .filter(MantenimientoChecklistDB.request_id == datos.request_id)
        .first()
    )
    if existente:
        logger.info(
            f"Checklist mantenimiento duplicado ignorado: RID {datos.request_id} (id {existente.id})"
        )
        return JSONResponse(status_code=200, content={"id": existente.id, "mensaje": "ok"})

    # ------------------------------------------------------------------
    # 2) Validación de payload -> 400 (FATAL, rechazo permanente real).
    #    Solo para datos que JAMÁS serán válidos por reintentar.
    # ------------------------------------------------------------------
    momento = (datos.momento or "").strip().upper()
    if momento not in MOMENTOS_VALIDOS:
        raise HTTPException(status_code=400, detail=f"momento inválido: '{datos.momento}'")

    try:
        fecha_tablet = datetime.strptime(datos.fecha.strip(), "%Y-%m-%d").date()
    except (ValueError, AttributeError):
        raise HTTPException(status_code=400, detail=f"fecha inválida: '{datos.fecha}' (se espera yyyy-MM-dd)")

    # ------------------------------------------------------------------
    # 3) El servidor recalcula el turno con SU reloj (zona Guayaquil).
    # ------------------------------------------------------------------
    codigo_turno, fecha_turno = calcular_turno(ahora_gye())

    # ------------------------------------------------------------------
    # 4) Inserción de cabecera + ítems.
    # ------------------------------------------------------------------
    try:
        checklist = MantenimientoChecklistDB(
            request_id=datos.request_id,
            maquina=datos.maquina,
            operador=datos.operador,
            momento=momento,
            fecha_turno=fecha_turno,
            codigo_turno=codigo_turno,
            fecha=fecha_tablet,
            hora=datos.hora,
            supervisor=datos.supervisor or None,
            comentarios=datos.comentarios or None,
        )
        for item in datos.items:
            checklist.items.append(
                MantenimientoChecklistItemDB(
                    etiqueta=item.etiqueta,
                    marcado=item.marcado,
                )
            )
        db.add(checklist)
        db.commit()
        db.refresh(checklist)
        logger.info(
            f"Checklist mantenimiento registrado: id {checklist.id} — {datos.operador} en "
            f"{datos.maquina} [{momento} | {codigo_turno} {fecha_turno}] [RID: {datos.request_id}]"
        )
        return {"id": checklist.id, "mensaje": "ok"}

    except IntegrityError:
        # Carrera contra el UNIQUE de request_id: otra petición lo insertó primero.
        # Lo tratamos como duplicado idempotente -> 200 con el registro ya guardado.
        db.rollback()
        actual = (
            db.query(MantenimientoChecklistDB)
            .filter(MantenimientoChecklistDB.request_id == datos.request_id)
            .first()
        )
        if actual:
            logger.info(f"Checklist mantenimiento (carrera) idempotente: RID {datos.request_id} (id {actual.id})")
            return JSONResponse(status_code=200, content={"id": actual.id, "mensaje": "ok"})
        # Si no lo encontramos, fue otro conflicto de integridad -> 409 (la app lo toma como éxito).
        raise HTTPException(status_code=409, detail="Checklist ya registrado")

    except Exception as e:
        # Fallo transitorio -> 5xx para que la app reintente.
        db.rollback()
        logger.error(f"Error en POST /api/mantenimiento/checklist: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.get("/checklist")
def listar_checklists(limit: int = 30, db: Session = Depends(get_db)):
    """Lista los checklists de mantenimiento mas recientes (para el dashboard web).

    Devuelve cabecera + items y un resumen (items_ok / total_items) por registro.
    """
    try:
        tope = max(1, min(limit, 200))
        filas = (
            db.query(MantenimientoChecklistDB)
            .order_by(MantenimientoChecklistDB.id.desc())
            .limit(tope)
            .all()
        )
        salida = []
        for c in filas:
            items = [{"etiqueta": it.etiqueta, "marcado": bool(it.marcado)} for it in c.items]
            salida.append({
                "id": c.id,
                "maquina": c.maquina,
                "operador": c.operador,
                "momento": c.momento,
                "codigo_turno": c.codigo_turno,
                "fecha_turno": c.fecha_turno.isoformat() if c.fecha_turno else None,
                "fecha": c.fecha.isoformat() if c.fecha else None,
                "hora": c.hora,
                "supervisor": c.supervisor,
                "comentarios": c.comentarios,
                "items": items,
                "total_items": len(items),
                "items_ok": sum(1 for it in items if it["marcado"]),
                "creado_en": c.creado_en.isoformat() if c.creado_en else None,
            })
        return salida
    except Exception as e:
        logger.error(f"Error en GET /api/mantenimiento/checklist: {e}")
        raise HTTPException(status_code=500, detail="Error al listar checklists")
