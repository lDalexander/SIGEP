import React from 'react';

/* Pill mono en mayúsculas con fondo tintado al 12%.
   ACTIVO/ENTRADA verde · SALIDA ámbar · FINALIZADO/OFFLINE gris. */
const TONOS = {
  ok:    'bg-sig-ok/[0.12]    text-sig-ok    border-sig-ok/25',
  amber: 'bg-sig-amber/[0.12] text-sig-amber border-sig-amber/25',
  gray:  'bg-white/[0.05]     text-sig-muted border-sig-line',
};

/**
 * Props:
 *   tono  : 'ok' | 'amber' | 'gray'
 *   punto : muestra un punto de color a la izquierda
 */
export default function Badge({ tono = 'gray', punto = false, className = '', children }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 shrink-0 rounded-full border px-2 py-[3px]
                  font-mono text-[10px] uppercase tracking-label leading-none
                  ${TONOS[tono] || TONOS.gray} ${className}`}
    >
      {punto && <span className="w-1.5 h-1.5 rounded-full bg-current" />}
      {children}
    </span>
  );
}

/** Traduce el `estado` / `momento` que devuelve la API al tono correspondiente. */
export function tonoDeEstado(valor) {
  const v = String(valor || '').toUpperCase();
  if (v === 'ACTIVO' || v === 'ENTRADA' || v === 'ONLINE') return 'ok';
  if (v === 'SALIDA') return 'amber';
  return 'gray';
}
