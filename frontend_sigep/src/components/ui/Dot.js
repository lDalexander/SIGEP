import React from 'react';

/* Punto de estado. Relleno verde = activo/en línea; hueco gris = finalizado;
   rojo = tablet fuera de línea. */
const TONOS = {
  ok:   'bg-sig-ok',
  off:  'bg-white/20',
  bad:  'bg-red-500',
};

export default function Dot({ tono = 'ok', pulso = false, className = '' }) {
  return (
    <span
      aria-hidden="true"
      className={`inline-block w-2 h-2 shrink-0 rounded-full ${TONOS[tono] || TONOS.off}
                  ${pulso ? 'animate-pulse-dot' : ''} ${className}`}
    />
  );
}
