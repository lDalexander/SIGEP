from main import SessionLocal, UsuarioDB
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("semilla_usuarios")

def sembrar_usuarios():
    db = SessionLocal()
    try:
        # Verificamos si ya existen para no duplicar
        if db.query(UsuarioDB).count() > 0:
            logger.warning("⚠️ Ya existen usuarios. Script cancelado.")
            return

        logger.info("👷 Creando Insumistas de prueba...")

        usuarios = [
            UsuarioDB(nombre="Juan Perez", pin="1234", rol="Insumista Empaque"),
            UsuarioDB(nombre="Carlos Gomez", pin="5678", rol="Insumista Granel"),
            UsuarioDB(nombre="Jefe Planta", pin="9999", rol="SUPERVISOR_PLANTA"),
            UsuarioDB(nombre="Jefe Bodega", pin="8888", rol="SUPERVISOR_BODEGA")
        ]
        
        db.add_all(usuarios)
        db.commit()

        logger.info("✅ Usuarios creados con éxito.")
        logger.info("- Empaque: PIN 1234")
        logger.info("- Granel: PIN 5678")
        logger.info("- Supervisor Planta: PIN 9999")
        logger.info("- Supervisor Bodega: PIN 8888")

    except Exception as e:
        db.rollback()
        logger.error(f"❌ Error: {e}")
    finally:
        db.close()

if __name__ == "__main__":
    sembrar_usuarios()