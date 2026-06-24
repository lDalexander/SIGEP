"""
Seed idempotente de la jerarquía máquina → marca → presentación (maquina_productos).

Datos iniciales tomados de los mapas hardcodeados de la app Android (MainActivity.kt):
`marcasPorMaquinaLocal` + `presentacionesPorMaquinaMarcaLocal`. Esta es la matriz
de arranque; el administrador la ajusta luego desde el editor web.

Idempotente: no duplica combinaciones existentes (respeta el UNIQUE). Volver a
correrlo solo agrega lo que falte. NO desactiva ni borra lo que el admin haya
editado a mano.

Uso (en el servidor, dentro de api_produccion):
    python seed_maquina_productos.py
"""
from database import SessionLocal
from models import MaquinaDB, MaquinaProductoDB

# Máquina -> { marca -> [presentaciones] }.  (la fragancia es universal, no va aquí)
MATRIZ = {
    "Máquina 7": {
        "ULTREX": ["1 KG", "2 KG", "3 KG"],
        "TORBELLINO": ["1 KG"],
        "COMISARIATO": ["1.2 KG"],
    },
    "Máquina 7B": {
        "PQP": ["15 KG", "25 KG"],
        "HIT": ["5 KG"],
        "ULTREX": ["5 KG"],
        "COMISARIATO": ["5 KG"],
    },
    "Máquina 8": {
        "ULTREX": ["100 GR", "250 GR", "500 GR"],
        "HIT": ["360 GR"],
    },
    "Máquina 9": {
        "TORBELLINO": ["1 KG"],
        "ULTREX": ["1 KG"],
        "COMISARIATO": ["1.2 KG"],
    },
    "Máquina 16": {
        "ULTREX": ["1 KG", "2 KG", "3 KG"],
        "TORBELLINO": ["1 KG"],
        "COMISARIATO": ["1.2 KG"],
    },
}


def main():
    db = SessionLocal()
    creadas = 0
    omitidas = 0
    sin_maquina = []
    try:
        for nombre_maquina, marcas in MATRIZ.items():
            maquina = db.query(MaquinaDB).filter(MaquinaDB.nombre == nombre_maquina).first()
            if not maquina:
                sin_maquina.append(nombre_maquina)
                continue
            for marca, presentaciones in marcas.items():
                for presentacion in presentaciones:
                    existe = db.query(MaquinaProductoDB).filter(
                        MaquinaProductoDB.maquina_id == maquina.id,
                        MaquinaProductoDB.marca == marca,
                        MaquinaProductoDB.presentacion == presentacion,
                    ).first()
                    if existe:
                        omitidas += 1
                        continue
                    db.add(MaquinaProductoDB(
                        maquina_id=maquina.id,
                        marca=marca,
                        presentacion=presentacion,
                        activo=True,
                    ))
                    creadas += 1
        db.commit()
        print(f"Seed completado: {creadas} combinaciones creadas, {omitidas} ya existían.")
        if sin_maquina:
            print(f"AVISO: máquinas no encontradas en BD (no sembradas): {sin_maquina}")
    except Exception as e:
        db.rollback()
        print(f"ERROR en seed: {e}")
        raise
    finally:
        db.close()


if __name__ == "__main__":
    main()
