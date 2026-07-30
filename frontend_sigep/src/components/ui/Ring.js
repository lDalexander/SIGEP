import React from 'react';

/**
 * Anillo de progreso circular con la fracción dentro: `6/6` en verde cuando está
 * completo, `1/6` en ámbar cuando falta algo. Se usa en las tarjetas de checklist.
 */
export default function Ring({ valor, max, tamano = 44, grosor = 3.5, className = '' }) {
  const total = Number(max) || 0;
  const v = Number(valor) || 0;
  const completo = total > 0 && v >= total;
  const color = completo ? '#22C55E' : '#F5A623';

  const r = (tamano - grosor) / 2;
  const circunferencia = 2 * Math.PI * r;
  const fraccion = total > 0 ? Math.max(0, Math.min(1, v / total)) : 0;

  return (
    <div
      className={`relative shrink-0 ${className}`}
      style={{ width: tamano, height: tamano }}
      role="img"
      aria-label={`${v} de ${total} ítems marcados`}
    >
      <svg width={tamano} height={tamano} className="-rotate-90">
        <circle
          cx={tamano / 2} cy={tamano / 2} r={r}
          fill="none" stroke="rgba(255,255,255,0.08)" strokeWidth={grosor}
        />
        <circle
          cx={tamano / 2} cy={tamano / 2} r={r}
          fill="none" stroke={color} strokeWidth={grosor} strokeLinecap="round"
          strokeDasharray={circunferencia}
          strokeDashoffset={circunferencia * (1 - fraccion)}
          className="transition-[stroke-dashoffset] duration-500 ease-out"
        />
      </svg>
      <span
        className="absolute inset-0 flex items-center justify-center font-mono text-[11px] font-semibold tabular-nums"
        style={{ color }}
      >
        {v}/{total}
      </span>
    </div>
  );
}
