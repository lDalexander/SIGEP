import React from 'react';
import { AreaChart, Area, XAxis, Tooltip, ResponsiveContainer } from 'recharts';
import { Card, Label, Tabs, Estado } from './ui';
import { num, fechaEje, fechaLegible } from '../lib/format';

/* Un día completo agrega muchas más sesiones que una hora, y el tooltip se saldría
   de la pantalla. Se muestran las mayores y se resume el resto. */
const MAX_DETALLE = 6;

/* Tooltip con el desglose por sesión que ya viene en `detalle` desde la API. */
function TooltipPunto({ active, payload, label, agrupacion }) {
  if (!active || !payload?.length) return null;
  const punto = payload[0].payload;
  const detalle = punto.detalle || [];
  const visibles = detalle.slice(0, MAX_DETALLE);
  const ocultas = detalle.length - visibles.length;

  return (
    <div className="sig-card bg-sig-input/95 px-3.5 py-2.5 shadow-xl backdrop-blur-sm">
      <p className="font-mono text-[11px] tracking-label text-sig-muted">
        {agrupacion === 'dia' ? fechaLegible(label) : label}
      </p>
      <p className="mt-0.5 mb-2">
        <span className="text-sig-amber font-bold text-[15px] tabular-nums">{num(punto.pallets)}</span>
        <span className="text-sig-muted text-[12px] ml-1.5">pacas</span>
      </p>
      {visibles.map((d, i) => (
        <p key={i} className="text-[11px] text-sig-muted leading-relaxed">
          <span className="text-sig-text font-semibold">{d.maquina}</span>
          {' · '}{d.producto}{' · '}
          <span className="tabular-nums text-sig-text">{num(d.pacas)}</span>
        </p>
      ))}
      {ocultas > 0 && (
        <p className="font-mono text-[10px] tracking-label text-sig-dim mt-1">
          +{ocultas} sesion{ocultas === 1 ? '' : 'es'} más
        </p>
      )}
    </div>
  );
}

/* Punto marcador solo en el último valor de la serie. */
function UltimoPunto({ cx, cy, index, total }) {
  if (index !== total - 1 || cx == null || cy == null) return null;
  return <circle cx={cx} cy={cy} r={3.5} fill="#F5A623" stroke="#0A100E" strokeWidth={1.5} />;
}

/**
 * «Producción por hora / por día · pacas» — área con curva suavizada, sin cuadrícula
 * ni eje Y.
 *
 * El eje lo decide `agrupacion`, que viaja al backend como `?agrupar=`:
 *   'hora' — un punto por hora del reloj. Con un rango de varios días, cada punto
 *            SUMA esa hora de todos los días del rango; no es una línea de tiempo.
 *   'dia'  — un punto por fecha natural. Es la lectura correcta de un rango largo,
 *            y por eso App.js la elige sola en cuanto el rango pasa de un día.
 *
 * Props:
 *   datos          : respuesta de /dashboard/produccion_hora
 *   periodo        : texto del metadato de la derecha («hoy» o el rango elegido)
 *   agrupacion     : 'hora' | 'dia'
 *   onAgrupacion   : (valor) => void
 *   diaHabilitado  : false cuando el rango es de un solo día (un punto no es serie)
 */
export default function ProductionChart({
  datos = [],
  periodo = 'hoy',
  agrupacion = 'hora',
  onAgrupacion,
  diaHabilitado = false,
  cargando,
  error,
}) {
  const hayDatos = datos.length > 0;
  const porDia = agrupacion === 'dia';

  const toggle = (
    <div className="flex items-center gap-3">
      <Label caja="normal" className="shrink-0">{periodo}</Label>
      <Tabs
        items={[
          { value: 'hora', label: 'HORA' },
          {
            value: 'dia',
            label: 'DÍA',
            disabled: !diaHabilitado,
            title: diaHabilitado ? undefined : 'Elige un rango de más de un día',
          },
        ]}
        value={agrupacion}
        onChange={onAgrupacion}
      />
    </div>
  );

  return (
    <Card titulo={`Producción por ${porDia ? 'día' : 'hora'} · pacas`} meta={toggle}>
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
                tickFormatter={(v) => (porDia ? fechaEje(v) : String(v).slice(0, 2))}
                tick={{ fill: '#5A6A64', fontSize: 11, fontFamily: 'JetBrains Mono' }}
                axisLine={false}
                tickLine={false}
                /* Por hora: una marca por cada hora con producción — el endpoint solo
                   devuelve las horas con datos, así que el eje salta los huecos igual
                   que en las capturas (…04 05 08 09…). Por día las etiquetas son más
                   anchas, así que a partir de ~12 puntos se van salteando para que no
                   se solapen. */
                interval={porDia && datos.length > 12 ? Math.ceil(datos.length / 12) - 1 : 0}
              />
              <Tooltip
                content={<TooltipPunto agrupacion={agrupacion} />}
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
