"""
Router para los endpoints de autenticación y sesiones.
Maneja el login y el listado de usuarios activos.
"""
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session

from database import get_db, logger
from models import UsuarioDB, PedidoBodegaDB, DispositivoFCMDB
from schemas import LoginRequest, LogoutRequest
from sqlalchemy import func
import schemas
import models

router = APIRouter(prefix="/api", tags=["Autenticación y Estado"])

@router.get("/usuarios")
def obtener_usuarios(db: Session = Depends(get_db)):
    """Obtiene la lista de usuarios para los selectores/spinners"""
    usuarios = db.query(UsuarioDB).filter(UsuarioDB.activo == True).all()
    return [{"id": u.id, "nombre": u.nombre, "rol": u.rol} for u in usuarios]

@router.post("/login")
def iniciar_sesion(datos: LoginRequest, db: Session = Depends(get_db)):
    """Verifica el PIN de un usuario de bodega o supervisor"""
    usuario = db.query(UsuarioDB).filter(
        UsuarioDB.nombre == datos.nombre,
        UsuarioDB.pin == datos.pin,
        UsuarioDB.activo == True
    ).first()

    if not usuario:
        raise HTTPException(status_code=401, detail="Credenciales incorrectas")

    logger.info(f"🔑 ACCESO CONCEDIDO: {usuario.nombre} como {usuario.rol}")
    return {"id": usuario.id, "nombre": usuario.nombre, "rol": usuario.rol}

@router.post("/logout")
def cerrar_sesion(datos: LogoutRequest, db: Session = Depends(get_db)):
    """Cierra sesión en la tablet. Desvincula el token FCM del usuario para que
    deje de recibir notificaciones dirigidas a él. Es idempotente: si no hay nada
    que desvincular, responde igual con 200 (la app solo necesita el éxito)."""
    try:
        actualizados = db.query(DispositivoFCMDB).filter(
            DispositivoFCMDB.usuario_id == datos.usuario_id
        ).update({DispositivoFCMDB.usuario_id: None}, synchronize_session=False)
        db.commit()
        logger.info(f"🔒 Logout usuario {datos.usuario_id} (device {datos.device_id}); tokens desvinculados: {actualizados}")
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
    # NOTA DE SEGURIDAD: Por ahora es texto plano ('=='). 
    if not admin or admin.password != credenciales.pin:
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
