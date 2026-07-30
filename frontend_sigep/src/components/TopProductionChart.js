import React from 'react';
import { Card, ProgressBar, Estado } from './ui';
import { num } from '../lib/format';

/**
 * «Top marcas» — barras horizontales sobre pista gris, ordenadas de mayor a menor
 * (el endpoint ya las devuelve ordenadas).
 *
 * Props:
 *   datos   : respuesta de /dashboard/top_produccion — [{name, value}]
 *   periodo : texto del título («hoy» o el rango elegido)
 */
export default function TopProductionChart({ datos = [], periodo = 'hoy', cargando, error }) {
  const maximo = datos.length > 0 ? Math.max(...datos.map((d) => d.value)) : 0;

  return (
    <Card titulo={`Top marcas · ${periodo}`} meta="pacas">
      {datos.length === 0 ? (
        <Estado cargando={cargando} error={error} vacio="Sin producción en el rango" />
      ) : (
        <ul className="space-y-3.5">
          {datos.map((d) => (
            <li key={d.name} className="flex items-center gap-3">
              <span className="w-[92px] shrink-0 truncate text-[12px] font-semibold text-sig-text">
                {d.name}
              </span>
              <ProgressBar valor={d.value} max={maximo} className="flex-1" />
              <span className="w-[52px] shrink-0 text-right text-[13px] font-bold tabular-nums text-sig-text">
                {num(d.value)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
