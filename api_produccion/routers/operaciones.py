"""
Router para los endpoints operacionales en piso de producción.
Maneja el inicio de turnos, registro de pallets, y paros de máquina.
"""
import os

from fastapi import APIRouter, Depends, HTTPException, BackgroundTasks, Query, File, UploadFile
from sqlalchemy.orm import Session
from datetime import datetime, timedelta

from database import get_db, logger
from models import OperadorDB, MaquinaDB, SesionTrabajoDB, PalletDB, ParoMaquinaDB, PedidoBodegaDB, UsuarioDB, InsumoDB, RecetaProductoDB, MaquinaProductoDB, MaquinaMarcaFraganciaDB, FraganciaDB, ComentarioTurnoDB, ReporteAppDB, VelocidadMaquinaDB
from schemas import IniciarTurno, RegistrarPalletRequest, FinalizarTurno, IniciarParo, FinalizarParo, ComentarioTurnoRequest, ReporteAppRequest, RegistrarVelocidad
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
# Fotos de paro. Mismo sitio y mismos límites que las de entregas de insumo, que llevan
# funcionando desde antes: se guarda el archivo en disco y en la BD solo su ruta.
PAROS_FOTO_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "static", "paros"
)
os.makedirs(PAROS_FOTO_DIR, exist_ok=True)
FOTO_TIPOS = {"image/jpeg": "jpg", "image/png": "png"}
FOTO_MAX_BYTES = 5 * 1024 * 1024

# Rango aceptable de velocidad, en fundas/min. No es una preferencia estética: el campo lo
# teclea una persona con guantes en una tablet, y un 0 o un 4500 por un dedo torcido
# ensuciarían para siempre la media del turno. Los límites son generosos a propósito —se
# trata de descartar erratas, no de imponer un ritmo.
VELOCIDAD_MIN = 0.1
VELOCIDAD_MAX = 1000.0

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
    """Cierra el paro abierto de la sesión y devuelve su duración.

    **Tolera que el paro ya estuviera cerrado** (2026-08-19). El estado de paro de la
    tablet es local: la app solo apaga su botón verde cuando esta llamada responde bien,
    así que el 404 de antes la dejaba bloqueada *para siempre* si el paro se había cerrado
    por otra vía — un supervisor desde `/admin` → Paros, una doble pulsación, o un
    reintento de la cola offline cuya respuesta se perdió. Pasó con la Máquina 7 el
    2026-08-19: el paro 237 se cerró desde la web a las 15:19 y la máquina estuvo 1 h sin
    poder registrar producción, con el operario reportándolo por la app.

    Cuando ya no hay paro abierto se responde **200 con los datos reales del último paro**
    en vez de 404: el estado que la tablet quiere conseguir —no estar en paro— ya se
    cumple, así que negarlo solo la bloquea. La clave `ya_cerrado` distingue el caso; el
    camino normal **no la lleva**, para que su respuesta siga siendo idéntica a la de
    siempre.
    """
    paro = db.query(ParoMaquinaDB).filter(ParoMaquinaDB.session_id == datos.sesion_id, ParoMaquinaDB.fin_paro.is_(None)).order_by(ParoMaquinaDB.inicio_paro.desc()).first()
    if not paro:
        # Si ningún paro de la sesión está abierto, el más reciente está cerrado por
        # definición: se contesta con SU duración registrada, nunca con una inventada.
        ultimo = (
            db.query(ParoMaquinaDB)
            .filter(ParoMaquinaDB.session_id == datos.sesion_id)
            .order_by(ParoMaquinaDB.inicio_paro.desc())
            .first()
        )
        if ultimo is None:
            # La sesión no tuvo ningún paro. Aquí un 200 tendría que inventarse un
            # `paro_id` y una duración que no existen, así que se mantiene el 404.
            raise HTTPException(status_code=404, detail="No hay paro activo")
        duracion = ultimo.duracion_segundos
        if duracion is None and ultimo.fin_paro and ultimo.inicio_paro:
            duracion = round((ultimo.fin_paro - ultimo.inicio_paro).total_seconds(), 2)
        # Este log es la única traza del desajuste: el 404 de antes era invisible en el
        # journal, y por eso nadie supo que la Máquina 7 llevaba una hora atascada.
        logger.warning(
            f"Reanudación sobre un paro ya cerrado: paro {ultimo.id} — sesión "
            f"{datos.sesion_id}. La tablet lo tenía abierto en local; se responde OK "
            "para desbloquearla."
        )
        return {
            "paro_id": ultimo.id,
            "mensaje": "El paro ya estaba cerrado",
            "duracion_segundos": duracion,
            "ya_cerrado": True,
        }
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
# CAMBIO 6 — Estado del paro, foto del paro y velocidad de máquina (2026-08-19)
# ============================================================================
# Las tres piezas salen del mismo sitio: la tablet lleva estado propio que el servidor no
# puede corregir. `/paro/estado` es lo que le permite dejar de adivinar.


