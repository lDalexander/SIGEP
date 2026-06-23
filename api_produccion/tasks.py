import asyncio
from datetime import datetime, timedelta
from database import SessionLocal, logger
from models import SesionTrabajoDB

async def garbage_collector_turnos():
    """
    Background task to automatically close 'SesionTrabajoDB' 
    records that have been left open for more than 13 hours (12h shift + 1h grace).
    """
    logger.info("♻️ Iniciando Garbage Collector de Turnos...")
    while True:
        try:
            db = SessionLocal()
            try:
                # Calculamos el límite: 13 horas atrás (12h de turno + 1h de gracia)
                limite_tiempo = datetime.now() - timedelta(hours=13)

                # Buscamos turnos abiertos (fin_turno is None) y cuyo inicio_turno es anterior al límite
                # Usamos with_for_update(skip_locked=True) para evitar colisiones si hay múltiples workers de Gunicorn ejecutando este mismo proceso.
                turnos_colgados = db.query(SesionTrabajoDB).filter(
                    SesionTrabajoDB.fin_turno.is_(None),
                    SesionTrabajoDB.inicio_turno < limite_tiempo
                ).with_for_update(skip_locked=True).all()

                if turnos_colgados:
                    ahora = datetime.now()
                    for turno in turnos_colgados:
                        turno.fin_turno = ahora
                        turno.duracion_minutos = (ahora - turno.inicio_turno).total_seconds() / 60.0
                        turno.observaciones = "CERRADO AUTOMATICAMENTE POR EL SISTEMA"
                        logger.warning(f"🔧 Turno {turno.id} cerrado automáticamente por inactividad.")
                    
                    db.commit()
                    logger.info(f"✅ Garbage Collector procesó {len(turnos_colgados)} turnos colgados.")
            except Exception as db_err:
                db.rollback()
                logger.error(f"❌ Error en la DB durante el Garbage Collector: {db_err}")
            finally:
                db.close()
                
        except Exception as e:
            logger.error(f"❌ Error crítico en el bucle del Garbage Collector: {e}")
            
        # Esperamos 1 hora (3600 segundos) antes de la siguiente revisión
        await asyncio.sleep(3600)
