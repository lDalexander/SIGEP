"""
Servicio de correo (SMTP) para notificar pedidos de insumos por email.

Se dispara junto con la notificación FCM/WebSocket cuando una máquina solicita
insumos. Es tolerante a fallos: cualquier error de SMTP se registra y se ignora,
NUNCA interrumpe el flujo del pedido (se ejecuta en un BackgroundTask).

Desde el 2026-08-07 la configuración vive en la tabla `config_correo` y se administra
desde /admin → Correo; el `.env` sigue siendo el respaldo de cada campo que no esté
puesto ahí (ver `services/config_correo.py`). Por eso **se resuelve en cada envío** y
no al importar el módulo: un cambio en la web tiene efecto en el siguiente correo, sin
recargar el servicio.

Variables de entorno de respaldo (.env, fuera de control de versiones):
  SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS, SMTP_FROM,
  PEDIDOS_EMAIL_TO / _CC    (pedidos de insumos)
  REPORTES_EMAIL_TO / _CC   (reportes de problemas con la app)
  SEMANAL_EMAIL_TO / _CC    (reporte semanal de paros)
"""
import os
import smtplib
import ssl
from datetime import datetime
from email.message import EmailMessage
from html import escape

from dotenv import load_dotenv
from database import logger
from services import config_correo

load_dotenv()  # asegura que el .env esté cargado aunque este módulo se importe primero


def _enviar(asunto, cuerpo_txt, cuerpo_html, to=None, cc=None, tipo="pedidos", cfg=None):
    """Envía un correo por SMTP+STARTTLS. No lanza excepciones (las registra).

    `to`/`cc` en None usan los destinatarios configurados para `tipo`; una lista vacía
    explícita se respeta (permite enviar sin CC, p. ej. en pruebas). `cfg` permite
    pasar una configuración ya resuelta —la usa el botón de prueba de /admin, que
    prueba lo que hay en el formulario antes de guardarlo.
    """
    cfg = cfg or config_correo.efectiva()
    destino = cfg.get("destinos", {}).get(tipo, {})
    if to is None:
        to = destino.get("to", [])
    if cc is None:
        cc = destino.get("cc", [])

    if not cfg.get("smtp_pass"):
        logger.warning("📧 SMTP sin credenciales (contraseña vacía); no se envía correo.")
        return False
    if not to and not cc:
        logger.warning(f"📧 Sin destinatarios configurados para '{tipo}'; no se envía correo.")
        return False

    remitente = cfg.get("smtp_from") or cfg.get("smtp_user")
    msg = EmailMessage()
    msg["Subject"] = asunto
    msg["From"] = remitente
    msg["To"] = ", ".join(to)
    if cc:
        msg["Cc"] = ", ".join(cc)
    msg.set_content(cuerpo_txt)
    msg.add_alternative(cuerpo_html, subtype="html")

    destinatarios = to + cc
    try:
        ctx = ssl.create_default_context()
        with smtplib.SMTP(cfg["smtp_host"], cfg["smtp_port"], timeout=20) as s:
            s.ehlo()
            s.starttls(context=ctx)
            s.ehlo()
            s.login(cfg["smtp_user"], cfg["smtp_pass"])
            s.send_message(msg, from_addr=remitente, to_addrs=destinatarios)
        logger.info(f"📧 Correo enviado: '{asunto}' → {len(destinatarios)} destinatario(s)")
        return True
    except Exception as e:
        logger.error(f"❌ Error enviando correo '{asunto}': {e}")
        return False


