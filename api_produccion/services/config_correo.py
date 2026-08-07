"""Configuración del correo saliente: fila única en BD, con el `.env` como respaldo.

Hasta el 2026-08-07 todo estaba en el `.env`, así que cambiar un destinatario exigía
entrar al servidor y recargar el servicio. Ahora vive en `config_correo` (una sola
fila, `id=1`) y se administra desde /admin → Correo.

**Regla de resolución, y es la clave del módulo:** para cada campo manda el valor de la
BD si está puesto, y si no, el del `.env`. Un campo vacío no es «sin destinatarios»: es
«no configurado aquí, usa el de siempre». Gracias a eso la tabla puede existir vacía y
el correo sigue comportándose exactamente igual que antes de esta pantalla.

Nunca lanza: si la BD no responde, se cae al `.env` y se registra. Un fallo consultando
la configuración no puede impedir que salga un aviso de planta.
"""
import os

from database import SessionLocal, logger
from models import ConfigCorreoDB

# Los tres tipos de correo que manda el sistema, con la variable de entorno que hacía
# de configuración antes de esta tabla. El orden es el que se muestra en la web.
TIPOS = {
    "semanal": ("SEMANAL_EMAIL_TO", "SEMANAL_EMAIL_CC"),
    "reportes": ("REPORTES_EMAIL_TO", "REPORTES_EMAIL_CC"),
    "pedidos": ("PEDIDOS_EMAIL_TO", "PEDIDOS_EMAIL_CC"),
}

# Valores por defecto del servidor, los mismos que tenía `email_service` cableados.
DEFECTOS_SMTP = {
    "smtp_host": "smtp-mail.outlook.com",
    "smtp_port": 587,
    "smtp_user": "no-reply@detcuador.com",
}


def lista(valor):
    """"a@x.com, b@y.com; c@z.com" -> ["a@x.com", "b@y.com", "c@z.com"].

    Acepta coma y punto y coma porque los dos orígenes —el `.env` escrito a mano y la
    web— se han visto con ambos separadores.
    """
    return [e.strip() for e in (valor or "").replace(";", ",").split(",") if e.strip()]


def texto(valores):
    """Inversa de `lista()`: la web manda un array y en la columna va texto."""
    if valores is None:
        return None
    if isinstance(valores, str):
        valores = lista(valores)
    return ", ".join(lista(", ".join(valores)))


def obtener_fila(db, crear=False):
    """La fila única de configuración. `crear=True` la inserta vacía si no existe."""
    fila = db.query(ConfigCorreoDB).filter(ConfigCorreoDB.id == 1).first()
    if fila is None and crear:
        fila = ConfigCorreoDB(id=1)
        db.add(fila)
        db.commit()
        db.refresh(fila)
    return fila


def _valor(fila, campo, defecto=None):
    """Valor de la BD si está puesto; si no, el del `.env`; si no, el defecto."""
    if fila is not None:
        v = getattr(fila, campo, None)
        if v not in (None, ""):
            return v
    env = os.getenv(campo.upper())
    if env not in (None, ""):
        return env
    return defecto


def efectiva(db=None):
    """Configuración ya resuelta (BD sobre `.env`), lista para usar al enviar.

    Si no se pasa `db` abre y cierra su propia sesión: `email_service` se ejecuta en
    BackgroundTasks, donde no hay ninguna sesión de petición viva.
    """
    propia = db is None
    if propia:
        db = SessionLocal()
    fila = None
    try:
        fila = obtener_fila(db)
    except Exception as e:  # la BD caída no puede impedir un envío
        logger.error(f"No se pudo leer config_correo, se usa el .env: {e}")
    finally:
        if propia:
            db.close()

    puerto = _valor(fila, "smtp_port", DEFECTOS_SMTP["smtp_port"])
    try:
        puerto = int(puerto)
    except (TypeError, ValueError):
        puerto = DEFECTOS_SMTP["smtp_port"]

    usuario = _valor(fila, "smtp_user", DEFECTOS_SMTP["smtp_user"])
    cfg = {
        "smtp_host": _valor(fila, "smtp_host", DEFECTOS_SMTP["smtp_host"]),
        "smtp_port": puerto,
        "smtp_user": usuario,
        "smtp_pass": _valor(fila, "smtp_pass", ""),
        # `From` cae al usuario autenticado, que es lo que hacía el módulo de correo.
        "smtp_from": _valor(fila, "smtp_from", usuario),
        "destinos": {},
        "semanal_activo": True if fila is None else bool(fila.semanal_activo),
    }
    for tipo, (env_to, env_cc) in TIPOS.items():
        en_bd_to = getattr(fila, f"{tipo}_to", None) if fila is not None else None
        en_bd_cc = getattr(fila, f"{tipo}_cc", None) if fila is not None else None
        cfg["destinos"][tipo] = {
            "to": lista(en_bd_to) if en_bd_to else lista(os.getenv(env_to)),
            "cc": lista(en_bd_cc) if en_bd_cc else lista(os.getenv(env_cc)),
            # De dónde salió cada lista: la web lo dice para que nadie edite el .env
            # creyendo que sigue mandando cuando ya lo pisa la BD.
            "origen_to": "bd" if en_bd_to else "env",
            "origen_cc": "bd" if en_bd_cc else "env",
        }

    # El reporte semanal no tenía variable de entorno previa (nace con la tabla): sin
    # nada configurado se manda a los mismos que los reportes de la app, que es quien
    # mantiene el sistema. Mejor eso que un reporte que no llega a nadie.
    if not cfg["destinos"]["semanal"]["to"]:
        cfg["destinos"]["semanal"]["to"] = list(cfg["destinos"]["reportes"]["to"])
        cfg["destinos"]["semanal"]["origen_to"] = "heredado"
    return cfg


def destinatarios(tipo, db=None):
    """(to, cc) de un tipo de correo. `tipo` ∈ TIPOS."""
    cfg = efectiva(db)
    destino = cfg["destinos"].get(tipo, {})
    return destino.get("to", []), destino.get("cc", [])
