import React from 'react';
import { Card, Label, ProgressBar, Estado } from '../ui';
import { duracionSeg, num, pct } from '../../lib/format';

/**
 * «Paros por categoría» — ranking por tiempo parado, no por número de paros: un
 * cambio de teflón de dos horas pesa más que cinco atascos de un minuto.
 *
 * Props:
 *   datos   : `por_categoria` de /dashboard/paros (ya viene ordenado por segundos)
 *   periodo : texto del metadato
 */
export default function ParosPorCategoria({ datos = [], periodo = 'hoy', cargando, error }) {
  const total = datos.reduce((suma, d) => suma + (Number(d.segundos) || 0), 0);
  const maximo = datos.reduce((mx, d) => Math.max(mx, Number(d.segundos) || 0), 0);

  return (
    <Card
      titulo="Paros por categoría"
      meta={datos.length === 0 ? undefined : `${duracionSeg(total)} · ${periodo}`}
    >
      {datos.length === 0 ? (
        <Estado cargando={cargando} error={error} vacio="Sin paros en el rango" />
      ) : (
        <ul className="space-y-3.5">
          {datos.map((d) => (
            <li key={d.categoria}>
              <div className="flex items-baseline justify-between gap-3">
                <Label className="truncate">{d.categoria}</Label>
                <span className="shrink-0 font-mono text-[12px] tabular-nums text-sig-text">
                  {duracionSeg(d.segundos)}
                </span>
              </div>
              <ProgressBar valor={d.segundos} max={maximo} className="mt-2" />
              <Label caja="normal" className="block mt-1.5 text-sig-dim">
                {num(d.paros)} {Number(d.paros) === 1 ? 'paro' : 'paros'}
                {total > 0 && ` · ${pct((Number(d.segundos) || 0) / total * 100)} del tiempo parado`}
              </Label>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
