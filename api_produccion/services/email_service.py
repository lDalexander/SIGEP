"""
Servicio de correo (SMTP) para notificar pedidos de insumos por email.

Se dispara junto con la notificación FCM/WebSocket cuando una máquina solicita
insumos. Es tolerante a fallos: cualquier error de SMTP se registra y se ignora,
NUNCA interrumpe el flujo del pedido (se ejecuta en un BackgroundTask).

Configuración por variables de entorno (.env, fuera de control de versiones):
  SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS, SMTP_FROM,
  PEDIDOS_EMAIL_TO   (coma-separado)
  PEDIDOS_EMAIL_CC   (coma-separado)
"""
import os
import smtplib
import ssl
from datetime import datetime
from email.message import EmailMessage

from dotenv import load_dotenv
from database import logger

load_dotenv()  # asegura que el .env esté cargado aunque este módulo se importe primero


def _lista(valor):
    return [e.strip() for e in (valor or "").replace(";", ",").split(",") if e.strip()]


SMTP_HOST = os.getenv("SMTP_HOST", "smtp-mail.outlook.com")
SMTP_PORT = int(os.getenv("SMTP_PORT", "587"))
SMTP_USER = os.getenv("SMTP_USER", "no-reply@detcuador.com")
SMTP_PASS = os.getenv("SMTP_PASS", "")
SMTP_FROM = os.getenv("SMTP_FROM", SMTP_USER)
PEDIDOS_EMAIL_TO = _lista(os.getenv("PEDIDOS_EMAIL_TO"))
PEDIDOS_EMAIL_CC = _lista(os.getenv("PEDIDOS_EMAIL_CC"))


def _enviar(asunto, cuerpo_txt, cuerpo_html, to=None, cc=None):
    """Envía un correo por SMTP+STARTTLS. No lanza excepciones (las registra).

    `to`/`cc` en None usan los destinatarios por defecto; una lista vacía explícita
    se respeta (permite enviar sin CC, p. ej. en pruebas)."""
    if to is None:
        to = PEDIDOS_EMAIL_TO
    if cc is None:
        cc = PEDIDOS_EMAIL_CC
    if not SMTP_PASS:
        logger.warning("📧 SMTP sin credenciales (SMTP_PASS vacío); no se envía correo.")
        return False
    if not to and not cc:
        logger.warning("📧 Sin destinatarios configurados; no se envía correo.")
        return False

    msg = EmailMessage()
    msg["Subject"] = asunto
    msg["From"] = SMTP_FROM
    msg["To"] = ", ".join(to)
    if cc:
        msg["Cc"] = ", ".join(cc)
    msg.set_content(cuerpo_txt)
    msg.add_alternative(cuerpo_html, subtype="html")

    destinatarios = to + cc
    try:
        ctx = ssl.create_default_context()
        with smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=20) as s:
            s.ehlo()
            s.starttls(context=ctx)
            s.ehlo()
            s.login(SMTP_USER, SMTP_PASS)
            s.send_message(msg, from_addr=SMTP_FROM, to_addrs=destinatarios)
        logger.info(f"📧 Correo enviado: '{asunto}' → {len(destinatarios)} destinatario(s)")
        return True
    except Exception as e:
        logger.error(f"❌ Error enviando correo '{asunto}': {e}")
        return False


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
