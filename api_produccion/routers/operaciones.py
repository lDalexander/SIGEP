"""
Router para los endpoints operacionales en piso de producción.
Maneja el inicio de turnos, registro de pallets, y paros de máquina.
"""
from fastapi import APIRouter, Depends, HTTPException, BackgroundTasks, Query
from sqlalchemy.orm import Session
from datetime import datetime, timedelta

from database import get_db, logger
from models import OperadorDB, MaquinaDB, SesionTrabajoDB, PalletDB, ParoMaquinaDB, PedidoBodegaDB, UsuarioDB, InsumoDB, RecetaProductoDB, MaquinaProductoDB, MaquinaMarcaFraganciaDB, FraganciaDB, ComentarioTurnoDB, ReporteAppDB
from schemas import IniciarTurno, RegistrarPalletRequest, FinalizarTurno, IniciarParo, FinalizarParo, ComentarioTurnoRequest, ReporteAppRequest
from ws_manager import manager
from services.email_service import notificar_reporte_app

# Estados de un pedido de insumo que aún están "vivos" (no terminados). Al cerrar
# el turno del operario, estos quedarían huérfanos (el operario ya no puede
# confirmar la recepción), así que se cierran automáticamente. Ver CAMBIO 3.
ESTADOS_PEDIDO_ACTIVO = ["Pendiente", "En Camino", "Entregado_Insumista"]
ESTADO_PEDIDO_CIERRE_TURNO = "Cerrado_Fin_Turno"

router = APIRouter(prefix="/api", tags=["Operaciones"])


def _orden_presentacion(p: str):
    """Ordena presentaciones por peso real (100 GR < 500 GR < 1 KG < 2 KG ...),
    no alfabéticamente ('100 GR' < '1 KG' sería incorrecto en texto)."""
    try:
        partes = p.strip().upper().split()
        valor = float(partes[0].replace(",", "."))
        unidad = partes[1] if len(partes) > 1 else "GR"
        gramos = valor * 1000 if unidad.startswith("K") else valor
        return (gramos, p)
    except Exception:
        return (float("inf"), p)

# Anti-ráfaga: defensa server-side contra vaciados masivos de cola desde la tablet.
# Aplica incluso cuando el cliente no envía request_id (compatibilidad con apps viejas).
# Un operador real registra 1 pallet cada 30-60 segundos, así que más de 3 inserts
# en 5 segundos a la misma sesión es siempre anomalía.
ANTI_RAFAGA_VENTANA_SEG = 5
ANTI_RAFAGA_MAX_INSERTS = 3

@router.get("/operadores")
def obtener_operadores(tipo: str = Query(None), db: Session = Depends(get_db)):
    """Operarios activos para el selector de la tablet.

    `tipo` (SOLIDO / LIQUIDO) es OPCIONAL y filtra por línea. Sin él se devuelven
    todos, con el mismo formato de siempre: la app que hay hoy en las 21 tablets no
    lo envía, así que sigue viendo exactamente lo mismo que antes. Para que el
    selector muestre solo a los de su línea, la app debe pasar el tipo de la
    máquina en la que está trabajando (`GET /api/maquinas` ya devuelve ese tipo).

    Un valor de `tipo` desconocido se ignora y se devuelven todos, en vez de dejar
    la tablet con un selector vacío.
    """
    q = db.query(OperadorDB).filter(OperadorDB.activo.is_(True))
    if tipo:
        t = tipo.strip().upper().replace("Á", "A").replace("Í", "I").replace("Ó", "O")
        if t in ("SOLIDO", "LIQUIDO"):
            q = q.filter(OperadorDB.tipo == t)
    operadores = q.all()
    return [{"id": op.id, "nombre": op.nombre} for op in operadores]

@router.get("/maquinas")
def obtener_maquinas(db: Session = Depends(get_db)):
    """Lista las máquinas activas con su jerarquía de productos POR máquina.

    Cada máquina trae solo las marcas/presentaciones que ESA máquina puede producir,
    leídas de `maquina_productos` (la fuente de verdad de la jerarquía). La app las
    usa para poblar en cascada los selectores al iniciar turno. Si una máquina no
    tiene jerarquía configurada, devuelve `marcas: []` (la app cae a su fallback
    local; NUNCA se devuelve un catálogo global, que era la causa del bug histórico).
    """
    maquinas = db.query(MaquinaDB).filter(MaquinaDB.activa.is_(True)).all()

    # maquina_id -> { marca -> [presentaciones] }, solo combinaciones activas.
    por_maquina: dict[int, dict[str, list]] = {}
    filas = (
        db.query(MaquinaProductoDB)
        .filter(MaquinaProductoDB.activo.is_(True))
        .all()
    )
    for f in filas:
        if not f.marca:
            continue
        marcas = por_maquina.setdefault(f.maquina_id, {})
        pres = marcas.setdefault(f.marca, [])
        if f.presentacion and f.presentacion not in pres:
            pres.append(f.presentacion)

    def _catalogo(maquina_id: int) -> list:
        marcas = por_maquina.get(maquina_id, {})
        return [
            {"nombre": marca, "presentaciones": sorted(presentaciones, key=_orden_presentacion)}
            for marca, presentaciones in sorted(marcas.items())
        ]

    return [
        {"id": maq.id, "nombre": maq.nombre, "tipo": maq.tipo, "marcas": _catalogo(maq.id)}
        for maq in maquinas
    ]

