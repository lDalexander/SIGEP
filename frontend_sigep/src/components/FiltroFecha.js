import React from 'react';
import { CalendarRange, X } from 'lucide-react';

/**
 * Filtro de rango de fechas (desde–hasta) para todo el dashboard.
 * Si ambos campos están vacíos, el backend usa el día de hoy (comportamiento en vivo).
 * Props:
 *   desde, hasta : strings 'YYYY-MM-DD' (o '')
 *   onChange     : (campo:'desde'|'hasta', valor:string) => void
 *   onReset      : () => void  -> vuelve a "hoy" (limpia el rango)
 */
export default function FiltroFecha({ desde, hasta, onChange, onReset }) {
  const activo = Boolean(desde || hasta);
  const inputCls =
    'bg-[#0d1424] border border-sigep-border rounded-lg px-3 py-1.5 text-[13px] text-slate-200 ' +
    'focus:border-sigep-neon/50 focus:outline-none [color-scheme:dark] transition-colors';

  return (
    <div className="flex flex-wrap items-center gap-3 mb-6 animate-fade-in">
      <div className="flex items-center gap-2 text-slate-300 text-[13px] font-medium">
        <CalendarRange size={16} className="text-sigep-neon" />
        Rango de fechas
      </div>

      <div className="flex items-center gap-2">
        <input
          type="date"
          value={desde}
          max={hasta || undefined}
          onChange={(e) => onChange('desde', e.target.value)}
          className={inputCls}
          aria-label="Fecha desde"
        />
        <span className="text-slate-500 text-xs">a</span>
        <input
          type="date"
          value={hasta}
          min={desde || undefined}
          onChange={(e) => onChange('hasta', e.target.value)}
          className={inputCls}
          aria-label="Fecha hasta"
        />
      </div>

      {activo ? (
        <button
          type="button"
          onClick={onReset}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[12px] font-medium text-slate-400 bg-[#162032] border border-sigep-border hover:text-white hover:border-sigep-border2 transition-colors"
        >
          <X size={12} />
          Hoy
        </button>
      ) : (
        <span className="text-[11px] text-slate-500">Mostrando hoy (en vivo)</span>
      )}
    </div>
  );
}
