import React from 'react';
import { Card, Dot, Estado } from './ui';

/**
 * «Actividad en vivo · últimos eventos» — lista con scroll propio, orden descendente
 * (el endpoint ya los devuelve del más reciente al más antiguo).
 *
 * Props:
 *   logs : respuesta de /dashboard/logs
 */
export default function TerminalLog({ logs = [], cargando, error }) {
  return (
    <Card titulo="Actividad en vivo" meta="últimos eventos" sinPad>
      {logs.length === 0 ? (
        <Estado cargando={cargando} error={error} vacio="Sin eventos registrados" />
      ) : (
        <ul className="max-h-[300px] overflow-y-auto px-5 pb-4 divide-y divide-sig-line">
          {logs.map((log, idx) => (
            <li key={`${log.hora}-${idx}`} className="flex items-start gap-3 py-2.5">
              <Dot tono="ok" className="mt-1.5" />
              <span className="shrink-0 font-mono text-[11px] tabular-nums tracking-label text-sig-dim mt-px">
                {log.hora}
              </span>
              <span className="text-[12px] leading-relaxed text-sig-muted">{log.mensaje}</span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
