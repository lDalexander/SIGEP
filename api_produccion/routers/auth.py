"""
Router para los endpoints de autenticación y sesiones.
Maneja el login y el listado de usuarios activos.
"""
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import JSONResponse, PlainTextResponse
from sqlalchemy.orm import Session

from database import get_db, logger
from models import UsuarioDB, PedidoBodegaDB, DispositivoFCMDB
from schemas import LoginRequest, LogoutRequest
from sqlalchemy import func
import schemas
import models
from services import seguridad, sesiones_usuario

router = APIRouter(prefix="/api", tags=["Autenticación y Estado"])

@router.get("/usuarios")
def obtener_usuarios(db: Session = Depends(get_db)):
    """Obtiene la lista de usuarios para los selectores/spinners"""
    usuarios = db.query(UsuarioDB).filter(UsuarioDB.activo == True).all()
    return [{"id": u.id, "nombre": u.nombre, "rol": u.rol} for u in usuarios]

@router.post("/login")
def iniciar_sesion(datos: LoginRequest, db: Session = Depends(get_db)):
    """Verifica el PIN y abre la sesión de turno del usuario en esta tablet.

    Desde la v1.7.0 el login es ÚNICO por usuario: `device_id` deja de ser un campo
    decorativo. Si el mismo insumista ya tiene sesión viva en otra tablet se responde
    **409**, que es justo lo que `LoginActivity` lleva esperando desde la 1.5 (muestra
    un diálogo «Sesión activa en otro dispositivo» con el texto que mandemos aquí). Sin
    esto, dos tablets con el mismo usuario podían aceptar y entregar el mismo pedido.

    El 409 va en TEXTO PLANO y no como el `{"detail": ...}` de FastAPI porque la app
    pinta el cuerpo del error tal cual en el diálogo: el JSON crudo se leería mal.

    Devuelve además `token`, la credencial de las siguientes llamadas. Es un campo
    nuevo en la respuesta y las tablets anteriores lo ignoran sin enterarse (Gson
    descarta los campos que su modelo no declara), así que no rompe nada en planta.
    """
    usuario = db.query(UsuarioDB).filter(
        UsuarioDB.nombre == datos.nombre,
        UsuarioDB.pin == datos.pin,
        UsuarioDB.activo == True
    ).first()

    if not usuario:
        raise HTTPException(status_code=401, detail="Credenciales incorrectas")

    conflicto = sesiones_usuario.conflicto_de_sesion(db, usuario, datos.device_id)
    if conflicto:
        # El rollback deshace los cierres por RELEVO/EXPIRACION que la comprobación
        # pudo dejar marcados: si no dejamos entrar, tampoco tocamos nada.
        db.rollback()
        logger.warning(f"🚫 LOGIN RECHAZADO (sesión en otra tablet): {usuario.nombre}")
        return PlainTextResponse(conflicto, status_code=409)

    sesion, token = sesiones_usuario.abrir(db, usuario, datos.device_id)
    db.commit()

    logger.info(
        f"🔑 ACCESO CONCEDIDO: {usuario.nombre} como {usuario.rol} "
        f"(tablet {sesion.device_id}, sesión {sesion.id})"
    )
    return {"id": usuario.id, "nombre": usuario.nombre, "rol": usuario.rol, "token": token}

