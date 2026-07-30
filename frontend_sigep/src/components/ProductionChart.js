import React from 'react';
import { AreaChart, Area, XAxis, Tooltip, ResponsiveContainer } from 'recharts';
import { Card, Estado } from './ui';
import { num } from '../lib/format';

/* Tooltip con el desglose por sesión que ya viene en `detalle` desde la API. */
function TooltipHora({ active, payload, label }) {
  if (!active || !payload?.length) return null;
  const punto = payload[0].payload;
  const detalle = punto.detalle || [];

  return (
    <div className="sig-card bg-sig-input/95 px-3.5 py-2.5 shadow-xl backdrop-blur-sm">
      <p className="font-mono text-[11px] tracking-label text-sig-muted">{label}</p>
      <p className="mt-0.5 mb-2">
        <span className="text-sig-amber font-bold text-[15px] tabular-nums">{num(punto.pallets)}</span>
        <span className="text-sig-muted text-[12px] ml-1.5">pacas</span>
      </p>
      {detalle.map((d, i) => (
        <p key={i} className="text-[11px] text-sig-muted leading-relaxed">
          <span className="text-sig-text font-semibold">{d.maquina}</span>
          {' · '}{d.producto}{' · '}
          <span className="tabular-nums text-sig-text">{num(d.pacas)}</span>
        </p>
      ))}
    </div>
  );
}

/* Punto marcador solo en el último valor de la serie. */
function UltimoPunto({ cx, cy, index, total }) {
  if (index !== total - 1 || cx == null || cy == null) return null;
  return <circle cx={cx} cy={cy} r={3.5} fill="#F5A623" stroke="#0A100E" strokeWidth={1.5} />;
}

/**
 * «Producción por hora · pacas» — área con curva suavizada, sin cuadrícula ni eje Y.
 *
 * Props:
 *   datos   : respuesta de /dashboard/produccion_hora
 *   periodo : texto del metadato de la derecha («hoy» o el rango elegido)
 */
export default function ProductionChart({ datos = [], periodo = 'hoy', cargando, error }) {
  const hayDatos = datos.length > 0;

  return (
    <Card titulo="Producción por hora · pacas" meta={periodo}>
      {!hayDatos ? (
        <Estado
          cargando={cargando}
          error={error}
          vacio="Sin producción registrada en el rango"
          className="h-[260px]"
        />
      ) : (
        <div className="h-[260px] w-full -mx-1">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={datos} margin={{ top: 12, right: 10, left: 10, bottom: 0 }}>
              <defs>
                <linearGradient id="gradProduccion" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#F5A623" stopOpacity={0.32} />
                  <stop offset="100%" stopColor="#F5A623" stopOpacity={0} />
                </linearGradient>
              </defs>
              <XAxis
                dataKey="hora"
                tickFormatter={(h) => String(h).slice(0, 2)}
                tick={{ fill: '#5A6A64', fontSize: 11, fontFamily: 'JetBrains Mono' }}
                axisLine={false}
                tickLine={false}
                interval="preserveStartEnd"
                minTickGap={18}
              />
              <Tooltip
                content={<TooltipHora />}
                cursor={{ stroke: 'rgba(245,166,35,0.25)', strokeWidth: 1 }}
              />
              <Area
                type="monotone"
                dataKey="pallets"
                stroke="#F5A623"
                strokeWidth={2}
                fill="url(#gradProduccion)"
                dot={(props) => <UltimoPunto {...props} total={datos.length} />}
                activeDot={{ r: 4, fill: '#F5A623', stroke: '#0A100E', strokeWidth: 2 }}
                isAnimationActive={false}
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}
    </Card>
  );
}
