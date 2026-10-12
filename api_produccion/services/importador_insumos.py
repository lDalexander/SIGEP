"""Importación del catálogo de insumos y del BOM desde CSV (v1.7.0).

El catálogo real se descarga del ERP, así que la carga tiene que ser repetible: se
vuelve a bajar el archivo, se vuelve a importar y la base queda al día. De ahí las dos
decisiones que mandan sobre todo lo demás:

  * POLÍTICA «AÑADIR Y ACTUALIZAR, BAJA LÓGICA DE LO AUSENTE». Nada se borra nunca.
    Lo que está en el CSV se crea o se actualiza; lo que ya no aparece se marca
    `activo = 0`. Borrar de verdad rompería el histórico: los pedidos existentes
    apuntan a esos nombres y un `DELETE` dejaría el histórico ilegible.

  * PASADA EN SECO POR DEFECTO. `aplicar=False` recorre el archivo entero, valida fila
    a fila y devuelve el mismo informe que devolvería al escribir, pero sin tocar nada.
    Un CSV del ERP con una columna corrida se detecta leyendo el informe, no a media
    escritura con la tabla ya a medio cambiar.

Comparación TOLERANTE para los catálogos (marca, presentación, fragancia, producto,
máquina, nombre de insumo): se ignoran mayúsculas, acentos y espacios de sobra, pero se
guarda SIEMPRE el valor canónico que ya tiene la base. Un export del ERP que diga
"LIMON" o "limón" encuentra la fragancia "LIMÓN" en vez de fallar, y la base no termina
con tres grafías de la misma cosa.
"""

import csv
import io
import unicodedata

from models import (
    FraganciaDB,
    InsumoDB,
    MaquinaDB,
    MarcaDB,
    PresentacionDB,
    ProductoDB,
    RecetaProductoDB,
)

BODEGAS = ("EMPAQUE", "GRANEL")

COLUMNAS_INSUMOS = ("nombre", "categoria", "unidad_medida", "bodega", "activo")
COLUMNAS_RECETAS = (
    "maquina", "producto", "marca", "presentacion", "fragancia", "insumo", "cantidad_sugerida",
)


# ──────────────────────────────────────────────────────────────────────────────
# Lectura del archivo
# ──────────────────────────────────────────────────────────────────────────────

def _texto_del_archivo(ruta):
    """Devuelve el contenido del CSV, resolviendo BOM y codificación.

    `utf-8-sig` se come el BOM que Excel escribe al guardar como «CSV UTF-8», que si
    no se cuela dentro del primer nombre de columna y la cabecera deja de reconocerse.
    El respaldo en `cp1252` cubre el otro «Guardar como» de Excel en Windows, que no
    avisa de que está escribiendo ANSI y rompe cualquier acento.
    """
    with open(ruta, "rb") as f:
        crudo = f.read()
    for codificacion in ("utf-8-sig", "cp1252"):
        try:
            return crudo.decode(codificacion)
        except UnicodeDecodeError:
            continue
    # Último recurso: no abortar la importación entera por un byte suelto.
    return crudo.decode("utf-8", errors="replace")


def _delimitador(primera_linea):
    """`;` o `,`. Excel en español escribe `;`, y un export del ERP puede traer `,`."""
    return ";" if primera_linea.count(";") >= primera_linea.count(",") else ","


def leer_csv(ruta, columnas_esperadas):
    """-> (filas, errores). Cada fila es {columna: texto} más `_linea` para el informe."""
    texto = _texto_del_archivo(ruta)
    lineas = texto.splitlines()
    if not lineas:
        return [], ["El archivo está vacío."]

    lector = csv.DictReader(io.StringIO(texto), delimiter=_delimitador(lineas[0]))
    cabecera = [(c or "").strip().lower() for c in (lector.fieldnames or [])]

    faltan = [c for c in columnas_esperadas if c not in cabecera]
    if faltan:
        return [], [
            "Faltan columnas en la cabecera: %s. Se esperaban: %s."
            % (", ".join(faltan), ", ".join(columnas_esperadas))
        ]

    filas = []
    # enumerate desde 2: la 1 es la cabecera, así el número coincide con lo que se ve
    # en Excel y el informe se puede seguir con el archivo abierto al lado.
    for numero, cruda in enumerate(lector, start=2):
        fila = {}
        for clave, valor in cruda.items():
            if clave is None:
                continue
            fila[clave.strip().lower()] = (valor or "").strip()
        if not any(fila.get(c) for c in columnas_esperadas):
            continue  # fila en blanco: Excel las deja al final al borrar contenido
        fila["_linea"] = numero
        filas.append(fila)
    return filas, []


# ──────────────────────────────────────────────────────────────────────────────
# Normalización y catálogos
# ──────────────────────────────────────────────────────────────────────────────

