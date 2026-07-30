import React from 'react';
import { Button, Label } from '../ui';

/**
 * Fila «desde → hasta + Cargar + contador» que abre las pestañas de Producción y
 * Checklists. Igual que en el dashboard, el rango solo se aplica al pulsar Cargar.
 */
export default function FiltroRango({ desde, hasta, onChange, onCargar, contador }) {
  return (
    <div className="flex flex-wrap items-center gap-3 mb-5">
      <input
        type="date"
        value={desde}
        max={hasta || undefined}
        onChange={(e) => onChange('desde', e.target.value)}
        aria-label="Fecha desde"
        className="sig-input font-mono text-[13px]"
      />
      <span aria-hidden="true" className="text-sig-dim">→</span>
      <input
        type="date"
        value={hasta}
        min={desde || undefined}
        onChange={(e) => onChange('hasta', e.target.value)}
        aria-label="Fecha hasta"
        className="sig-input font-mono text-[13px]"
      />
      <Button onClick={onCargar}>Cargar</Button>
      {contador && <Label caja="normal">{contador}</Label>}
    </div>
  );
}
