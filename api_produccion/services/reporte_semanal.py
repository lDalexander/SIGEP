"""Reporte semanal de paros por correo (2026-08-07).

Ventana: **de viernes 12:00 a viernes 12:00**, siete días completos. Se envía el viernes
al mediodía y cubre exactamente lo ocurrido desde el mediodía del viernes anterior.

Un paro cuenta **entero si su inicio cae dentro de la ventana**, aunque termine después
del corte: el mismo criterio que `GET /dashboard/paros` y que la franja horaria del
dashboard.

**Pero el total NO coincide con el de `/paros`, y es a propósito:** aquí no se cuentan
las categorías de `CATEGORIAS_EXCLUIDAS` (el almuerzo), porque son paradas previstas de
la jornada y no incidencias sobre las que actuar. La vista de paros sí las muestra. El
correo lo dice en su pie para que la diferencia no se lea como un fallo.

El estado y la duración de cada paro salen de `routers.dashboard` (`_estado_paro` y
`_dur_segundos`) en vez de recalcularse aquí: son las que saben que un paro sin cerrar
con el turno ya cerrado se acota al fin del turno en lugar de crecer sin fin.
"""
from datetime import datetime, timedelta

from database import logger
from models import ParoMaquinaDB, SesionTrabajoDB
from routers.dashboard import _desglosar_motivo, _dur_segundos, _estado_paro

HORA_CORTE = 12  # mediodía
DIA_CORTE = 4    # viernes (lunes=0)

# Categorías que NO cuentan como tiempo perdido (2026-08-07, a petición del responsable).
# El almuerzo es una parada prevista de la jornada, no una incidencia, y sumarlo inflaba
# el total con algo sobre lo que no se puede actuar.
#
# La comparación se hace sobre la categoría ya normalizada por `_desglosar_motivo`, que
# devuelve mayúsculas: la tablet manda tanto "ALMUERZO" suelto como "[Almuerzo] - …".
# Comprobado sobre 120 días de datos: `ALMUERZO` es la única forma que aparece.
#
# **Esto separa el correo de la vista /paros**, que sí los cuenta. Es deliberado, y por
# eso el correo lo dice explícitamente: si no, los dos números se compararían y la
# diferencia parecería un error.
CATEGORIAS_EXCLUIDAS = {"ALMUERZO"}


def ultimo_corte(referencia=None):
    """El viernes 12:00 más reciente que no sea posterior a `referencia`."""
    referencia = referencia or datetime.now()
    dias = (referencia.weekday() - DIA_CORTE) % 7
    corte = (referencia - timedelta(days=dias)).replace(
        hour=HORA_CORTE, minute=0, second=0, microsecond=0
    )
    # Un viernes por la mañana el corte «de hoy» todavía no ha llegado: la semana
    # cerrada es la anterior.
    if corte > referencia:
        corte -= timedelta(days=7)
    return corte


def ventana(referencia=None):
    """(desde, hasta) de la última semana cerrada. Inicio inclusivo, fin exclusivo."""
    hasta = ultimo_corte(referencia)
    return hasta - timedelta(days=7), hasta


def _hhmm(segundos):
    """3900 -> "1h 05m". Sin datos, "0h 00m" (aquí el cero SÍ es un dato: no hubo paros)."""
    total = int(round(segundos or 0))
    return f"{total // 3600}h {(total % 3600) // 60:02d}m"