@router.get("/fragancias")
def obtener_fragancias(maquina: str = Query(None), marca: str = Query(None),
                       db: Session = Depends(get_db)):
    """Fragancias que puede hacer una máquina de una marca (RUTA NUEVA, 2026-08-06).

    Hasta ahora la fragancia era universal y la app llevaba su propia lista fija
    (Floral / Limón). Con la línea líquida en producción cada máquina y marca hace
    fragancias distintas, así que se administran desde la web (tabla
    `maquina_marca_fragancias`) y se leen aquí.

    Es una ruta NUEVA a propósito: `GET /api/maquinas` no cambia, así que las 21
    tablets que hay hoy en planta siguen funcionando exactamente igual sin
    actualizarse. Ver CAMBIO_ANDROID_fragancias.md.

    - Con `maquina` y `marca`: las fragancias activas de esa combinación.
    - Sin parámetros (o si esa combinación no tiene ninguna configurada): el
      catálogo activo completo. Nunca se devuelve una lista vacía por falta de
      configuración — dejaría al operario sin poder elegir fragancia y sin poder
      iniciar el turno. Mismo criterio que `/api/maquinas`, que cae al fallback de
      la app cuando una máquina no tiene jerarquía.

    Formato: `["Floral", "Limón"]`, la lista de cadenas que el selector necesita.
    """
    catalogo = [f.nombre for f in db.query(FraganciaDB)
                .filter(FraganciaDB.activa.is_(True))
                .order_by(FraganciaDB.nombre).all()]
    if not maquina or not marca:
        return catalogo

    maq = db.query(MaquinaDB).filter(MaquinaDB.nombre == maquina.strip()).first()
    if not maq:
        return catalogo
    propias = [f.fragancia for f in db.query(MaquinaMarcaFraganciaDB).filter(
        MaquinaMarcaFraganciaDB.maquina_id == maq.id,
        MaquinaMarcaFraganciaDB.marca == marca.strip(),
        MaquinaMarcaFraganciaDB.activo.is_(True),
    ).order_by(MaquinaMarcaFraganciaDB.fragancia).all()]
    return propias or catalogo


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

    # 3. Validación de jerarquía: la combinación (máquina, marca, presentación) debe
    # existir y estar activa en maquina_productos. Defensa contra catálogos viejos en
    # caché de la tablet o combinaciones imposibles para esa línea. Solo se valida si
    # la máquina TIENE jerarquía configurada; si no tiene ninguna fila, no se bloquea
    # (compatibilidad: una máquina sin configurar no debe impedir trabajar).
    maquina = db.query(MaquinaDB).filter(MaquinaDB.nombre == datos.maquina).first()
    if maquina:
        tiene_jerarquia = db.query(MaquinaProductoDB).filter(
            MaquinaProductoDB.maquina_id == maquina.id,
            MaquinaProductoDB.activo.is_(True),
        ).first()
        if tiene_jerarquia:
            combo_valido = db.query(MaquinaProductoDB).filter(
                MaquinaProductoDB.maquina_id == maquina.id,
                MaquinaProductoDB.marca == datos.marca,
                MaquinaProductoDB.presentacion == datos.presentacion,
                MaquinaProductoDB.activo.is_(True),
            ).first()
            if not combo_valido:
                raise HTTPException(
                    status_code=422,
                    detail=f"{datos.maquina} no produce {datos.marca} {datos.presentacion}.",
                )

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
def finalizar(datos: FinalizarTurno, background_tasks: BackgroundTasks, db: Session = Depends(get_db)):
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

        # --- Cierre automático de pedidos de insumo huérfanos (CAMBIO 3) ---
        # Al cerrar el turno, los pedidos que siguen vivos no podrán completarse
        # (el operario ya no confirmará recepción). Se cierran con un estado propio
        # y se avisa por WebSocket para que desaparezcan de la bandeja del insumista
        # al instante (el refresco de 15s es el respaldo).
        pedidos_activos = db.query(PedidoBodegaDB).filter(
            PedidoBodegaDB.session_id == datos.sesion_id,
            PedidoBodegaDB.estado.in_(ESTADOS_PEDIDO_ACTIVO),
        ).all()
        pedidos_cerrados = [
            {"id": p.id, "categoria": (p.categoria or "EMPAQUE").upper()}
            for p in pedidos_activos
        ]
        for p in pedidos_activos:
            p.estado = ESTADO_PEDIDO_CIERRE_TURNO

        sesion.fin_turno = datetime.now()
        sesion.duracion_minutos = (sesion.fin_turno - sesion.inicio_turno).total_seconds() / 60
        db.commit()

        # Aviso WS por cada pedido cerrado. Reutilizamos el evento "pedido_aceptado"
        # que la app ya maneja (apaga la alarma del pedido y refresca las listas);
        # como el pedido ya no está en un estado activo, deja de aparecer.
        for pc in pedidos_cerrados:
            background_tasks.add_task(
                manager.broadcast_to_tipo,
                pc["categoria"],
                {"evento": "pedido_aceptado", "solicitud_id": pc["id"]},
            )

        if pedidos_cerrados:
            logger.info(
                f"Turno finalizado: sesión {sesion.id} — {round(sesion.duracion_minutos, 1)} min "
                f"· {len(pedidos_cerrados)} pedido(s) de insumo cerrado(s) por fin de turno"
            )
        else:
            logger.info(f"Turno finalizado: sesión {sesion.id} — {round(sesion.duracion_minutos, 1)} min")
        return {
            "mensaje": "Turno finalizado",
            "duracion_minutos": round(sesion.duracion_minutos, 2),
            "pedidos_cerrados": len(pedidos_cerrados),
        }
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


