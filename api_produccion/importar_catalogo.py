#!/usr/bin/env python3
"""Carga el catálogo de insumos y el BOM desde los CSV de `plantillas/`.

El catálogo real se baja del ERP, así que esto se ejecuta cada vez que haya una
exportación nueva: lo que viene en el archivo se crea o se actualiza, y lo que ya no
aparece queda con `activo = 0`. Nada se borra.

    # 1) EN SECO — valida el archivo entero y no escribe nada
    cd ~/workspace/apps/sigep/api_produccion
    venv/bin/python importar_catalogo.py --insumos ../plantillas/insumos.csv \
                                         --recetas ../plantillas/recetas.csv

    # 2) Contra la base de pruebas, para ver el resultado sin arriesgar producción
    venv/bin/python importar_catalogo.py --insumos ... --recetas ... \
        --bd 'mysql+mysqlconnector://admin:3012@localhost/prueba_insumos_v17' --aplicar

    # 3) En producción, una vez que el informe en seco sale limpio
    venv/bin/python importar_catalogo.py --insumos ... --recetas ... --aplicar

La pasada en seco es el modo por DEFECTO a propósito: `--aplicar` hay que escribirlo.
Un CSV del ERP con una columna corrida se detecta leyendo el informe, no a mitad de
escritura con la tabla ya a medio cambiar.

Los dos archivos se procesan en UNA SOLA TRANSACCIÓN. Si las recetas fallan, los
insumos tampoco se guardan: un catálogo a medias con un BOM que apunta a lo que no
llegó a existir es peor que no haber importado nada. Y así una receta puede apuntar a
un insumo creado en la misma pasada.
"""

import argparse
import sys

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from services.importador_insumos import (
    COLUMNAS_INSUMOS,
    COLUMNAS_RECETAS,
    importar_insumos,
    importar_recetas,
    leer_csv,
)


def main():
    p = argparse.ArgumentParser(
        description="Importa el catálogo de insumos y el BOM desde CSV.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--insumos", metavar="CSV", help="ruta de insumos.csv")
    p.add_argument("--recetas", metavar="CSV", help="ruta de recetas.csv")
    p.add_argument("--aplicar", action="store_true",
                   help="escribe en la base. Sin esto, solo valida e informa.")
    p.add_argument("--bd", metavar="URL",
                   help="URL de SQLAlchemy. Por defecto, la DATABASE_URL del .env.")
    args = p.parse_args()

    if not args.insumos and not args.recetas:
        p.error("indique al menos --insumos o --recetas")

    # El import de `database` se hace aquí y no arriba porque abre el engine del .env
    # al importarse: con --bd apuntando a la copia de pruebas, no queremos ni tocar
    # la conexión de producción.
    if args.bd:
        engine = create_engine(args.bd, pool_pre_ping=True)
        Session = sessionmaker(autocommit=False, autoflush=False, bind=engine)
        destino = args.bd.rsplit("/", 1)[-1]
    else:
        from database import SQLALCHEMY_DATABASE_URL, SessionLocal
        Session = SessionLocal
        destino = SQLALCHEMY_DATABASE_URL.rsplit("/", 1)[-1]

    print("Base de datos : %s" % destino)
    print("Modo          : %s" % ("APLICAR (escribe)" if args.aplicar else "EN SECO (no escribe)"))

    # ── Lectura y validación de forma, antes de abrir la sesión ──────────────
    filas_insumos, filas_recetas = [], []
    errores_lectura = []
    if args.insumos:
        filas_insumos, errs = leer_csv(args.insumos, COLUMNAS_INSUMOS)
        errores_lectura += ["insumos.csv: %s" % e for e in errs]
        print("insumos.csv   : %d fila(s)" % len(filas_insumos))
    if args.recetas:
        filas_recetas, errs = leer_csv(args.recetas, COLUMNAS_RECETAS)
        errores_lectura += ["recetas.csv: %s" % e for e in errs]
        print("recetas.csv   : %d fila(s)" % len(filas_recetas))

    if errores_lectura:
        print("\nNo se pudo leer el archivo:")
        for e in errores_lectura:
            print("  ✗ %s" % e)
        return 2

    db = Session()
    try:
        resultados = []
        if args.insumos:
            resultados.append(importar_insumos(db, filas_insumos, aplicar=args.aplicar))
        if args.recetas:
            # Sin --insumos las recetas se validan contra lo que ya está en la base.
            resultados.append(importar_recetas(db, filas_recetas, aplicar=args.aplicar))

        hay_errores = any(r.hay_errores for r in resultados)
        for r in resultados:
            print(r.informe(aplicado=args.aplicar and not hay_errores))

        if hay_errores:
            db.rollback()
            print("=" * 74)
            print("  NO SE ESCRIBIÓ NADA: corrija los errores de arriba y vuelva a ejecutar.")
            print("=" * 74)
            return 1

        if args.aplicar:
            db.commit()
            print("=" * 74)
            print("  CAMBIOS GUARDADOS.")
            print("=" * 74)
        else:
            db.rollback()
            print("=" * 74)
            print("  PASADA EN SECO: no se escribió nada. Repita con --aplicar.")
            print("=" * 74)
        return 0

    except Exception as e:
        db.rollback()
        print("\nERROR INESPERADO, no se guardó nada: %s" % e)
        raise
    finally:
        db.close()


if __name__ == "__main__":
    sys.exit(main())