def normalizar(texto):
    """Clave de comparación: sin acentos, sin mayúsculas, sin espacios de sobra."""
    if texto is None:
        return ""
    sin_acentos = unicodedata.normalize("NFKD", str(texto))
    sin_acentos = "".join(c for c in sin_acentos if not unicodedata.combining(c))
    return " ".join(sin_acentos.split()).casefold()


def _indice(valores):
    """{clave normalizada: valor canónico de la base}."""
    return {normalizar(v): v for v in valores if v}


def _entero(texto, por_defecto=None):
    try:
        return int(str(texto).strip())
    except (TypeError, ValueError):
        return por_defecto


def _booleano(texto, por_defecto=True):
    valor = normalizar(texto)
    if valor in ("", None):
        return por_defecto
    if valor in ("1", "si", "s", "true", "verdadero", "x", "activo"):
        return True
    if valor in ("0", "no", "n", "false", "falso", "inactivo"):
        return False
    return None  # valor que no se entiende -> lo reporta quien llama


# ──────────────────────────────────────────────────────────────────────────────
# Informe
# ──────────────────────────────────────────────────────────────────────────────

class Resultado:
    """Lo que pasó (o pasaría) con una importación."""

    def __init__(self, titulo):
        self.titulo = titulo
        self.nuevos = []
        self.actualizados = []
        self.sin_cambios = 0
        self.dados_de_baja = []
        self.errores = []
        self.avisos = []

    @property
    def hay_errores(self):
        return bool(self.errores)

    def informe(self, aplicado):
        lineas = []
        lineas.append("")
        lineas.append("=" * 74)
        lineas.append("  %s  %s" % (self.titulo, "[APLICADO]" if aplicado else "[EN SECO — no se escribió nada]"))
        lineas.append("=" * 74)

        if self.errores:
            lineas.append("")
            lineas.append("ERRORES (%d) — no se escribe nada hasta corregirlos:" % len(self.errores))
            for e in self.errores:
                lineas.append("  ✗ %s" % e)

        if self.avisos:
            lineas.append("")
            lineas.append("AVISOS (%d):" % len(self.avisos))
            for a in self.avisos:
                lineas.append("  ! %s" % a)

        lineas.append("")
        lineas.append("RESUMEN")
        lineas.append("  nuevos .............. %d" % len(self.nuevos))
        lineas.append("  actualizados ........ %d" % len(self.actualizados))
        lineas.append("  sin cambios ......... %d" % self.sin_cambios)
        lineas.append("  dados de baja ....... %d   (activo=0; no se borra nada)" % len(self.dados_de_baja))

        for etiqueta, conjunto in (
            ("NUEVOS", self.nuevos),
            ("ACTUALIZADOS", self.actualizados),
            ("DADOS DE BAJA", self.dados_de_baja),
        ):
            if not conjunto:
                continue
            lineas.append("")
            lineas.append("%s:" % etiqueta)
            for item in conjunto[:40]:
                lineas.append("  · %s" % item)
            if len(conjunto) > 40:
                lineas.append("  … y %d más" % (len(conjunto) - 40))

        lineas.append("")
        return "\n".join(lineas)


# ──────────────────────────────────────────────────────────────────────────────
# Catálogo de insumos
# ──────────────────────────────────────────────────────────────────────────────