def _paros_de(db, desde, hasta):
    """(contados, excluidos) de la ventana, con su duración ya resuelta.

    Se devuelven los excluidos en vez de descartarlos sin más para poder decir en el
    correo cuánto se dejó fuera: un total que baja sin explicación se lee como un fallo.
    """
    filas = (
        db.query(ParoMaquinaDB)
        .filter(ParoMaquinaDB.inicio_paro >= desde, ParoMaquinaDB.inicio_paro < hasta)
        .all()
    )
    if not filas:
        return [], []
    ids = {p.session_id for p in filas if p.session_id}
    sesiones = {}
    if ids:
        sesiones = {
            s.id: s for s in db.query(SesionTrabajoDB).filter(SesionTrabajoDB.id.in_(ids)).all()
        }

    ahora = datetime.now()
    contados, excluidos = [], []
    for p in filas:
        sesion = sesiones.get(p.session_id)
        estado, fin_efectivo, estimada = _estado_paro(p, sesion, ahora)
        # Un paro todavía abierto corre contra el reloj: es lo correcto en /paros, que
        # es una vista EN VIVO, pero aquí metería tiempo POSTERIOR al corte. Se nota en
        # cuanto el reporte no se genera justo al mediodía del viernes — con el botón
        # «enviar ahora» un miércoles, o si el servidor estuvo caído y sale con retraso,
        # un paro sin cerrar sumaría también los días transcurridos desde entonces.
        # El reporte no puede hablar de lo que pasó después de su propio periodo.
        #
        # Que esto importa lo enseñó el paro 105 (2026-08-07): sin `fin_paro` desde
        # siempre, aportaba 13h 33m él solo —casi la mitad del total de la semana—
        # simplemente porque nadie lo cerró.
        if fin_efectivo and fin_efectivo > hasta:
            fin_efectivo = hasta
            estimada = True
        categoria, _ = _desglosar_motivo(p.motivo)
        registro = {
            "categoria": categoria,
            "maquina": (sesion.maquina if sesion is not None else None) or "—",
            "segundos": _dur_segundos(p, fin_efectivo) or 0,
            "estado": estado,
            "estimada": estimada,
        }
        (excluidos if categoria in CATEGORIAS_EXCLUIDAS else contados).append(registro)
    return contados, excluidos


def _ranking(paros, clave):
    """[(etiqueta, nº paros, segundos)] ordenado por tiempo parado, de mayor a menor.

    Por TIEMPO y no por número: dos horas de mantenimiento pesan más que cinco atascos
    de un minuto. Mismo criterio que la tarjeta «Paros por categoría» de /paros.
    """
    acumulado = {}
    for p in paros:
        etiqueta = p[clave] or "—"
        n, seg = acumulado.get(etiqueta, (0, 0.0))
        acumulado[etiqueta] = (n + 1, seg + p["segundos"])
    return sorted(
        [(etiqueta, n, seg) for etiqueta, (n, seg) in acumulado.items()],
        key=lambda x: x[2],
        reverse=True,
    )


def calcular(db, referencia=None):
    """Todos los números del reporte de la última semana cerrada."""
    desde, hasta = ventana(referencia)
    paros, excluidos = _paros_de(db, desde, hasta)
    # La semana anterior, para la comparación. Mismo criterio y misma exclusión: si el
    # almuerzo contara solo en una de las dos, la variación sería inventada.
    previos, _ = _paros_de(db, desde - timedelta(days=7), desde)

    total = sum(p["segundos"] for p in paros)
    total_previo = sum(p["segundos"] for p in previos)
    sin_cierre = sum(1 for p in paros if p["estado"] == "SIN CIERRE")
    en_curso = sum(1 for p in paros if p["estado"] == "EN CURSO")

    return {
        "desde": desde,
        "hasta": hasta,
        "total_segundos": total,
        "total_paros": len(paros),
        # None y no 0 cuando no hubo paros: un promedio de cero minutos sería falso.
        "promedio_segundos": (total / len(paros)) if paros else None,
        "por_categoria": _ranking(paros, "categoria"),
        "por_maquina": _ranking(paros, "maquina"),
        "sin_cierre": sin_cierre,
        "en_curso": en_curso,
        "previo_segundos": total_previo,
        "previo_paros": len(previos),
        # Lo que se dejó fuera, para poder decirlo: un total que baja sin explicación
        # se lee como un error del sistema.
        "excluidos_paros": len(excluidos),
        "excluidos_segundos": sum(p["segundos"] for p in excluidos),
        "excluidas": sorted(CATEGORIAS_EXCLUIDAS),
        # None si la semana anterior no tuvo paros: dividir daría un porcentaje infinito.
        "variacion_pct": (
            round((total - total_previo) * 100.0 / total_previo, 1) if total_previo else None
        ),
    }


