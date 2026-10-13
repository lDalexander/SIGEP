"""Resolución del BOM: qué insumos lleva una combinación de producción (v1.7.0).

Vive aparte porque lo preguntan DOS sitios que no se pueden permitir responder distinto:

  * `GET /api/sesion/{id}/insumos_permitidos` — lo que el operario puede pedir en su
    turno.
  * La planificación — lo que hay que tener preparado para un plan del día.

Si la planificación dijera que un plan necesita X y el operario de esa misma línea no
viera X en su lista, el insumista habría llevado material que nadie puede pedir. Por eso
la regla de los comodines se escribe una sola vez.

LA REGLA: `NULL` en una columna de la receta es un COMODÍN, vale para cualquier valor.
Es el mismo criterio de `maquina_productos.producto`. Sin comodines, con 7 productos ×
11 marcas × 36 presentaciones × 9 fragancias harían falta decenas de miles de filas.
"""

from sqlalchemy import or_

from models import InsumoDB, RecetaProductoDB

# Las cinco dimensiones, en el orden en que se leen. El orden importa solo para los
# mensajes: la consulta las cruza todas a la vez.
DIMENSIONES = ("maquina", "producto", "marca", "presentacion", "fragancia")


def _coincide(columna, valor):
    """Comodín o igualdad.

    Con `valor` vacío (el turno no tiene fragancia, por ejemplo) solo casan las recetas
    que también son comodín ahí: una receta que exige fragancia LIMÓN no puede aplicar a
    un turno que no lleva fragancia ninguna.
    """
    if not valor:
        return columna.is_(None)
    return or_(columna.is_(None), columna == valor)


def recetas_que_casan(db, maquina=None, producto=None, marca=None, presentacion=None, fragancia=None):
    """Recetas activas que aplican a esa combinación."""
    return (
        db.query(RecetaProductoDB)
        .filter(
            RecetaProductoDB.activo.is_(True),
            _coincide(RecetaProductoDB.maquina, maquina),
            _coincide(RecetaProductoDB.producto, producto),
            _coincide(RecetaProductoDB.marca, marca),
            _coincide(RecetaProductoDB.presentacion, presentacion),
            _coincide(RecetaProductoDB.fragancia, fragancia),
        )
        .all()
    )


def cantidades_sugeridas(db, **combinacion):
    """-> {insumo_id: cantidad}. Con varias recetas para el mismo insumo, manda la MAYOR.

    Pasa de verdad: una receta comodín para todo el detergente y otra específica de una
    máquina pueden apuntar al mismo insumo. Quedarse con la menor obligaría a pedir dos
    veces; quien pide siempre puede bajar el número en el propio diálogo.
    """
    sugerida = {}
    for r in recetas_que_casan(db, **combinacion):
        if r.insumo_id is None:
            continue
        sugerida[r.insumo_id] = max(sugerida.get(r.insumo_id, 0), r.cantidad_sugerida or 1)
    return sugerida


def insumos_de(db, **combinacion):
    """-> [(InsumoDB, cantidad_sugerida)] ordenado por nombre. Solo insumos activos."""
    sugerida = cantidades_sugeridas(db, **combinacion)
    if not sugerida:
        return []
    insumos = (
        db.query(InsumoDB)
        .filter(InsumoDB.id.in_(list(sugerida.keys())), InsumoDB.activo.is_(True))
        .all()
    )
    return sorted(
        ((i, sugerida.get(i.id, 1)) for i in insumos),
        key=lambda par: (par[0].nombre or "").lower(),
    )