def importar_insumos(db, filas, aplicar=False):
    """Crea/actualiza insumos del CSV y da de baja los que ya no aparecen."""
    res = Resultado("CATÁLOGO DE INSUMOS  (insumos.csv)")

    existentes = db.query(InsumoDB).all()
    por_codigo = {i.codigo_erp: i for i in existentes if i.codigo_erp}
    por_nombre = {normalizar(i.nombre): i for i in existentes}

    vistos_nombre = {}
    vistos_codigo = {}
    tocados = set()

    for fila in filas:
        n = fila["_linea"]
        nombre = fila.get("nombre", "")
        codigo = fila.get("codigo_erp", "") or None
        categoria = fila.get("categoria", "") or None
        unidad = fila.get("unidad_medida", "")
        bodega = (fila.get("bodega", "") or "").strip().upper()
        activo = _booleano(fila.get("activo", ""), True)

        if not nombre:
            res.errores.append("línea %d: `nombre` vacío." % n)
            continue
        if len(nombre) > 150:
            res.errores.append("línea %d: `nombre` pasa de 150 caracteres." % n)
            continue
        if not unidad:
            res.errores.append("línea %d (%s): `unidad_medida` vacía." % (n, nombre))
            continue
        if bodega not in BODEGAS:
            res.errores.append(
                "línea %d (%s): `bodega` = %r; tiene que ser EMPAQUE o GRANEL. De esta "
                "columna depende a qué insumista le suena la alarma."
                % (n, nombre, fila.get("bodega", ""))
            )
            continue
        if activo is None:
            res.errores.append("línea %d (%s): `activo` = %r; use 1 o 0."
                               % (n, nombre, fila.get("activo", "")))
            continue

        clave = normalizar(nombre)
        if clave in vistos_nombre:
            res.errores.append("línea %d: `%s` ya venía en la línea %d del archivo."
                               % (n, nombre, vistos_nombre[clave]))
            continue
        vistos_nombre[clave] = n
        if codigo:
            if codigo in vistos_codigo:
                res.errores.append("línea %d: `codigo_erp` %s repetido (ya en la línea %d)."
                                   % (n, codigo, vistos_codigo[codigo]))
                continue
            vistos_codigo[codigo] = n

        # El código del ERP manda sobre el nombre: es lo que sobrevive a que en el ERP
        # le retoquen la descripción al material.
        actual = por_codigo.get(codigo) if codigo else None
        if actual is None:
            actual = por_nombre.get(clave)
        # Un código que apunta a una fila y un nombre que apunta a OTRA distinta es
        # ambiguo: aplicarlo fusionaría dos insumos y dejaría pedidos colgando.
        if actual is not None and codigo and por_nombre.get(clave) not in (None, actual):
            res.errores.append(
                "línea %d: el `codigo_erp` %s es del insumo «%s» pero el nombre «%s» ya "
                "es de otro insumo distinto. Resuélvalo en el archivo antes de importar."
                % (n, codigo, actual.nombre, nombre)
            )
            continue

        if actual is None:
            res.nuevos.append("%s  [%s · %s]" % (nombre, bodega, unidad))
            if aplicar:
                db.add(InsumoDB(
                    codigo_erp=codigo, nombre=nombre, categoria=categoria,
                    bodega=bodega, unidad_medida=unidad, activo=activo,
                ))
            continue

        tocados.add(actual.id)
        cambios = []
        for campo, valor in (
            ("codigo_erp", codigo), ("nombre", nombre), ("categoria", categoria),
            ("bodega", bodega), ("unidad_medida", unidad), ("activo", activo),
        ):
            # Un `codigo_erp` vacío en el CSV no borra el que ya está en la base: el
            # ERP puede exportar sin esa columna y no por eso se pierde la clave.
            if campo == "codigo_erp" and not valor:
                continue
            anterior = getattr(actual, campo)
            if campo == "activo":
                anterior = bool(anterior)
            if anterior != valor:
                cambios.append("%s: %r → %r" % (campo, anterior, valor))
                if aplicar:
                    setattr(actual, campo, valor)
        if cambios:
            res.actualizados.append("%s  (%s)" % (nombre, "; ".join(cambios)))
        else:
            res.sin_cambios += 1

    # Baja lógica de lo ausente. Solo de lo que estaba activo: volver a marcar algo que
    # ya estaba de baja no es un cambio y ensuciaría el informe cada vez.
    for insumo in existentes:
        if insumo.id in tocados or not insumo.activo:
            continue
        res.dados_de_baja.append("%s  (ya no viene en el archivo)" % insumo.nombre)
        if aplicar:
            insumo.activo = False

    if res.dados_de_baja:
        res.avisos.append(
            "%d insumo(s) quedan con activo=0 por no venir en el CSV. No se borra nada: "
            "los pedidos del histórico los siguen nombrando."
            % len(res.dados_de_baja)
        )
    return res


# ──────────────────────────────────────────────────────────────────────────────
# BOM (recetas)
# ──────────────────────────────────────────────────────────────────────────────

def _clave_receta(maquina, producto, marca, presentacion, fragancia, insumo_id):
    """Identidad de una receta. Los comodines (None) cuentan como un valor más.

    Hace falta en Python porque el índice único de MySQL NO atrapa dos comodines
    idénticos: un índice único deja repetir filas que llevan NULL en alguna columna.
    """
    return (
        normalizar(maquina), normalizar(producto), normalizar(marca),
        normalizar(presentacion), normalizar(fragancia), insumo_id,
    )


