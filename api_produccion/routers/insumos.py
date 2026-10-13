"""
Router para los endpoints del sistema MES (Insumos).
Permite la comunicación estilo "Uber" entre operadores de línea y personal de bodega.
"""
import os
from fastapi import APIRouter, Depends, HTTPException, BackgroundTasks, Form, File, UploadFile, Query
from sqlalchemy.orm import Session
from sqlalchemy import func
from datetime import datetime, timedelta
from database import get_db, logger
from models import PedidoBodegaDB, SesionTrabajoDB, MaquinaDB, EntregaProactivaDB, UsuarioDB, InsumoDB
from schemas import NuevoPedidoRequest, PedidoAccionRequest
import schemas
import models
from ws_manager import manager
from services.fcm_service import notificar_insumistas_por_fcm
from services.email_service import notificar_pedido_insumo
from services import notificaciones_pedido, sesiones_usuario

ENTREGAS_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "static", "entregas"
)
os.makedirs(ENTREGAS_DIR, exist_ok=True)

router = APIRouter(prefix="/api/insumos", tags=["Insumos MES"])

# Palabras con las que se adivinaba la bodega cuando el pedido era texto libre. Se
# conservan SOLO para las tablets anteriores a la 1.7.0, que no mandan `insumo_id`.
# Es una heurística mala —"Lámina para polvo" se iba a Granel— y por eso la reemplaza
# `insumos.bodega`; pero quitarla dejaría sin enrutar a las tablets que aún no se han
# actualizado, que se actualizan a mano por sideload.
PALABRAS_GRANEL = ["granel", "preten", "polvo"]


def _bodega_por_texto(detalle):
    return "Granel" if any(p in (detalle or "").lower() for p in PALABRAS_GRANEL) else "Empaque"


def _describir_pedido(insumo, sesion):
    """Texto de `detalle_pedido` cuando el pedido viene por `insumo_id`.

    La columna es contrato congelado con la app y es lo que hace legible el histórico y
    los Excel, así que se sigue escribiendo: lo que cambia es QUIÉN la escribe. Antes la
    tecleaba el operario y de ahí salía el enrutado; ahora la arma el servidor a partir
    del catálogo, y el enrutado sale de `insumos.bodega`.
    """
    contexto = " ".join(p for p in (
        sesion.producto if sesion else None,
        sesion.marca if sesion else None,
        sesion.presentacion if sesion else None,
        sesion.fragancia if sesion else None,
    ) if p)
    texto = "%s - %s" % (insumo.nombre, contexto) if contexto else insumo.nombre
    return texto[:255]


