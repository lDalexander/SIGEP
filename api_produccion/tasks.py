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


async def programador_reporte_semanal():
    """Envía el reporte semanal de paros los viernes a las 12:00 (hora del servidor).

    No usa cron ni systemd (§«no reconfigurar»): vive en el proceso, como el garbage
    collector, así que se reprograma solo en cada arranque y no hay nada más que
    mantener.

    Dos problemas que resuelve la marca en BD (`config_correo.semanal_ultima_ventana`),
    y que un simple `sleep(7 días)` no resolvería:

    - **Reinicios.** El servicio se recarga a menudo (cada despliegue es un `HUP`). Un
      temporizador en memoria empezaría de cero cada vez y el viernes podría no llegar
      nunca. Aquí se comprueba el reloj cada 10 minutos contra la última ventana
      enviada.
    - **Envíos dobles.** Con la marca, dos comprobaciones seguidas —o dos workers si
      algún día se sube de `-w 1`— no pueden mandar dos veces la misma semana: se
      escribe la ventana cubierta, no «la fecha del último envío».

    Si el servidor estuvo apagado el viernes al mediodía, el reporte sale en cuanto
    vuelve: se compara contra la ventana, no contra la hora exacta. Llega tarde, que es
    mejor que no llegar.
    """
    from services import config_correo, reporte_semanal

    logger.info("📊 Iniciando programador del reporte semanal de paros...")
    while True:
        try:
            db = SessionLocal()
            try:
                cfg = config_correo.obtener_fila(db, crear=True)
                corte = reporte_semanal.ultimo_corte()
                ya_enviada = cfg.semanal_ultima_ventana
                if not cfg.semanal_activo:
                    pass  # desactivado desde /admin → Correo
                elif ya_enviada is not None and ya_enviada >= corte:
                    pass  # esta semana ya salió
                else:
                    enviado, _ = reporte_semanal.enviar(db, motivo="programado")
                    # La marca se escribe aunque el envío falle: si no, cada 10 minutos
                    # se reintentaría toda la semana y con un SMTP roto serían cientos
                    # de intentos. El fallo queda en el log y está el botón manual.
                    cfg.semanal_ultima_ventana = corte
                    cfg.semanal_ultimo_envio = datetime.now()
                    db.commit()
                    if not enviado:
                        logger.warning("📊 El reporte semanal no pudo enviarse; revisa la configuración de correo.")
            except Exception as db_err:
                db.rollback()
                logger.error(f"❌ Error en el programador del reporte semanal: {db_err}")
            finally:
                db.close()
        except Exception as e:
            logger.error(f"❌ Error crítico en el bucle del reporte semanal: {e}")

        await asyncio.sleep(600)  # 10 minutos
