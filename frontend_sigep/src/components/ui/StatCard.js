import React from 'react';
import Label from './Label';

/* Las tarjetas KPI llevan una línea de acento de 2px en el borde superior:
   verde en las dos primeras, ámbar en la tercera. */
const ACENTOS = {
  ok:    'bg-sig-ok',
  amber: 'bg-sig-amber',
};

/**
 * Props:
 *   etiqueta : rótulo mono superior («PRODUCCIÓN DE HOY»)
 *   acento   : 'ok' | 'amber'
 *   pie      : nodo opcional al fondo de la tarjeta
 */
export default function StatCard({ etiqueta, acento = 'ok', pie, className = '', children }) {
  return (
    <section className={`sig-card relative animate-fade-in flex flex-col ${className}`}>
      <span
        aria-hidden="true"
        className={`absolute inset-x-0 top-0 h-[2px] rounded-t-xl ${ACENTOS[acento] || ACENTOS.ok}`}
      />
      <div className="px-5 pt-5 pb-4 flex-1">
        <Label className="block mb-3">{etiqueta}</Label>
        {children}
      </div>
      {pie && <div className="px-5 pb-4">{pie}</div>}
    </section>
  );
}

/**
 * Cifra grande + su unidad debajo, la unidad mínima de las tarjetas KPI.
 * `1.873` sobre `PACAS`.
 */
export function Cifra({ valor, unidad, className = '' }) {
  return (
    <div className={className}>
      <p className="text-[42px] leading-none font-extrabold tracking-tight text-white tabular-nums">
        {valor}
      </p>
      {unidad && <Label className="block mt-2">{unidad}</Label>}
    </div>
  );
}