@router.post("/logout")
def cerrar_sesion(datos: LogoutRequest, db: Session = Depends(get_db)):
    """Cierra sesión en la tablet. Desvincula el token FCM del usuario para que
    deje de recibir notificaciones dirigidas a él. Es idempotente: si no hay nada
    que desvincular, responde igual con 200 (la app solo necesita el éxito)."""
    try:
        actualizados = db.query(DispositivoFCMDB).filter(
            DispositivoFCMDB.usuario_id == datos.usuario_id
        ).update({DispositivoFCMDB.usuario_id: None}, synchronize_session=False)
        # Cerrar el turno libera el login único: sin esto el usuario no podría entrar
        # en otra tablet hasta que la sesión caducara sola a las 9 h.
        cerradas = sesiones_usuario.cerrar_por_logout(db, datos.usuario_id, datos.device_id)
        db.commit()
        logger.info(
            f"🔒 Logout usuario {datos.usuario_id} (device {datos.device_id}); "
            f"tokens desvinculados: {actualizados}; sesiones cerradas: {cerradas}"
        )
        return {"mensaje": "Sesión cerrada"}
    except Exception as e:
        db.rollback()
        logger.error(f"Error en /api/logout: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.get("/sesion/{sesion_id}/estado_pedidos")
def obtener_estado_pedidos_sesion(sesion_id: int, db: Session = Depends(get_db)):
    """Devuelve un conteo en vivo de los pedidos de un turno específico"""
    try:
        resultados = db.query(PedidoBodegaDB.estado, func.count(PedidoBodegaDB.id))\
                       .filter(PedidoBodegaDB.session_id == sesion_id)\
                       .group_by(PedidoBodegaDB.estado).all()
        
        conteo = {"Pendiente": 0, "En Camino": 0, "Entregado_Insumista": 0, "Entregado": 0}
        for estado, cantidad in resultados:
            if estado in conteo:
                conteo[estado] = cantidad

        return {
            "pendientes": conteo["Pendiente"],
            "en_camino": conteo["En Camino"],
            "pendiente_confirmacion": conteo["Entregado_Insumista"],
            "entregados": conteo["Entregado"]
        }
    except Exception as e:
        logger.error(f"Error al obtener estado de pedidos: {e}")
        raise HTTPException(status_code=500, detail="Error interno")

@router.post("/admin/login")
def login_administrador(credenciales: schemas.AdminLoginRequest, db: Session = Depends(get_db)):
    
    # 1. Buscamos al usuario en la base de datos (nombre -> username)
    admin = db.query(models.AdministradorDB).filter(
        models.AdministradorDB.username == credenciales.nombre,
        models.AdministradorDB.activo == True
    ).first()

    # 2. Validamos si existe y si la contraseña coincide (pin -> password)
    # Desde 2026-08-06 las contraseñas se guardan hasheadas (PBKDF2, ver
    # services/seguridad.py). `verificar` acepta también el texto plano heredado y
    # avisa de que hay que reescribirlo: así este login —el que usa la app Android—
    # sigue funcionando igual el día del despliegue, sin tocar la app.
    correcta, necesita_rehash = (False, False)
    if admin:
        correcta, necesita_rehash = seguridad.verificar(credenciales.pin, admin.password)
    if admin and correcta and necesita_rehash:
        admin.password = seguridad.hashear(credenciales.pin)
        db.commit()
        logger.info(f"Contraseña de {admin.username} migrada a hash en el login de la app")

    if not admin or not correcta:
        return JSONResponse(
            status_code=401,
            content={
                "status": "error",
                "mensaje": "PIN de administrador incorrecto o usuario no encontrado"
            }
        )

    # 3. Si todo está bien, damos luz verde
    return {
        "status": "success",
        "mensaje": "Login exitoso",
        "admin_id": admin.id,
        "nivel_acceso": admin.nivel_acceso
    }

@router.get("/admin/supervisores")
def obtener_admin_supervisores(db: Session = Depends(get_db)):
    """Obtiene la lista de supervisores (admins) para el Login de la App de Android"""
    roles_permitidos = ["SUPERADMIN", "ADMINBODEGA", "ADMINPLANTA", "ADMIN"]
    
    admins = db.query(models.AdministradorDB).filter(
        models.AdministradorDB.activo == True,
        models.AdministradorDB.nivel_acceso.in_(roles_permitidos)
    ).all()
    
    return [
        {"id": a.id, "nombre": a.username, "rol": a.nivel_acceso}
        for a in admins
    ]
