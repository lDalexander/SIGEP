import React from 'react';
import FiltroFecha from './FiltroFecha';
import { Label } from './ui';
import { turnoActual } from '../lib/format';

/**
 * Barra de título del dashboard: sobre-título mono, H1, el turno en curso a la
 * derecha, y debajo la fila de rango de fechas y descargas.
 */
export default function BarraTitulo({ desde, hasta, onChange, onCargar, onDescargar }) {
  const turno = turnoActual();

  return (
    <div className="pt-7 pb-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Label className="block mb-2">Detcuador · Planta de llenado</Label>
          <h1 className="text-[34px] leading-none font-extrabold tracking-tight text-white">
            Producción en tiempo real
          </h1>
        </div>

        <div className="text-right shrink-0">
          <span className="text-[13px] text-sig-muted">Turno actual&nbsp;&nbsp;</span>
          <span className="font-mono text-[13px] font-semibold tracking-label text-sig-amber">
            {turno.codigo} · {turno.franja}
          </span>
        </div>
      </div>

      <div className="mt-5">
        <FiltroFecha
          desde={desde}
          hasta={hasta}
          onChange={onChange}
          onCargar={onCargar}
          onDescargar={onDescargar}
        />
      </div>
    </div>
  );
}