def notificar_reporte_app(reporte_id, maquina, operador, texto, sesion_id=None, fecha=None):
    """Envía el correo de un reporte de problema con la app. Para BackgroundTasks.

    Se dispara desde `POST /api/reportes_app` **solo cuando la fila es nueva**: ese
    endpoint es idempotente por `request_id` y una tablet sin red reintenta el mismo
    reporte varias veces; enviar en el duplicado llenaría el buzón de copias del
    mismo incidente.

    El texto llega tal cual lo escribió el operario y puede traer cualquier carácter,
    así que en el HTML va escapado — un `<` suelto rompería la maquetación del correo.
    """
    fecha = fecha or datetime.now()
    fecha_txt = fecha.strftime("%Y-%m-%d %H:%M:%S")
    maquina = maquina or "—"
    operador = operador or "—"
    texto = (texto or "").strip() or "—"
    # Sin turno abierto el reporte llega con session_id NULL: se dice, no se inventa.
    sesion_txt = sesion_id if sesion_id else "sin turno"

    asunto = f"⚠️ Problema con la app — {maquina} ({operador})"

    cuerpo_txt = (
        "Reporte de problema con la aplicación (SIGEP)\n"
        "---------------------------------------------\n"
        f"Reporte #:    {reporte_id}\n"
        f"Máquina:      {maquina}\n"
        f"Operador:     {operador}\n"
        f"Turno:        {sesion_txt}\n"
        f"Fecha/Hora:   {fecha_txt}\n"
        "---------------------------------------------\n"
        f"{texto}\n"
        "---------------------------------------------\n"
        "Mensaje automático — no responder."
    )

    def fila(k, v):
        return (f'<tr><td style="padding:6px 14px;color:#5E7674;font:600 12px Arial">{k}</td>'
                f'<td style="padding:6px 14px;color:#1c2b29;font:700 14px Arial">{escape(str(v))}</td></tr>')

    cuerpo_html = f"""\
<div style="font-family:Arial,sans-serif;background:#f3f6f5;padding:22px">
  <div style="max-width:560px;margin:0 auto;background:#fff;border:1px solid #e2e8e6;border-radius:12px;overflow:hidden">
    <div style="background:#0D1A1C;padding:16px 20px">
      <span style="color:#F5A623;font-weight:800;letter-spacing:.04em;font-size:16px">SIGEP</span>
      <span style="color:#88A19E;font-size:11px;letter-spacing:.18em;text-transform:uppercase;margin-left:8px">Reporte de la app</span>
    </div>
    <div style="padding:18px 20px">
      <p style="margin:0 0 14px;color:#1c2b29;font-size:15px">
        <b>{escape(operador)}</b> reportó un problema desde <b>{escape(maquina)}</b>.
      </p>
      <div style="background:#fff8ec;border:1px solid #f3dcb0;border-radius:8px;padding:12px 14px;margin-bottom:14px">
        <p style="margin:0;color:#1c2b29;font-size:14px;line-height:1.5;white-space:pre-wrap">{escape(texto)}</p>
      </div>
      <table style="width:100%;border-collapse:collapse;background:#fafcfb;border:1px solid #eef2f1;border-radius:8px">
        {fila("Reporte #", reporte_id)}
        {fila("Máquina", maquina)}
        {fila("Operador", operador)}
        {fila("Turno", sesion_txt)}
        {fila("Fecha / Hora", fecha_txt)}
      </table>
      <p style="margin:16px 0 0;color:#5E7674;font-size:11px">Mensaje automático generado por SIGEP — no responder.</p>
    </div>
  </div>
</div>"""

    return _enviar(asunto, cuerpo_txt, cuerpo_html, tipo="reportes")


def notificar_pedido_insumo(maquina, operador, detalle, cantidad, categoria, pedido_id, fecha=None):
    """Envía el correo de un nuevo pedido de insumo. Pensado para BackgroundTasks."""
    fecha = fecha or datetime.now()
    fecha_txt = fecha.strftime("%Y-%m-%d %H:%M:%S")
    maquina = maquina or "—"
    operador = operador or "—"

    asunto = f"🧴 Pedido de insumo — {maquina}: {cantidad} x {detalle}"

    cuerpo_txt = (
        "Nuevo pedido de insumo desde planta (SIGEP)\n"
        "-------------------------------------------\n"
        f"Pedido #:     {pedido_id}\n"
        f"Máquina:      {maquina}\n"
        f"Operador:     {operador}\n"
        f"Insumo:       {detalle}\n"
        f"Cantidad:     {cantidad}\n"
        f"Categoría:    {categoria}\n"
        f"Fecha/Hora:   {fecha_txt}\n"
        "-------------------------------------------\n"
        "Mensaje automático — no responder."
    )

    def fila(k, v):
        return (f'<tr><td style="padding:6px 14px;color:#5E7674;font:600 12px Arial">{k}</td>'
                f'<td style="padding:6px 14px;color:#1c2b29;font:700 14px Arial">{v}</td></tr>')

    cuerpo_html = f"""\
<div style="font-family:Arial,sans-serif;background:#f3f6f5;padding:22px">
  <div style="max-width:560px;margin:0 auto;background:#fff;border:1px solid #e2e8e6;border-radius:12px;overflow:hidden">
    <div style="background:#0D1A1C;padding:16px 20px">
      <span style="color:#F5A623;font-weight:800;letter-spacing:.04em;font-size:16px">SIGEP</span>
      <span style="color:#88A19E;font-size:11px;letter-spacing:.18em;text-transform:uppercase;margin-left:8px">Pedido de insumo</span>
    </div>
    <div style="padding:18px 20px">
      <p style="margin:0 0 14px;color:#1c2b29;font-size:15px"><b>{maquina}</b> solicitó un insumo a bodega.</p>
      <table style="width:100%;border-collapse:collapse;background:#fafcfb;border:1px solid #eef2f1;border-radius:8px">
        {fila("Pedido #", pedido_id)}
        {fila("Máquina", maquina)}
        {fila("Operador", operador)}
        {fila("Insumo", detalle)}
        {fila("Cantidad", cantidad)}
        {fila("Categoría", categoria)}
        {fila("Fecha / Hora", fecha_txt)}
      </table>
      <p style="margin:16px 0 0;color:#5E7674;font-size:11px">Mensaje automático generado por SIGEP — no responder.</p>
    </div>
  </div>
</div>"""

    return _enviar(asunto, cuerpo_txt, cuerpo_html)
