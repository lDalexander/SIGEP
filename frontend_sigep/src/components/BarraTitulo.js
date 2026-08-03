import React from 'react';
import FiltroFecha from './FiltroFecha';
import { Label } from './ui';
import { turnoActual } from '../lib/format';

/**
 * Barra de título del dashboard: sobre-título mono, H1, el turno en curso a la
 * derecha, y debajo la fila de rango de fechas y descargas.
 */
export default function BarraTitulo({
  desde, hasta, horaDesde, horaHasta,
  onChange, onCargar, onLimpiarHoras, onDescargar,
}) {
  const turno = turnoActual();

  return (
    <div className="pt-7 pb-6 flex flex-wrap items-end justify-between gap-x-6 gap-y-4">
      <div>
        <Label className="block mb-2">Detcuador · Planta de llenado</Label>
        <h1 className="text-[34px] leading-none font-extrabold tracking-tight text-white">
          Producción en tiempo real
        </h1>
      </div>

      {/* El turno queda arriba y los controles a su altura del H1, como en las capturas. */}
      <div className="flex-1 min-w-[420px]">
        <p className="text-right mb-4">
          <span className="text-[13px] text-sig-muted">Turno actual&nbsp;&nbsp;</span>
          <span className="font-mono text-[13px] font-semibold tracking-label text-sig-amber">
            {turno.codigo} · {turno.franja}
          </span>
        </p>

        <div className="flex justify-end">
          <FiltroFecha
            desde={desde}
            hasta={hasta}
            horaDesde={horaDesde}
            horaHasta={horaHasta}
            onChange={onChange}
            onCargar={onCargar}
            onLimpiarHoras={onLimpiarHoras}
            onDescargar={onDescargar}
          />
        </div>
      </div>
    </div>
  );
}