# ============================================================================
# CAMBIO 5 — Comentarios del turno y reportes de problemas con la app
# ============================================================================
# Offline-first: la tablet los encola y los envía el SyncWorker. Idempotentes por
# `request_id` (igual que registrar_pallet): un duplicado responde 200 sin insertar.

def _guardar_feedback(modelo, datos, db, etiqueta):
    rid = (datos.request_id or "").strip() or None
    if rid:
        existente = db.query(modelo).filter(modelo.request_id == rid).first()
        if existente:
            logger.info(f"{etiqueta} duplicado ignorado: RID {rid} (id {existente.id})")
            return {"id": existente.id, "mensaje": f"{etiqueta} ya registrado (idempotencia)", "duplicado": True}

    texto = (datos.texto or "").strip()
    if not texto:
        raise HTTPException(status_code=400, detail="El texto no puede estar vacío")

    try:
        # session_id <= 0 (p.ej. el placeholder -99 de un turno aún encolado) -> None.
        session_id = datos.session_id if (datos.session_id or 0) > 0 else None
        fila = modelo(
            session_id=session_id,
            maquina=datos.maquina,
            operador=datos.operador,
            texto=texto[:1000],
            request_id=rid,
        )
        db.add(fila)
        db.commit()
        db.refresh(fila)
        logger.info(f"{etiqueta} registrado: id {fila.id} — {datos.operador or '?'} / {datos.maquina or '?'}")
        return {"id": fila.id, "mensaje": f"{etiqueta} registrado"}
    except Exception as e:
        db.rollback()
        logger.error(f"Error al guardar {etiqueta.lower()}: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.post("/comentarios_turno")
def crear_comentario_turno(datos: ComentarioTurnoRequest, db: Session = Depends(get_db)):
    """Guarda un comentario libre del turno escrito por el operario."""
    return _guardar_feedback(ComentarioTurnoDB, datos, db, "Comentario de turno")


@router.post("/reportes_app")
def crear_reporte_app(datos: ReporteAppRequest, background_tasks: BackgroundTasks,
                      db: Session = Depends(get_db)):
    """Guarda un reporte de problema con la aplicación enviado por el operario.

    Desde el 2026-08-07 avisa además por correo (`services/email_service`). Dos
    detalles que no son evidentes:

    - **Solo se envía si la fila es nueva.** El endpoint es idempotente por
      `request_id` y una tablet sin red reintenta el mismo reporte hasta que entra;
      mandar correo también en el duplicado llenaría el buzón de copias del mismo
      incidente. `_guardar_feedback` marca esos casos con `duplicado: True`.
    - **Va en BackgroundTasks y el envío se traga sus propios errores**, así que un
      SMTP caído o lento no retrasa ni rompe la respuesta a la tablet. Un reporte
      guardado sin correo es un problema menor; una tablet colgada esperando al
      servidor de correo, no.
    """
    resultado = _guardar_feedback(ReporteAppDB, datos, db, "Reporte de app")
    if not resultado.get("duplicado"):
        background_tasks.add_task(
            notificar_reporte_app,
            resultado.get("id"),
            datos.maquina,
            datos.operador,
            datos.texto,
            datos.session_id if (datos.session_id or 0) > 0 else None,
        )
    return resultado
