import React, { useEffect, useState } from 'react';
import { duracionSeg } from '../../lib/format';

/**
 * Duración que sigue corriendo, para los paros que están abiertos ahora mismo.
 *
 * Cuenta a partir de la duración que dio el backend más lo transcurrido desde que
 * llegó la respuesta, en vez de restar `inicio` al reloj del navegador: así un PC con
 * la hora mal puesta no inventa paros de tres horas ni de valores negativos.
 *
 * Props:
 *   segundos  : duración en el momento de la respuesta (`duracion_segundos`)
 *   recibidoEn: Date.now() de esa respuesta
 */
export default function Cronometro({ segundos, recibidoEn, className = '' }) {
  const [ahora, setAhora] = useState(() => Date.now());

  useEffect(() => {
    const id = setInterval(() => setAhora(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  // Sin base numérica no se estima nada: se muestra "—" como en el resto del panel.
  const base = Number(segundos);
  if (!Number.isFinite(base)) return <span className={className}>—</span>;

  const transcurrido = recibidoEn ? Math.max(0, (ahora - recibidoEn) / 1000) : 0;
  return (
    <span className={`tabular-nums ${className}`}>{duracionSeg(base + transcurrido)}</span>
  );
}
