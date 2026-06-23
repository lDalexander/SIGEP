"""
Helpers de turno compartidos por los routers.

Todos los cálculos de fecha/turno usan la zona horaria de la planta
(America/Guayaquil, UTC-5), NUNCA UTC. El servidor SIEMPRE recalcula el turno
con su propio reloj; jamás confía en la fecha/hora que envía la tablet.

Regla de turno:
  DIA   : 07:00 a 18:59 -> fecha_turno = día actual.
  NOCHE : 19:00 a 06:59 -> 00:00-06:59 pertenece a la noche anterior
          (fecha_turno = día anterior); 19:00-23:59 -> fecha_turno = día actual.
"""
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

ZONA_GYE = ZoneInfo("America/Guayaquil")


def ahora_gye() -> datetime:
    """Hora actual en America/Guayaquil (UTC-5)."""
    return datetime.now(ZONA_GYE)


def calcular_turno(ahora: datetime):
    """Devuelve (codigo_turno, fecha_turno) según la hora local de Guayaquil.

    DIA   : 07:00 a 18:59 -> fecha del día actual.
    NOCHE : 19:00 a 06:59 -> si es de madrugada (<=06) cuenta para el día anterior.
    """
    h = ahora.hour
    if 7 <= h <= 18:
        return "DIA", ahora.date()
    fecha = (ahora.date() - timedelta(days=1)) if h <= 6 else ahora.date()
    return "NOCHE", fecha
