"""Hash de contraseñas de administrador (2026-08-06).

Hasta ahora `administradores.password` guardaba la contraseña **en texto plano** y los
dos logins la comparaban con `==`. Al montar la gestión de usuarios desde la web eso
dejaba de ser una deuda tolerable: la pantalla nueva crea y resetea contraseñas, y
quedarían legibles para cualquiera con acceso a MySQL o a un backup.

Se usa PBKDF2-HMAC-SHA256 de `hashlib`, de la librería estándar: **no añade ninguna
dependencia** al proyecto (bcrypt/passlib habrían obligado a instalar y a recompilar).

Formato guardado, autodescriptivo y del mismo estilo que el de Django:

    pbkdf2_sha256$<iteraciones>$<salt_hex>$<hash_hex>

MIGRACIÓN PROGRESIVA, que es la clave de que esto no rompa nada: `verificar()` acepta
también una contraseña guardada en texto plano. Si coincide, devuelve `necesita_rehash`
y el login la reescribe hasheada en ese mismo momento. Así:

  - nadie se queda fuera el día del despliegue —ni la web, ni la app Android, que hace
    login contra la misma tabla en POST /api/admin/login—;
  - no hace falta ningún UPDATE masivo ni conocer las contraseñas actuales;
  - la tabla se va migrando sola conforme cada usuario entra.

Para los clientes es invisible: siguen mandando usuario y contraseña igual que siempre.
Lo que NO resuelve esto es que la contraseña viaja en claro por la red (la API es HTTP
sin TLS dentro de la planta); eso es harina de otro costal.
"""
import hashlib
import hmac
import secrets

ALGORITMO = "pbkdf2_sha256"
ITERACIONES = 260000          # ~0,1 s por verificación en este servidor
LONGITUD_SALT = 16


def hashear(password: str) -> str:
    """Devuelve la contraseña lista para guardar en `administradores.password`."""
    if not password:
        raise ValueError("La contraseña no puede estar vacía")
    salt = secrets.token_hex(LONGITUD_SALT)
    derivado = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt.encode("utf-8"), ITERACIONES
    ).hex()
    return f"{ALGORITMO}${ITERACIONES}${salt}${derivado}"


def es_hash(guardado: str) -> bool:
    """¿El valor almacenado ya está hasheado, o sigue siendo texto plano?"""
    return bool(guardado) and guardado.startswith(f"{ALGORITMO}$")


def verificar(password: str, guardado: str) -> tuple[bool, bool]:
    """Comprueba la contraseña contra lo que hay en la BD.

    Devuelve `(correcta, necesita_rehash)`. `necesita_rehash` es True cuando la
    contraseña era válida pero estaba guardada en texto plano (o con un número de
    iteraciones distinto del actual): quien llama debe reescribirla con `hashear()`.
    """
    if not password or not guardado:
        return False, False

    if not es_hash(guardado):
        # Texto plano heredado. `compare_digest` en vez de `==` para no filtrar por
        # el tiempo de comparación cuántos caracteres iniciales se acertaron.
        correcta = hmac.compare_digest(password, guardado)
        return correcta, correcta

    try:
        _, iteraciones, salt, esperado = guardado.split("$", 3)
        derivado = hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"), salt.encode("utf-8"), int(iteraciones)
        ).hex()
    except (ValueError, TypeError):
        # Un valor con el prefijo pero mal formado no puede validar nada.
        return False, False

    correcta = hmac.compare_digest(derivado, esperado)
    return correcta, correcta and int(iteraciones) != ITERACIONES
