from main import SessionLocal, InsumoDB, RecetaProductoDB
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("semilla")

def sembrar_datos():
    db = SessionLocal()
    try:
        # 1. VERIFICACIÓN: Evitar duplicados si ejecutas el script 2 veces
        if db.query(InsumoDB).count() > 0:
            logger.warning("⚠️ Ya existen insumos en la base de datos. Script cancelado para no duplicar.")
            return

        logger.info("📦 Iniciando inyección de catálogo de Insumos...")

        # --- FASE 1: CREAR EL CATÁLOGO EN BODEGA ---
        insumos_bodega = [
            InsumoDB(nombre="Lamina ULTREX 1KG", categoria="Rollo", unidad_medida="Kilogramo"),
            InsumoDB(nombre="Lamina TORBELLINO 1KG", categoria="Rollo", unidad_medida="Kilogramo"),
            InsumoDB(nombre="Saco PQP 25KG", categoria="Sacos", unidad_medida="Unidades"),
            InsumoDB(nombre="Caja Estándar (Detergentes)", categoria="Cartón", unidad_medida="Unidades"),
            InsumoDB(nombre="Cinta Adhesiva Transparente", categoria="Cintas", unidad_medida="Rollos"),
            InsumoDB(nombre="Etiqueta Código de Barras", categoria="Etiquetas", unidad_medida="Rollos")
        ]
        
        db.add_all(insumos_bodega)
        db.commit() # Guardamos para que MySQL les asigne un ID (1, 2, 3...)

        logger.info("✅ Catálogo de insumos creado. Vinculando Recetas (BOM)...")

        # Recuperamos los insumos recién creados para usar sus IDs
        f_ultrex = db.query(InsumoDB).filter_by(nombre="Funda Plástica ULTREX 1KG").first()
        f_torbel = db.query(InsumoDB).filter_by(nombre="Funda Plástica TORBELLINO 1KG").first()
        s_pqp = db.query(InsumoDB).filter_by(nombre="Saco Tejido PQP 25KG").first()
        caja = db.query(InsumoDB).filter_by(nombre="Caja Corrugada Estándar (Detergentes)").first()
        cinta = db.query(InsumoDB).filter_by(nombre="Cinta Adhesiva Transparente").first()
        etiqueta = db.query(InsumoDB).filter_by(nombre="Etiqueta Código de Barras").first()

        # --- FASE 2: CREAR LAS RECETAS (El "Candado" Lógico) ---
        recetas = [
            # RECETA 1: ULTREX 1KG (Usa su funda, más caja, cinta y etiqueta)
            RecetaProductoDB(marca="ULTREX", presentacion="1 KG", fragancia="Limón", insumo_id=f_ultrex.id),
            RecetaProductoDB(marca="ULTREX", presentacion="1 KG", fragancia="Limón", insumo_id=caja.id),
            RecetaProductoDB(marca="ULTREX", presentacion="1 KG", fragancia="Limón", insumo_id=cinta.id),
            RecetaProductoDB(marca="ULTREX", presentacion="1 KG", fragancia="Limón", insumo_id=etiqueta.id),

            # RECETA 2: TORBELLINO 1KG (Usa SU PROPIA funda, pero COMPARTE caja, cinta y etiqueta)
            RecetaProductoDB(marca="TORBELLINO", presentacion="1 KG", fragancia="Limón", insumo_id=f_torbel.id),
            RecetaProductoDB(marca="TORBELLINO", presentacion="1 KG", fragancia="Limón", insumo_id=caja.id),
            RecetaProductoDB(marca="TORBELLINO", presentacion="1 KG", fragancia="Limón", insumo_id=cinta.id),
            RecetaProductoDB(marca="TORBELLINO", presentacion="1 KG", fragancia="Limón", insumo_id=etiqueta.id),

            # RECETA 3: PQP 25KG (Es industrial, solo usa el saco y una etiqueta, NO usa caja ni cinta)
            RecetaProductoDB(marca="PQP", presentacion="25 KG", fragancia="Limón", insumo_id=s_pqp.id),
            RecetaProductoDB(marca="PQP", presentacion="25 KG", fragancia="Limón", insumo_id=etiqueta.id),
        ]

        db.add_all(recetas)
        db.commit()

        logger.info("🚀 ¡Inyección completada! Base de datos lista para pruebas de Insumistas.")

    except Exception as e:
        db.rollback()
        logger.error(f"❌ Error catastrófico inyectando datos: {e}")
    finally:
        db.close()

if __name__ == "__main__":
    sembrar_datos()

    