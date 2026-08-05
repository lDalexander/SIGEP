import React from 'react';
import { StatCard, Cifra, Label, Dot } from '../ui';
import { Esqueleto } from '../ui/Estado';
import { duracionSeg, num } from '../../lib/format';

/**
 * Las cuatro tarjetas KPI de la vista de paros.
 *
 * La primera es de AHORA (máquinas paradas en este instante) y las otras tres del
 * rango consultado; el pie de cada tarjeta lo dice para que no se confundan.
 *
 * Props:
 *   kpis    : `kpis` de /dashboard/paros
 *   periodo : texto del rango consultado
 */
export default function ParosKPIs({ kpis, periodo = 'hoy', cargando, error }) {
  const esperando = cargando && !kpis;
  const paradas = Number(kpis?.maquinas_paradas) || 0;

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 wide:grid-cols-4 gap-5">
      {/* 1 — En vivo: cuántas líneas están detenidas en este momento. */}
      <StatCard
        etiqueta="Máquinas paradas ahora"
        acento={paradas > 0 ? 'amber' : 'ok'}
        pie={
          <span className="inline-flex items-center gap-2">
            <Dot tono={paradas > 0 ? 'amber' : 'ok'} pulso={paradas > 0} />
            <Label caja="normal">
              {num(kpis?.maquinas_produciendo)} produciendo · en vivo
            </Label>
          </span>
        }
      >
        {esperando ? <Esqueleto className="h-11 w-20" /> : (
          <div className="flex items-baseline gap-2.5">
            <p className={`text-[42px] leading-none font-extrabold tracking-tight tabular-nums
                           ${paradas > 0 ? 'text-sig-amber' : 'text-white'}`}>
              {num(kpis?.maquinas_paradas)}
            </p>
            <span className="text-[13px] text-sig-muted">
              {paradas === 1 ? 'línea detenida' : 'líneas detenidas'}
            </span>
          </div>
        )}
      </StatCard>

      {/* 2 — Recuento del rango */}
      <StatCard
        etiqueta="Paros registrados"
        acento="ok"
        pie={
          <Label caja="normal">
            {num(kpis?.en_curso)} en curso
            {Number(kpis?.sin_cierre) > 0 && ` · ${num(kpis?.sin_cierre)} sin cierre`}
          </Label>
        }
      >
        {esperando ? <Esqueleto className="h-11 w-20" /> : (
          <div className="flex items-baseline gap-2.5">
            <p className="text-[42px] leading-none font-extrabold tracking-tight text-white tabular-nums">
              {num(kpis?.total_paros)}
            </p>
            <span className="text-[13px] text-sig-muted">{periodo}</span>
          </div>
        )}
      </StatCard>

      {/* 3 — Tiempo perdido acumulado */}
      <StatCard etiqueta="Tiempo total parado" acento="amber" pie={<Label caja="normal">{periodo}</Label>}>
        {esperando
          ? <Esqueleto className="h-11 w-32" />
          : <Cifra valor={duracionSeg(kpis?.segundos_total)} unidad="Suma de los paros" />}
      </StatCard>

      {/* 4 — Duración media (null cuando no hay ningún paro con duración conocida) */}
      <StatCard etiqueta="Duración promedio" acento="ok" pie={<Label caja="normal">por paro · {periodo}</Label>}>
        {esperando
          ? <Esqueleto className="h-11 w-32" />
          : <Cifra valor={duracionSeg(kpis?.segundos_promedio)} unidad="Media del rango" />}
      </StatCard>

      {error && (
        <p className="sm:col-span-2 wide:col-span-4 sig-meta text-sig-amber/70">
          Sin conexión con el servidor — se muestran los últimos datos recibidos
        </p>
      )}
    </div>
  );
}
