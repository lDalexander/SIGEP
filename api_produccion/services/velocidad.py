"""
Velocidad estándar de (máquina, presentación) — resolución compartida.

Lo usan dos routers que responden a preguntas distintas con el mismo dato:
`/api/dashboard/estado_operativo` (a qué ritmo va la línea AHORA) y `/api/admin/sesiones`
(a qué ritmo fue el turno). Vive aquí y no en uno de los dos para que no puedan
divergir: si el criterio de emparejamiento cambiara solo en un sitio, la misma sesión
saldría dentro de rango en una pantalla y fuera en la otra, y no habría forma de saber
cuál miente.

EL EMPAREJAMIENTO ES POR TEXTO, y no por clave: `sesiones_trabajo` guarda la máquina y la
presentación como el texto que eligió el operario en la tablet, no como un id. Es lo
mismo que ya hacen los filtros del dashboard y las recetas de insumos, y tiene una
consecuencia que conviene saber: si a una máquina se le cambia el nombre desde /admin,
las sesiones viejas dejan de encontrar su estándar (y las nuevas lo encuentran). Se
prefiere eso a inventar un emparejamiento aproximado: un rango equivocado es peor que
ningún rango, porque el de más se lee como un juicio sobre el turno.
"""
from models import MaquinaDB, MaquinaPresentacionVelocidadDB

# Claves que van SIEMPRE en la respuesta, con None cuando no hay estándar configurado.
# Que viajen siempre es lo que permite a la web distinguir «no hay estándar» de «no se
# consultó», igual que con las cuatro claves de velocidad del 2026-08-19.
SIN_ESTANDAR = {"velocidad_min": None, "velocidad_max": None}


def estandares_por_maquina_presentacion(db) -> dict:
    """{(nombre_maquina, presentacion): {"velocidad_min": x, "velocidad_max": y}}

    Solo los estándares ACTIVOS: uno dado de baja deja de juzgar los turnos, que es
    justo para lo que se le da de baja, pero la fila se conserva para poder reactivarla.

    Dos consultas para toda la pantalla, no una por sesión: el dashboard se repinta cada
    15 segundos con hasta 21 líneas.
    """
    nombres = {m.id: m.nombre for m in db.query(MaquinaDB.id, MaquinaDB.nombre).all()}
    filas = db.query(MaquinaPresentacionVelocidadDB).filter(
        MaquinaPresentacionVelocidadDB.activo.is_(True)
    ).all()
    salida = {}
    for f in filas:
        nombre = nombres.get(f.maquina_id)
        if not nombre:
            continue        # máquina borrada físicamente: sin nombre no hay con qué casar
        salida[(nombre, f.presentacion)] = {
            "velocidad_min": float(f.vel_min),
            "velocidad_max": float(f.vel_max),
        }
    return salida