def _paro_resumen(p):
    """Forma mínima de un paro para que la tablet decida qué botón enseñar."""
    return {
        "paro_id": p.id,
        "sesion_id": p.session_id,
        "motivo": p.motivo or "",
        "inicio_paro": p.inicio_paro.strftime("%Y-%m-%d %H:%M:%S") if p.inicio_paro else None,
        "fin_paro": p.fin_paro.strftime("%Y-%m-%d %H:%M:%S") if p.fin_paro else None,
        "duracion_segundos": p.duracion_segundos,
        "abierto": p.fin_paro is None,
        "tiene_foto": bool(p.foto_path),
    }


@router.get("/paro/estado")
def estado_paro(sesion_id: int = Query(...), db: Session = Depends(get_db)):
    """¿Tiene esta sesión un paro abierto ahora mismo?

    Existe por el incidente del 2026-08-19: el estado de paro de la tablet vive en su
    memoria y **nada se lo corrige**. Si el paro se cierra por otra vía —un supervisor
    desde `/admin`, un reintento cuya respuesta se perdió— la tablet se queda con el botón
    verde puesto y sin forma de enterarse. Con esto puede preguntar al arrancar y antes de
    reanudar, y ajustar el botón a la verdad del servidor en vez de a lo que recuerda.

    **Es de solo lectura y no cambia nada.** `paro_abierto` es `null` cuando la máquina no
    está en paro: eso ya es la respuesta, no un error. `ultimo_paro` va aparte para que la
    tablet pueda reconocer el paro que tenía cacheado y saber que se cerró y cuándo.
    """
    sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == sesion_id).first()
    if not sesion:
        raise HTTPException(status_code=404, detail="Sesión no encontrada")

    abierto = (
        db.query(ParoMaquinaDB)
        .filter(ParoMaquinaDB.session_id == sesion_id, ParoMaquinaDB.fin_paro.is_(None))
        .order_by(ParoMaquinaDB.inicio_paro.desc())
        .first()
    )
    ultimo = (
        db.query(ParoMaquinaDB)
        .filter(ParoMaquinaDB.session_id == sesion_id)
        .order_by(ParoMaquinaDB.inicio_paro.desc())
        .first()
    )
    return {
        "sesion_id": sesion_id,
        "turno_abierto": sesion.fin_turno is None,
        "en_paro": abierto is not None,
        "paro_abierto": _paro_resumen(abierto) if abierto else None,
        "ultimo_paro": _paro_resumen(ultimo) if ultimo else None,
    }


