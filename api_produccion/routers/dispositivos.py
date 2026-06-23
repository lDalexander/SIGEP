"""
Router para gestión de dispositivos Android y notificaciones push FCM.
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from datetime import datetime

from database import get_db, logger
from models import DispositivoFCMDB
from schemas import RegistrarTokenRequest, RegistrarTokenResponse

router = APIRouter(prefix="/api/dispositivos", tags=["Dispositivos FCM"])


@router.post("/token", response_model=RegistrarTokenResponse)
def registrar_token(datos: RegistrarTokenRequest, db: Session = Depends(get_db)):
    """
    Registra o actualiza el token FCM de un dispositivo Android.
    Si el token ya existe (mismo dispositivo), actualiza sus datos en lugar de duplicarlo.
    """
    try:
        dispositivo = db.query(DispositivoFCMDB).filter(
            DispositivoFCMDB.token == datos.token
        ).first()

        if dispositivo:
            dispositivo.plataforma = datos.plataforma
            dispositivo.usuario_id = datos.usuario_id
            dispositivo.activo = True
            dispositivo.actualizado_en = datetime.now()
            db.commit()
            db.refresh(dispositivo)
            logger.info(f"📱 Token FCM actualizado: ID {dispositivo.id} — usuario_id={datos.usuario_id}")
            return {"mensaje": "Token actualizado correctamente", "id": dispositivo.id}

        nuevo = DispositivoFCMDB(
            token=datos.token,
            plataforma=datos.plataforma,
            usuario_id=datos.usuario_id,
        )
        db.add(nuevo)
        db.commit()
        db.refresh(nuevo)
        logger.info(f"📱 Token FCM registrado: ID {nuevo.id} — usuario_id={datos.usuario_id}")
        return {"mensaje": "Token registrado correctamente", "id": nuevo.id}

    except Exception as e:
        db.rollback()
        logger.error(f"Error al registrar token FCM: {e}")
        raise HTTPException(status_code=500, detail="Error interno al registrar el token")
