import React from 'react';

/**
 * Barra ámbar proporcional sobre pista gris. Se usa en el ranking de estadísticas
 * (proporción respecto al máximo) y en «Top marcas».
 *
 * Props:
 *   valor / max : la barra ocupa valor/max del ancho
 *   pista       : dibuja la pista gris de fondo (en el ranking del dashboard la
 *                 pista no se ve, en «Top marcas» sí)
 */
export default function ProgressBar({ valor, max, pista = true, className = '' }) {
  const total = Number(max) || 0;
  const v = Number(valor) || 0;
  const ancho = total > 0 ? Math.max(0, Math.min(100, (v / total) * 100)) : 0;

  return (
    <div
      role="presentation"
      className={`h-[5px] w-full rounded-full overflow-hidden ${pista ? 'bg-white/[0.06]' : ''} ${className}`}
    >
      <div
        className="h-full rounded-full bg-sig-amber transition-[width] duration-500 ease-out"
        style={{ width: `${ancho}%` }}
      />
    </div>
  );
}