def importar_recetas(db, filas, aplicar=False):
    """Crea/actualiza el BOM del CSV y da de baja las recetas que ya no aparecen.

    Se ejecuta DESPUÉS de `importar_insumos` dentro de la misma transacción, para que
    una receta pueda apuntar a un insumo creado en la misma pasada.
    """
    res = Resultado("BOM / RECETAS  (recetas.csv)")

    db.flush()  # los insumos recién creados ya tienen id y se pueden referenciar

    insumos = {normalizar(i.nombre): i for i in db.query(InsumoDB).all()}
    maquinas = _indice(m.nombre for m in db.query(MaquinaDB).all())
    productos = _indice(p.nombre for p in db.query(ProductoDB).all())
    marcas = _indice(m.nombre for m in db.query(MarcaDB).all())
    presentaciones = _indice(p.nombre for p in db.query(PresentacionDB).all())
    fragancias = _indice(f.nombre for f in db.query(FraganciaDB).all())

    existentes = db.query(RecetaProductoDB).all()
    por_clave = {}
    for r in existentes:
        por_clave.setdefault(
            _clave_receta(r.maquina, r.producto, r.marca, r.presentacion, r.fragancia, r.insumo_id),
            r,
        )

    vistas = {}
    tocadas = set()

    for fila in filas:
        n = fila["_linea"]
        nombre_insumo = fila.get("insumo", "")
        if not nombre_insumo:
            res.errores.append("línea %d: `insumo` vacío. Es la única columna obligatoria." % n)
            continue

        insumo = insumos.get(normalizar(nombre_insumo))
        if insumo is None:
            res.errores.append(
                "línea %d: el insumo «%s» no está en el catálogo. Impórtelo primero en "
                "insumos.csv o corrija el nombre." % (n, nombre_insumo)
            )
            continue

        # Celda vacía = COMODÍN. Lo que sí viene tiene que existir en su catálogo: un
        # nombre mal escrito crearía una receta que no casa con ningún turno y el
        # operario se quedaría sin ver ese insumo, sin ningún error visible.
        valores = {}
        malo = False
        for columna, indice, etiqueta in (
            ("maquina", maquinas, "máquina"),
            ("producto", productos, "producto"),
            ("marca", marcas, "marca"),
            ("presentacion", presentaciones, "presentación"),
            ("fragancia", fragancias, "fragancia"),
        ):
            escrito = fila.get(columna, "")
            if not escrito:
                valores[columna] = None      # comodín
                continue
            canonico = indice.get(normalizar(escrito))
            if canonico is None:
                res.errores.append(
                    "línea %d: la %s «%s» no existe en el catálogo. Déjela vacía para "
                    "que valga para todas, o use uno de estos: %s."
                    % (n, etiqueta, escrito, ", ".join(sorted(indice.values())[:12]) or "(vacío)")
                )
                malo = True
                break
            valores[columna] = canonico
        if malo:
            continue

        cantidad = _entero(fila.get("cantidad_sugerida", ""), 1)
        if cantidad is None or cantidad < 1:
            res.errores.append("línea %d: `cantidad_sugerida` = %r; tiene que ser un entero >= 1."
                               % (n, fila.get("cantidad_sugerida", "")))
            continue

        clave = _clave_receta(
            valores["maquina"], valores["producto"], valores["marca"],
            valores["presentacion"], valores["fragancia"], insumo.id,
        )
        if clave in vistas:
            res.errores.append(
                "línea %d: esta misma combinación ya venía en la línea %d del archivo."
                % (n, vistas[clave])
            )
            continue
        vistas[clave] = n

        etiqueta = "%s ← %s" % (
            " / ".join(valores[c] or "*" for c in
                       ("maquina", "producto", "marca", "presentacion", "fragancia")),
            insumo.nombre,
        )

        actual = por_clave.get(clave)
        if actual is None:
            res.nuevos.append("%s  (x%d)" % (etiqueta, cantidad))
            if aplicar:
                db.add(RecetaProductoDB(
                    maquina=valores["maquina"], producto=valores["producto"],
                    marca=valores["marca"], presentacion=valores["presentacion"],
                    fragancia=valores["fragancia"], insumo_id=insumo.id,
                    cantidad_sugerida=cantidad, activo=True,
                ))
            continue

        tocadas.add(actual.id)
        cambios = []
        if actual.cantidad_sugerida != cantidad:
            cambios.append("cantidad_sugerida: %r → %r" % (actual.cantidad_sugerida, cantidad))
            if aplicar:
                actual.cantidad_sugerida = cantidad
        if not actual.activo:
            cambios.append("activo: False → True")
            if aplicar:
                actual.activo = True
        if cambios:
            res.actualizados.append("%s  (%s)" % (etiqueta, "; ".join(cambios)))
        else:
            res.sin_cambios += 1

    for receta in existentes:
        if receta.id in tocadas or not receta.activo:
            continue
        nombre = next((i.nombre for i in insumos.values() if i.id == receta.insumo_id), "insumo %s" % receta.insumo_id)
        res.dados_de_baja.append(
            "%s / %s / %s / %s / %s ← %s"
            % (receta.maquina or "*", receta.producto or "*", receta.marca or "*",
               receta.presentacion or "*", receta.fragancia or "*", nombre)
        )
        if aplicar:
            receta.activo = False

    if res.dados_de_baja:
        res.avisos.append(
            "%d receta(s) quedan con activo=0 por no venir en el CSV. El archivo tiene "
            "que traer el BOM COMPLETO, no solo lo que cambió."
            % len(res.dados_de_baja)
        )
    return res