@router.post("/paro/{paro_id}/foto")
def subir_foto_paro(
    paro_id: int,
    foto: UploadFile = File(...),
    db: Session = Depends(get_db),
):
    """Adjunta la evidencia fotográfica de un paro. **Opcional**: un paro sin foto es
    normal y no se rechaza en ningún sitio.

    Va en una ruta aparte y no dentro de `/paro/iniciar` **a propósito**: `iniciar` recibe
    JSON y pasarlo a multipart rompería a todas las tablets que hay hoy en planta. Así la
    app puede subir la foto cuando la tenga —al abrir el paro, al cerrarlo o más tarde— y
    encolarla por separado si no hay red, sin bloquear el registro del paro, que es el dato
    que de verdad no se puede perder.

    Se puede sustituir volviendo a llamar: el operario que sale desenfocado necesita poder
    repetirla. El archivo anterior se borra para no dejar huérfanos en `static/paros/`.
    """
    paro = db.query(ParoMaquinaDB).filter(ParoMaquinaDB.id == paro_id).first()
    if paro is None:
        raise HTTPException(status_code=404, detail="Paro no encontrado")

    ext = FOTO_TIPOS.get(foto.content_type)
    if not ext:
        raise HTTPException(status_code=400, detail="La foto debe ser JPG o PNG")
    contenido = foto.file.read()
    if not contenido:
        raise HTTPException(status_code=400, detail="La foto llegó vacía")
    if len(contenido) > FOTO_MAX_BYTES:
        raise HTTPException(status_code=400, detail="La foto no debe superar 5 MB")

    try:
        anterior = paro.foto_path
        sello = datetime.now().strftime("%Y%m%d%H%M%S")
        nombre = f"{paro.id}_{paro.session_id or 0}_{sello}.{ext}"
        with open(os.path.join(PAROS_FOTO_DIR, nombre), "wb") as f:
            f.write(contenido)
        paro.foto_path = f"/static/paros/{nombre}"
        db.commit()

        # Solo después de que la fila apunte al archivo nuevo: si se borrara antes y el
        # commit fallara, el paro quedaría apuntando a un archivo que ya no existe.
        if anterior and anterior != paro.foto_path:
            try:
                os.remove(os.path.join(PAROS_FOTO_DIR, os.path.basename(anterior)))
            except OSError:
                pass    # que no se pueda borrar el viejo no invalida la foto nueva

        logger.info(f"📸 Foto de paro {paro.id} guardada ({len(contenido)} bytes)")
        return {"paro_id": paro.id, "foto_url": paro.foto_path, "mensaje": "Foto guardada"}
    except HTTPException:
        raise
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/paro/{paro_id}/foto: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.post("/velocidad")
def registrar_velocidad(datos: RegistrarVelocidad, db: Session = Depends(get_db)):
    """Guarda la velocidad a la que trabaja la máquina, en **fundas/min**, tal como la
    teclea el operario.

    Una fila por lectura: el ritmo cambia durante el turno y lo que sirve para medir
    eficiencia es la serie, no el último valor. Idempotente por `request_id` con el mismo
    criterio que los pallets —incluida la normalización del `""` que manda Gson, que en una
    columna UNIQUE colisiona con el siguiente y revienta en 500.
    """
    rid = (datos.request_id or "").strip() or None
    if rid:
        ya = db.query(VelocidadMaquinaDB).filter(VelocidadMaquinaDB.request_id == rid).first()
        if ya:
            logger.info(
                f"Velocidad duplicada ignorada: RID {rid} ya registrado "
                f"(id {ya.id}, sesión {ya.session_id})"
            )
            return {
                "mensaje": "Velocidad ya registrada previamente (idempotencia)",
                "velocidad_id": ya.id,
                "duplicado": True,
            }

    if not (VELOCIDAD_MIN <= datos.velocidad <= VELOCIDAD_MAX):
        raise HTTPException(
            status_code=400,
            detail=f"La velocidad debe estar entre {VELOCIDAD_MIN} y {VELOCIDAD_MAX} fundas/min",
        )

    sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == datos.sesion_id).first()
    if not sesion:
        raise HTTPException(status_code=404, detail="Sesión no encontrada")
    if sesion.fin_turno is not None:
        raise HTTPException(status_code=400, detail="No se puede registrar en un turno finalizado")

    try:
        registro = VelocidadMaquinaDB(
            session_id=datos.sesion_id,
            velocidad=datos.velocidad,
            fecha_hora=datetime.now(),
            request_id=rid,
        )
        db.add(registro)
        db.commit()
        db.refresh(registro)
        logger.info(
            f"VELOCIDAD: {datos.velocidad} fundas/min — {sesion.maquina} "
            f"({sesion.operador}) · sesión {datos.sesion_id}"
        )
        return {
            "velocidad_id": registro.id,
            "mensaje": "Velocidad registrada",
            "velocidad": registro.velocidad,
            "duplicado": False,
        }
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/velocidad: {e}")
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