@router.post("/pedido_dinamico")
def crear_pedido_dinamico(
    datos: NuevoPedidoRequest, 
    background_tasks: BackgroundTasks, # 🔥 1. Agregamos esto aquí
    db: Session = Depends(get_db)
):
    """Crea un pedido desde la tablet en piso hacia la bodega.

    Desde la v1.7.0 el pedido viaja con `insumo_id` y `request_id`. Los dos son
    OPCIONALES: las tablets se actualizan por sideload y durante un tiempo conviven
    versiones, así que una 1.6.0 que mande solo `detalle_pedido` sigue funcionando
    exactamente igual que antes.
    """
    try:
        sesion = db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id == datos.sesion_id).first()

        # IDEMPOTENCIA. Es lo que mete este flujo en la cola offline: hasta ahora pedir
        # un insumo era la ÚNICA operación del operario que exigía red. Un reintento del
        # SyncWorker tiene que devolver éxito, no crear un segundo pedido; el worker
        # trata el duplicado como operación cumplida.
        if datos.request_id:
            ya = db.query(PedidoBodegaDB).filter(
                PedidoBodegaDB.request_id == datos.request_id
            ).first()
            if ya:
                logger.info(f"↩️ Pedido duplicado ignorado (request_id={datos.request_id})")
                return {"mensaje": "Pedido ya registrado", "duplicado": True, "id": ya.id}

        insumo = None
        if datos.insumo_id:
            insumo = db.query(InsumoDB).filter(InsumoDB.id == datos.insumo_id).first()
            if insumo is None:
                raise HTTPException(status_code=404, detail="El insumo no existe en el catálogo")
            if not insumo.activo:
                raise HTTPException(status_code=400, detail=f"El insumo «{insumo.nombre}» está dado de baja")

        if insumo is not None:
            # El dato manda sobre la adivinanza. `bodega` se guarda capitalizada porque
            # es lo que `/pendientes/{rol}` lleva comparando desde siempre.
            categoria_asignada = (insumo.bodega or "EMPAQUE").capitalize()
            detalle = _describir_pedido(insumo, sesion)
            unidad = insumo.unidad_medida
        else:
            categoria_asignada = _bodega_por_texto(datos.detalle_pedido)
            detalle = datos.detalle_pedido
            unidad = None

        nuevo_pedido = PedidoBodegaDB(
            request_id=datos.request_id,
            session_id=datos.sesion_id,
            detalle_pedido=detalle,
            insumo_id=insumo.id if insumo else None,
            cantidad_solicitada=datos.cantidad,
            unidad=unidad,
            categoria=categoria_asignada
        )
        db.add(nuevo_pedido)
        db.commit()
        db.refresh(nuevo_pedido)

        maquina_txt = sesion.maquina if sesion else "?"
        operador_txt = sesion.operador if sesion else "?"

        # 🔥 2. El aviso sale por el helper, que es el único sitio donde se decide
        # quién se entera de qué. Sigue siendo asíncrono y no bloquea la respuesta.
        notificaciones_pedido.notificar(
            db, notificaciones_pedido.NUEVO, nuevo_pedido, background_tasks
        )

        # 🔔 3. Notificación FCM data-only para tablets en background/cerradas.
        background_tasks.add_task(
            notificar_insumistas_por_fcm,
            categoria_asignada.upper(),
            f"Nuevo pedido — {maquina_txt}",
            f"{datos.cantidad} x {detalle} (operador: {operador_txt})",
            nuevo_pedido.id,
        )

        # 📧 4. Correo a administración/bodega (asíncrono y tolerante a fallos).
        background_tasks.add_task(
            notificar_pedido_insumo,
            maquina_txt, operador_txt, detalle, datos.cantidad,
            categoria_asignada, nuevo_pedido.id,
        )

        origen = "catálogo" if insumo else "texto libre (tablet anterior a la 1.7.0)"
        logger.info(
            f"📦 PEDIDO ENRUTADO A [{categoria_asignada}] por {origen}: "
            f"{datos.cantidad} x {detalle}"
        )
        return {"mensaje": "Pedido enviado a bodega", "id": nuevo_pedido.id}

    except HTTPException:
        # Un 404/400 de validación no es un fallo del servidor: si se deja caer al
        # `except Exception` sale convertido en 500 y la app lo reintentaría para
        # siempre en vez de marcar la operación como fatal.
        db.rollback()
        raise
    except Exception as e:
        db.rollback()
        logger.error(f"Error al crear pedido: {e}")
        raise HTTPException(status_code=500, detail="Error interno")