def _fila_ranking(pos, etiqueta, n, segundos, total):
    from html import escape
    pct = (segundos * 100.0 / total) if total else 0
    return f"""\
<tr>
  <td style="padding:7px 12px;color:#5E7674;font:700 12px Arial;width:24px">{pos}</td>
  <td style="padding:7px 12px;color:#1c2b29;font:700 13px Arial">{escape(str(etiqueta))}</td>
  <td style="padding:7px 12px;color:#1c2b29;font:700 13px Arial;text-align:right;white-space:nowrap">{_hhmm(segundos)}</td>
  <td style="padding:7px 12px;color:#5E7674;font:600 12px Arial;text-align:right;white-space:nowrap">{pct:.0f}% · {n} paro(s)</td>
</tr>"""


def construir_correo(datos, limite=8):
    """(asunto, texto, html) a partir de lo que devuelve `calcular`."""
    desde_txt = datos["desde"].strftime("%d/%m/%Y %H:%M")
    hasta_txt = datos["hasta"].strftime("%d/%m/%Y %H:%M")
    total_txt = _hhmm(datos["total_segundos"])
    prom_txt = _hhmm(datos["promedio_segundos"]) if datos["promedio_segundos"] is not None else "—"

    # Lo excluido se dice siempre, incluso cuando es cero: quien compare este total con
    # la vista /paros —que sí cuenta los almuerzos— tiene que poder ver por qué difieren.
    excluidas_txt = ", ".join(datos.get("excluidas") or []) or "—"
    nota_excluidas = (
        f"No se cuentan los paros de {excluidas_txt}: son paradas previstas de la jornada, "
        "así que el total es menor que el de la vista de paros de SIGEP."
    )
    if datos.get("excluidos_paros"):
        excluido_txt = (f"Excluido: {_hhmm(datos['excluidos_segundos'])} de "
                        f"{excluidas_txt.lower()} ({datos['excluidos_paros']} paro(s))")
    else:
        excluido_txt = f"Excluido: sin paros de {excluidas_txt.lower()} en el periodo"

    variacion = datos["variacion_pct"]
    if variacion is None:
        comparacion = f"Semana anterior: {_hhmm(datos['previo_segundos'])} (sin paros para comparar)" \
            if not datos["previo_paros"] else f"Semana anterior: {_hhmm(datos['previo_segundos'])}"
        color_var, flecha = "#5E7674", ""
    else:
        # Más horas paradas es peor: el rojo va con la subida, no con el signo.
        color_var, flecha = ("#C0392B", "▲") if variacion > 0 else ("#1E8449", "▼")
        comparacion = (f"{flecha} {abs(variacion):.1f}% frente a la semana anterior "
                       f"({_hhmm(datos['previo_segundos'])})")

    asunto = f"📊 Paros de la semana — {total_txt} · {datos['total_paros']} paros ({desde_txt} → {hasta_txt})"

    lineas_cat = "\n".join(
        f"  {i}. {etiqueta}: {_hhmm(seg)} ({n} paro(s))"
        for i, (etiqueta, n, seg) in enumerate(datos["por_categoria"][:limite], 1)
    ) or "  (sin paros registrados)"
    lineas_maq = "\n".join(
        f"  {i}. {etiqueta}: {_hhmm(seg)} ({n} paro(s))"
        for i, (etiqueta, n, seg) in enumerate(datos["por_maquina"][:limite], 1)
    ) or "  (sin paros registrados)"

    cuerpo_txt = (
        "Reporte semanal de paros (SIGEP)\n"
        "================================\n"
        f"Periodo:      {desde_txt} → {hasta_txt}\n"
        f"Tiempo total: {total_txt}\n"
        f"Paros:        {datos['total_paros']}   ·   Duración media: {prom_txt}\n"
        f"{comparacion}\n"
        f"{excluido_txt}\n\n"
        "Categorías con más tiempo parado\n"
        "--------------------------------\n"
        f"{lineas_cat}\n\n"
        "Máquinas con más tiempo parado\n"
        "------------------------------\n"
        f"{lineas_maq}\n\n"
        "Un paro cuenta entero si empezó dentro del periodo, aunque terminara después.\n"
        f"{nota_excluidas}\n"
        "Mensaje automático — no responder."
    )

    total_seg = datos["total_segundos"]
    filas_cat = "".join(
        _fila_ranking(i, etiqueta, n, seg, total_seg)
        for i, (etiqueta, n, seg) in enumerate(datos["por_categoria"][:limite], 1)
    ) or '<tr><td colspan="4" style="padding:12px;color:#5E7674;font:600 13px Arial">Sin paros registrados en el periodo.</td></tr>'
    filas_maq = "".join(
        _fila_ranking(i, etiqueta, n, seg, total_seg)
        for i, (etiqueta, n, seg) in enumerate(datos["por_maquina"][:limite], 1)
    ) or '<tr><td colspan="4" style="padding:12px;color:#5E7674;font:600 13px Arial">Sin paros registrados en el periodo.</td></tr>'

    # Los paros sin cierre y los que seguían abiertos al calcular llevan duración
    # estimada: se dice en el correo en vez de dar la cifra como exacta.
    avisos = []
    if datos["sin_cierre"]:
        avisos.append(f"{datos['sin_cierre']} paro(s) quedaron sin cerrar; su duración se acotó al fin del turno.")
    if datos["en_curso"]:
        avisos.append(f"{datos['en_curso']} paro(s) seguían abiertos al generar el reporte.")
    aviso_html = ""
    if avisos:
        aviso_html = ('<p style="margin:14px 0 0;color:#8a6d3b;font:600 12px Arial;background:#fff8ec;'
                      'border:1px solid #f3dcb0;border-radius:8px;padding:10px 12px">'
                      + "<br>".join(avisos) + "</p>")

    def tabla(titulo, filas):
        return f"""\
      <p style="margin:20px 0 8px;color:#5E7674;font:700 11px Arial;letter-spacing:.14em;text-transform:uppercase">{titulo}</p>
      <table style="width:100%;border-collapse:collapse;background:#fafcfb;border:1px solid #eef2f1;border-radius:8px">{filas}</table>"""

    cuerpo_html = f"""\
<div style="font-family:Arial,sans-serif;background:#f3f6f5;padding:22px">
  <div style="max-width:640px;margin:0 auto;background:#fff;border:1px solid #e2e8e6;border-radius:12px;overflow:hidden">
    <div style="background:#0D1A1C;padding:16px 20px">
      <span style="color:#F5A623;font-weight:800;letter-spacing:.04em;font-size:16px">SIGEP</span>
      <span style="color:#88A19E;font-size:11px;letter-spacing:.18em;text-transform:uppercase;margin-left:8px">Paros de la semana</span>
    </div>
    <div style="padding:18px 20px">
      <p style="margin:0;color:#5E7674;font:600 12px Arial">{desde_txt} &nbsp;→&nbsp; {hasta_txt}</p>
      <p style="margin:10px 0 2px;color:#0D1A1C;font:800 34px Arial;letter-spacing:-.02em">{total_txt}</p>
      <p style="margin:0;color:#5E7674;font:600 13px Arial">
        {datos['total_paros']} paro(s) · media {prom_txt}
      </p>
      <p style="margin:8px 0 0;color:{color_var};font:700 13px Arial">{comparacion}</p>
      <p style="margin:6px 0 0;color:#5E7674;font:600 12px Arial">{excluido_txt}</p>
      {tabla("Categorías con más tiempo parado", filas_cat)}
      {tabla("Máquinas con más tiempo parado", filas_maq)}
      {aviso_html}
      <p style="margin:16px 0 0;color:#5E7674;font-size:11px">
        Un paro cuenta entero si empezó dentro del periodo, aunque terminara después.<br>
        {nota_excluidas}<br>
        Mensaje automático generado por SIGEP — no responder.
      </p>
    </div>
  </div>
</div>"""

    return asunto, cuerpo_txt, cuerpo_html


def enviar(db, referencia=None, motivo="programado"):
    """Calcula y envía el reporte. Devuelve (enviado, datos)."""
    from services.email_service import _enviar  # import tardío: evita el ciclo al importar

    datos = calcular(db, referencia)
    asunto, txt, html = construir_correo(datos)
    enviado = _enviar(asunto, txt, html, tipo="semanal")
    logger.info(
        f"📊 Reporte semanal ({motivo}) {datos['desde']:%Y-%m-%d %H:%M} → "
        f"{datos['hasta']:%Y-%m-%d %H:%M}: {_hhmm(datos['total_segundos'])} en "
        f"{datos['total_paros']} paros · enviado={enviado}"
    )
    return enviado, datos