@router.get("/catalogo")
def obtener_catalogo(
    bodega: str = Query(None, description="EMPAQUE o GRANEL. Sin esto, se deduce del token."),
    db: Session = Depends(get_db),
    token: str = Depends(sesiones_usuario.token_de_cabecera),
):
    """Catálogo de insumos de una bodega, para el formulario de entrega del insumista.

    El operario tiene `/api/sesion/{id}/insumos_permitidos`, que filtra por el BOM del
    turno. El insumista no tiene turno contra el que filtrar —entrega lo que haga falta,
    donde haga falta—, así que necesita el catálogo entero de SU bodega. Sin este
    endpoint su formulario seguiría con la lista quemada en el APK
    (`["Cajas","Botellas","Fajillas","Etiquetas","Tapas"]`), que no coincide ni con la
    del operario ni con la tabla.

    La bodega sale del token cuando lo hay; si no, del parámetro. Un insumista de Granel
    no tiene por qué ver el empaque, y al revés: es una lista más corta y sin ruido.
    """
    try:
        sesion = sesiones_usuario.resolver_token(db, token)
        if sesion is not None and sesion.rol:
            bodega = "GRANEL" if "granel" in sesion.rol.lower() else "EMPAQUE"

        q = db.query(InsumoDB).filter(InsumoDB.activo.is_(True))
        destino = (bodega or "").strip().upper()
        if destino in ("EMPAQUE", "GRANEL"):
            q = q.filter(InsumoDB.bodega == destino)
        # Con una bodega que no se reconoce se devuelve TODO en vez de nada: una lista
        # vacía dejaría al insumista sin poder registrar la entrega que ya hizo.

        return [
            {
                "id": i.id,
                "nombre": i.nombre,
                "categoria": i.categoria or "",
                "unidad": i.unidad_medida or "",
                "bodega": i.bodega or "EMPAQUE",
                "cantidad_sugerida": 1,
            }
            for i in q.order_by(InsumoDB.nombre.asc()).all()
        ]
    except Exception as e:
        logger.error(f"Error en /insumos/catalogo: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.get("/pendientes/{rol}")
def obtener_pedidos_pendientes(
    rol: str,
    db: Session = Depends(get_db),
    token: str = Depends(sesiones_usuario.token_de_cabecera),
):
    """Pedidos pendientes de la bodega que le toca a quien pregunta.

    EL ROL VIENE EN LA URL, es decir, LO ELIGE EL CLIENTE. Cualquiera con la dirección
    podía mirar —y tomar— los pedidos de cualquier bodega. Desde la v1.7.0, si la
    petición trae `X-User-Token` el rol se saca de la SESIÓN y se ignora el de la URL.

    Sin token se sigue respetando el de la URL: las tablets se actualizan a mano por
    sideload y durante un tiempo conviven versiones; exigirlo hoy dejaría a media planta
    sin poder atender pedidos. El caso queda anotado en el log para poder medir cuántas
    tablets faltan antes de volverlo obligatorio.
    """
    try:
        sesion = sesiones_usuario.resolver_token(db, token)
        if sesion is not None:
            if (sesion.rol or "").lower() != (rol or "").lower():
                logger.warning(
                    f"Rol de la URL ignorado: la sesión {sesion.id} es «{sesion.rol}» "
                    f"y pidió la bandeja de «{rol}»."
                )
            rol = sesion.rol or rol
        else:
            logger.info(f"Bandeja de «{rol}» servida SIN token (tablet anterior a la 1.7.0).")

        rol_seguro = (rol or "").lower()
        if "granel" in rol_seguro or "mezcla" in rol_seguro:
            categoria_buscada = "Granel"
        else:
            categoria_buscada = "Empaque"
            
        query = db.query(PedidoBodegaDB, SesionTrabajoDB)\
                  .join(SesionTrabajoDB, SesionTrabajoDB.id == PedidoBodegaDB.session_id)\
                  .filter(PedidoBodegaDB.estado == "Pendiente")
                  
        if "supervisor" not in rol_seguro:
            query = query.filter(PedidoBodegaDB.categoria == categoria_buscada)
            
        pedidos = query.order_by(PedidoBodegaDB.fecha_solicitud.asc()).all()

        resultados = []
        for pedido, sesion in pedidos:
            resultados.append({
                "id": pedido.id,
                "maquina": sesion.maquina,
                "operador": sesion.operador,
                "detalle": pedido.detalle_pedido,
                "cantidad": pedido.cantidad_solicitada,
                "hora": pedido.fecha_solicitud.strftime("%H:%M:%S")
            })
        return resultados
    except Exception as e:
        logger.error(f"Error al obtener pendientes: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.put("/aceptar")
def aceptar_pedido(
    datos: schemas.PedidoAccionRequest,
    background_tasks: BackgroundTasks, # 🔥 1. Agregamos el BackgroundTasks
    db: Session = Depends(get_db),
    token: str = Depends(sesiones_usuario.token_de_cabecera),
):
    """El insumista toma el pedido.

    `insumista_id` llega en el cuerpo, o sea que lo decide el cliente: con el token
    presente se usa el de la SESIÓN, para que nadie pueda apuntarse un pedido a nombre
    de otro —ni por error ni a propósito— y las estadísticas de bodega digan la verdad.
    """
    pedido = db.query(models.PedidoBodegaDB).filter(models.PedidoBodegaDB.id == datos.pedido_id).first()

    if not pedido:
        raise HTTPException(status_code=404, detail="El pedido no existe")

    if pedido.estado.strip().lower() != "pendiente":
        raise HTTPException(status_code=400, detail=f"Estado actual: {pedido.estado}")

    sesion = sesiones_usuario.resolver_token(db, token)
    insumista_id = sesion.usuario_id if sesion is not None else datos.insumista_id
    if sesion is not None and datos.insumista_id != sesion.usuario_id:
        logger.warning(
            f"insumista_id del cuerpo ({datos.insumista_id}) ignorado: "
            f"la sesión {sesion.id} es del usuario {sesion.usuario_id}."
        )

    pedido.estado = "En Camino"
    pedido.insumista_id = insumista_id
    pedido.fecha_aceptacion = datetime.now()
    
    db.commit()

    # 🔥 2. EL GRITO GLOBAL: a los demás insumistas para que apaguen la alarma de un
    # pedido que ya tiene dueño, y a la tablet del operario para que vea que viene.
    notificaciones_pedido.notificar(
        db, notificaciones_pedido.ACEPTADO, pedido, background_tasks
    )

    return {"status": "ok", "message": "Pedido aceptado", "mensaje": "Pedido aceptado"}

@router.put("/entregar")
def entregar_pedido(
    datos: PedidoAccionRequest,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    token: str = Depends(sesiones_usuario.token_de_cabecera),
):
    """El insumista marca el pedido como entregado y queda esperando al operario.

    Hasta la v1.7.0 este endpoint no avisaba a NADIE: el operario se enteraba por su
    sondeo de 5 s de que tenía algo que confirmar.
    """
    pedido = db.query(models.PedidoBodegaDB).filter(models.PedidoBodegaDB.id == datos.pedido_id).first()

    if not pedido or pedido.estado != "En Camino":
        raise HTTPException(status_code=400, detail="El pedido no está en camino")

    # Entregar un pedido que aceptó otro deja el histórico mintiendo: la entrega se
    # apuntaría a nombre de quien lo tomó, no de quien lo llevó. Solo se comprueba con
    # token, que es el único dato de la petición que el cliente no elige.
    sesion = sesiones_usuario.resolver_token(db, token)
    if sesion is not None and pedido.insumista_id and pedido.insumista_id != sesion.usuario_id:
        raise HTTPException(
            status_code=403,
            detail="Este pedido lo aceptó otro insumista; no puedes marcarlo como entregado.",
        )

    pedido.estado = "Entregado_Insumista"
    pedido.fecha_entrega = datetime.now()
    pedido.cantidad_entregada = (
        datos.cantidad_entregada if datos.cantidad_entregada is not None else pedido.cantidad_solicitada
    )

    db.commit()

    notificaciones_pedido.notificar(
        db, notificaciones_pedido.ENTREGADO, pedido, background_tasks,
        descripcion="Insumo entregado, falta tu confirmación",
    )
    return {"status": "ok", "message": "Entrega registrada, esperando confirmación del operador", "mensaje": "Entrega registrada, esperando confirmación del operador"}

@router.put("/confirmar_recepcion")
def confirmar_recepcion(
    datos: schemas.ConfirmarRecepcionRequest,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
):
    """El operador confirma que recibió el insumo, cerrando definitivamente el pedido.

    Hasta la v1.7.0 tampoco avisaba a nadie, así que el pedido se quedaba colgado en la
    bandeja del insumista hasta su refresco de 15 s. El «salvavidas periódico» que hay
    en `BandejaInsumosActivity` existe exactamente por esto.
    """
    pedido = db.query(models.PedidoBodegaDB).filter(models.PedidoBodegaDB.id == datos.pedido_id).first()

    if not pedido:
        raise HTTPException(status_code=404, detail="Pedido no encontrado")
    if pedido.estado != "Entregado_Insumista":
        raise HTTPException(status_code=400, detail=f"El pedido no está pendiente de confirmación. Estado actual: {pedido.estado}")
    if pedido.session_id != datos.sesion_id:
        raise HTTPException(status_code=403, detail="Esta sesión no corresponde al pedido")

    pedido.estado = "Entregado"
    pedido.fecha_confirmacion = datetime.now()
    pedido.cantidad_recibida = (
        datos.cantidad_recibida if datos.cantidad_recibida is not None
        else (pedido.cantidad_entregada if pedido.cantidad_entregada is not None else pedido.cantidad_solicitada)
    )
    # Quién cerró el pedido. Por este endpoint entra siempre el operario; el supervisor
    # confirmando en su lugar llega por su propia ruta y se marca como SUPERVISOR.
    pedido.confirmado_por_tipo = "OPERARIO"

    db.commit()

    notificaciones_pedido.notificar(
        db, notificaciones_pedido.CONFIRMADO, pedido, background_tasks,
        descripcion="El operario confirmó la recepción",
    )
    return {"status": "ok", "message": "Recepción confirmada", "mensaje": "Recepción confirmada"}

@router.get("/pendientes_confirmacion/{sesion_id}")
def pedidos_pendientes_confirmacion(sesion_id: int, db: Session = Depends(get_db)):
    """Pedidos en estado Entregado_Insumista que el operador aún no ha confirmado."""
    try:
        pedidos = db.query(models.PedidoBodegaDB, models.SesionTrabajoDB)\
                    .join(models.SesionTrabajoDB, models.SesionTrabajoDB.id == models.PedidoBodegaDB.session_id)\
                    .filter(
                        models.PedidoBodegaDB.session_id == sesion_id,
                        models.PedidoBodegaDB.estado == "Entregado_Insumista"
                    )\
                    .order_by(models.PedidoBodegaDB.fecha_solicitud.asc()).all()

        return [
            {
                "id": p.id,
                "maquina": s.maquina,
                "operador": s.operador,
                "detalle": p.detalle_pedido,
                "cantidad": p.cantidad_solicitada,
                "cantidad_entregada": p.cantidad_entregada,
                "cantidad_recibida": p.cantidad_recibida,
                "hora": p.fecha_solicitud.strftime("%H:%M:%S"),
                "estado": p.estado,
            }
            for p, s in pedidos
        ]
    except Exception as e:
        logger.error(f"Error al obtener pendientes de confirmación: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.get("/asignados/{insumista_id}")
def obtener_pedidos_asignados(insumista_id: int, db: Session = Depends(get_db)):
    """Pedidos que un insumista tiene activos: en camino o esperando confirmación del operador."""
    try:
        pedidos = db.query(PedidoBodegaDB, SesionTrabajoDB)\
                    .join(SesionTrabajoDB, SesionTrabajoDB.id == PedidoBodegaDB.session_id)\
                    .filter(
                        PedidoBodegaDB.insumista_id == insumista_id,
                        PedidoBodegaDB.estado.in_(["En Camino", "Entregado_Insumista"])
                    )\
                    .order_by(PedidoBodegaDB.fecha_solicitud.asc()).all()

        return [
            {
                "id": p.id,
                "maquina": s.maquina,
                "operador": s.operador,
                "detalle": p.detalle_pedido,
                "cantidad": p.cantidad_solicitada,
                "cantidad_entregada": p.cantidad_entregada,
                "cantidad_recibida": p.cantidad_recibida,
                "hora": p.fecha_solicitud.strftime("%H:%M:%S"),
                "estado": p.estado,
            }
            for p, s in pedidos
        ]
    except Exception as e:
        logger.error(f"Error al obtener asignados: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.post("/entrega_proactiva", status_code=201)
def registrar_entrega_proactiva(
    insumista_id: int = Form(...),
    tipo_producto: str = Form(...),
    insumo: str = Form(...),
    cantidad: int = Form(...),
    # Opcional desde 2026-10-13: muchas veces el material se deja en bodega o en la
    # línea sin saber todavía qué máquina lo va a usar, o no lo usará ninguna. Vacío o
    # ausente = sin máquina. Una tablet 1.7.1 siempre lo manda y se valida como antes.
    maquina: str = Form(None),
    observaciones: str = Form(None),
    # v1.7.0, opcionales: una tablet anterior no los manda y la entrega se registra
    # igual, solo que sin quedar atada al catálogo.
    insumo_id: int = Form(None),
    request_id: str = Form(None),
    # Plan de produccion al que se destina el material. Es lo que permite dejar
    # insumos preparados para produccion que todavia no tiene maquina asignada.
    plan_id: int = Form(None),
    foto: UploadFile = File(...),
    db: Session = Depends(get_db),
    token: str = Depends(sesiones_usuario.token_de_cabecera),
):
    """Registra una entrega de insumo sin pedido previo (planeación).

    LA FOTO ES OBLIGATORIA. La app ya la exigía desde su propio formulario, pero el
    servidor la aceptaba vacía: cualquiera podía registrar por API una entrega sin
    evidencia, y una entrega proactiva sin foto no se puede verificar después — es la
    única prueba de dónde quedó el material.
    """
    if tipo_producto not in ("Sólido", "Líquido"):
        raise HTTPException(status_code=400, detail="tipo_producto debe ser 'Sólido' o 'Líquido'")

    # Idempotencia: la entrega lleva foto, así que un reintento por red lenta subiría
    # el archivo dos veces y dejaría dos registros de la misma entrega.
    if request_id:
        ya = db.query(EntregaProactivaDB).filter(
            EntregaProactivaDB.request_id == request_id
        ).first()
        if ya:
            logger.info(f"↩️ Entrega proactiva duplicada ignorada (request_id={request_id})")
            return {"id": ya.id, "mensaje": "Entrega ya registrada", "foto_url": ya.foto_path}

    # El insumista real sale del token cuando lo hay: `insumista_id` viene en el
    # formulario, o sea que lo elige el cliente, y con él se firman las entregas.
    sesion_usuario = sesiones_usuario.resolver_token(db, token)
    if sesion_usuario is not None:
        insumista_id = sesion_usuario.usuario_id

    if insumo_id is not None:
        catalogo = db.query(InsumoDB).filter(InsumoDB.id == insumo_id).first()
        if catalogo is None:
            raise HTTPException(status_code=404, detail="El insumo no existe en el catálogo")
        # El nombre se reescribe desde el catálogo: `insumo` es texto libre del
        # formulario y es lo que se lee en el dashboard y en el Excel.
        insumo = catalogo.nombre

    maquina = (maquina or "").strip() or None
    if maquina is not None:
        maquina_db = db.query(MaquinaDB).filter(
            MaquinaDB.nombre == maquina, MaquinaDB.activa.is_(True)
        ).first()
        if not maquina_db:
            raise HTTPException(status_code=404, detail=f"Máquina '{maquina}' no encontrada o inactiva")

    if not foto or not foto.filename:
        raise HTTPException(status_code=400, detail="La foto de evidencia es obligatoria")
    if foto.content_type not in ("image/jpeg", "image/png"):
        raise HTTPException(status_code=400, detail="La foto debe ser JPG o PNG")
    foto_content = foto.file.read()
    if not foto_content:
        raise HTTPException(status_code=400, detail="La foto llegó vacía")
    if len(foto_content) > 5 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="La foto no debe superar 5 MB")
    foto_ext = "jpg" if foto.content_type == "image/jpeg" else "png"

    try:
        nueva_entrega = EntregaProactivaDB(
            request_id=request_id,
            insumista_id=insumista_id,
            tipo_producto=tipo_producto,
            insumo=insumo,
            insumo_id=insumo_id,
            plan_id=plan_id,
            cantidad=cantidad,
            maquina=maquina,
            observaciones=observaciones,
        )
        db.add(nueva_entrega)
        db.commit()
        db.refresh(nueva_entrega)

        foto_url = None
        if foto_content is not None:
            timestamp = nueva_entrega.fecha_hora.strftime("%Y%m%d%H%M%S")
            filename = f"{nueva_entrega.id}_{insumista_id}_{timestamp}.{foto_ext}"
            with open(os.path.join(ENTREGAS_DIR, filename), "wb") as f:
                f.write(foto_content)
            nueva_entrega.foto_path = f"/static/entregas/{filename}"
            db.commit()
            foto_url = nueva_entrega.foto_path

        logger.info(f"📸 ENTREGA PROACTIVA: {cantidad} x {insumo} → {maquina or 'sin máquina'} (insumista {insumista_id})")
        return {"id": nueva_entrega.id, "mensaje": "Entrega registrada correctamente", "foto_url": foto_url}

    except HTTPException:
        raise
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /entrega_proactiva: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.get("/entregas_proactivas/{insumista_id}")
def obtener_entregas_proactivas(insumista_id: int, db: Session = Depends(get_db)):
    """Últimas 20 entregas proactivas registradas por un insumista."""
    try:
        entregas = db.query(EntregaProactivaDB)\
                     .filter(EntregaProactivaDB.insumista_id == insumista_id)\
                     .order_by(EntregaProactivaDB.fecha_hora.desc())\
                     .limit(20).all()
        return [
            {
                "id": e.id,
                "insumista_id": e.insumista_id,
                "tipo_producto": e.tipo_producto,
                "insumo": e.insumo,
                "cantidad": e.cantidad,
                "maquina": e.maquina,
                "observaciones": e.observaciones,
                "foto_path": e.foto_path,
                "fecha_hora": e.fecha_hora.strftime("%Y-%m-%d %H:%M:%S"),
            }
            for e in entregas
        ]
    except Exception as e:
        logger.error(f"Error en /entregas_proactivas/{insumista_id}: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


# ============================================================================
# DASHBOARD DE INSUMOS (vista web abierta de solo lectura)
# ============================================================================
# Agrega, para un rango de fechas: tiempos de reacción del flujo de pedidos
# (solicitud → aceptación → entrega → recepción), comparación de cantidades
# (solicitada / entregada / recibida) y las entregas proactivas con su evidencia
# fotográfica. Sin auth (igual que el dashboard de producción).

def _rango_insumos(desde, hasta):
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


def _delta_seg(a, b):
    """Segundos entre dos datetimes (b - a) o None si falta alguno o sale negativo."""
    if not a or not b:
        return None
    s = (b - a).total_seconds()
    return int(s) if s >= 0 else None


@router.get("/mis_entregas/{insumista_id}")
def mis_entregas(
    insumista_id: int,
    desde: str = Query(..., description="Inicio del turno, 'YYYY-MM-DD HH:MM:SS'."),
    db: Session = Depends(get_db),
    token: str = Depends(sesiones_usuario.token_de_cabecera),
):
    """Todo lo que el insumista entregó en su turno: entregas proactivas y pedidos.

    `/entregas_proactivas` devuelve las últimas 20 sin mirar la fecha y solo las
    proactivas, así que la pestaña «Mis entregas» mezclaba días y no contaba los
    pedidos atendidos. Aquí van las dos cosas desde `desde`, que la app manda con la
    hora en que el insumista inició sesión.

    Un pedido cuenta por su `fecha_entrega` (cuando el insumista lo dejó), no por la de
    solicitud: lo que importa es qué entregó en SU turno.
    """
    try:
        inicio = datetime.strptime(desde, "%Y-%m-%d %H:%M:%S")
    except ValueError:
        raise HTTPException(status_code=400, detail="desde debe ser 'YYYY-MM-DD HH:MM:SS'")

    # Como en el resto del módulo, el token manda sobre lo que el cliente dice ser.
    sesion_usuario = sesiones_usuario.resolver_token(db, token)
    if sesion_usuario is not None:
        insumista_id = sesion_usuario.usuario_id

    try:
        proactivas = db.query(EntregaProactivaDB).filter(
            EntregaProactivaDB.insumista_id == insumista_id,
            EntregaProactivaDB.fecha_hora >= inicio,
        ).order_by(EntregaProactivaDB.fecha_hora.desc()).all()

        pedidos = (
            db.query(PedidoBodegaDB, SesionTrabajoDB)
            .outerjoin(SesionTrabajoDB, SesionTrabajoDB.id == PedidoBodegaDB.session_id)
            .filter(
                PedidoBodegaDB.insumista_id == insumista_id,
                PedidoBodegaDB.fecha_entrega.isnot(None),
                PedidoBodegaDB.fecha_entrega >= inicio,
            )
            .order_by(PedidoBodegaDB.fecha_entrega.desc())
            .all()
        )

        def hora(d):
            return d.strftime("%Y-%m-%d %H:%M:%S") if d else None

        return {
            "desde": desde,
            "proactivas": [
                {
                    "id": e.id,
                    "insumo": e.insumo,
                    "cantidad": e.cantidad,
                    "maquina": e.maquina,
                    "plan_id": e.plan_id,
                    "observaciones": e.observaciones,
                    "foto_path": e.foto_path,
                    "estado": e.estado,
                    "fecha_hora": hora(e.fecha_hora),
                }
                for e in proactivas
            ],
            "pedidos": [
                {
                    "id": p.id,
                    "detalle": p.detalle_pedido,
                    "maquina": s.maquina if s else None,
                    "operador": s.operador if s else None,
                    "cantidad_solicitada": p.cantidad_solicitada,
                    "cantidad_entregada": p.cantidad_entregada,
                    "cantidad_recibida": p.cantidad_recibida,
                    "estado": p.estado,
                    "fecha_entrega": hora(p.fecha_entrega),
                    "fecha_confirmacion": hora(p.fecha_confirmacion),
                }
                for p, s in pedidos
            ],
        }
    except Exception as e:
        logger.error(f"Error en /mis_entregas/{insumista_id}: {e}")
        raise HTTPException(status_code=500, detail="Error interno")


@router.get("/dashboard")
def dashboard_insumos(desde: str = Query(None), hasta: str = Query(None), db: Session = Depends(get_db)):
    """Datos del dashboard de insumos: pedidos (tiempos + cantidades) y entregas proactivas."""
    try:
        d, h, inicio, fin = _rango_insumos(desde, hasta)

        # --- Pedidos del rango (por fecha de solicitud) + sesión + insumista ---
        filas = (
            db.query(PedidoBodegaDB, SesionTrabajoDB)
            .outerjoin(SesionTrabajoDB, SesionTrabajoDB.id == PedidoBodegaDB.session_id)
            .filter(PedidoBodegaDB.fecha_solicitud >= inicio, PedidoBodegaDB.fecha_solicitud < fin)
            .order_by(PedidoBodegaDB.fecha_solicitud.asc())
            .all()
        )
        insumista_ids = {p.insumista_id for p, _ in filas if p.insumista_id is not None}
        nombres = {}
        if insumista_ids:
            for u in db.query(UsuarioDB).filter(UsuarioDB.id.in_(insumista_ids)).all():
                nombres[u.id] = u.nombre

        def _hms(dt):
            return dt.strftime("%H:%M:%S") if dt else None

        pedidos = []
        suma_resp = 0
        n_resp = 0
        con_discrepancia = 0
        for p, s in filas:
            seg_total = _delta_seg(p.fecha_solicitud, p.fecha_confirmacion) \
                or _delta_seg(p.fecha_solicitud, p.fecha_entrega)
            seg_sol_ent = _delta_seg(p.fecha_solicitud, p.fecha_entrega)
            if seg_sol_ent is not None:
                suma_resp += seg_sol_ent
                n_resp += 1
            ent, rec, sol = p.cantidad_entregada, p.cantidad_recibida, p.cantidad_solicitada
            hay_disc = (
                (ent is not None and rec is not None and ent != rec)
                or (ent is not None and sol is not None and ent != sol)
            )
            if hay_disc:
                con_discrepancia += 1
            pedidos.append({
                "id": p.id,
                "maquina": s.maquina if s else None,
                "operador": s.operador if s else None,
                "insumo": p.detalle_pedido,
                "categoria": p.categoria,
                "insumista": nombres.get(p.insumista_id) if p.insumista_id else None,
                "estado": p.estado,
                "hora_solicitud": _hms(p.fecha_solicitud),
                "hora_aceptacion": _hms(p.fecha_aceptacion),
                "hora_entrega": _hms(p.fecha_entrega),
                "hora_confirmacion": _hms(p.fecha_confirmacion),
                "seg_aceptacion": _delta_seg(p.fecha_solicitud, p.fecha_aceptacion),
                "seg_entrega": _delta_seg(p.fecha_aceptacion, p.fecha_entrega),
                "seg_recepcion": _delta_seg(p.fecha_entrega, p.fecha_confirmacion),
                "seg_total": seg_total,
                "solicitada": sol,
                "entregada": ent,
                "recibida": rec,
            })

        # --- Entregas proactivas del rango (con evidencia fotográfica) ---
        entregas_q = (
            db.query(EntregaProactivaDB)
            .filter(EntregaProactivaDB.fecha_hora >= inicio, EntregaProactivaDB.fecha_hora < fin)
            .order_by(EntregaProactivaDB.fecha_hora.desc())
            .all()
        )
        ep_ids = {e.insumista_id for e in entregas_q if e.insumista_id is not None}
        ep_nombres = {}
        if ep_ids:
            for u in db.query(UsuarioDB).filter(UsuarioDB.id.in_(ep_ids)).all():
                ep_nombres[u.id] = u.nombre
        entregas = [
            {
                "id": e.id,
                "insumista": ep_nombres.get(e.insumista_id) if e.insumista_id else None,
                "tipo_producto": e.tipo_producto,
                "insumo": e.insumo,
                "cantidad": e.cantidad,
                "maquina": e.maquina,
                "observaciones": e.observaciones,
                "foto_url": e.foto_path,  # /static/entregas/...  (None si no hay)
                "fecha_hora": e.fecha_hora.strftime("%Y-%m-%d %H:%M:%S") if e.fecha_hora else None,
            }
            for e in entregas_q
        ]

        return {
            "rango": {"desde": d.isoformat(), "hasta": h.isoformat()},
            "kpis": {
                "total_pedidos": len(pedidos),
                "tiempo_resp_prom_seg": int(suma_resp / n_resp) if n_resp else None,
                "con_discrepancia": con_discrepancia,
                "entregas_proactivas": len(entregas),
            },
            "pedidos": pedidos,
            "entregas": entregas,
        }
    except Exception as e:
        logger.error(f"Error en /api/insumos/dashboard: {e}")
        raise HTTPException(status_code=500, detail="Error al generar dashboard de insumos")
