import React from 'react';
import { Card, ProgressBar, Label, Estado } from './ui';
import { num, pct, plural } from '../lib/format';
import useApi from '../lib/useApi';
import { dimAutomatica, paramsDeFiltros, serializarParams } from '../lib/filtros';

/* Cómo se nombra cada agrupación en la línea de totales («por máquina»). Las claves son
   los valores que acepta `dim` en /dashboard/estadisticas. */
const RESUMEN_DIM = {
  maquina: 'por máquina',
  operario: 'por operario',
  marca_presentacion: 'por marca y presentación',
  marca_presentacion_fragancia: 'por marca, presentación y fragancia',
};

/**
 * «Estadísticas de producción» — ranking del período, segmentado como el resto del
 * dashboard.
 *
 * No tiene controles propios, ni temporales ni de agrupación:
 *
 * - El **período** es el rango de fechas y la franja horaria de la cabecera, para que
 *   todas las tarjetas hablen siempre del mismo. El endpoint da precedencia a
 *   `desde`/`hasta` sobre su parámetro `rango`, así que basta con enviarlos.
 * - Los **filtros** son los segmentadores de arriba. `/dashboard/estadisticas` los
 *   acepta desde 2026-08-05; antes era el único endpoint con rango que no podía
 *   segmentarse y la tarjeta lo avisaba con un badge.
 * - La **agrupación** se deduce de lo segmentado (`dimAutomatica`), en vez de tener un
 *   selector con los mismos nombres que los segmentadores a dos dedos de distancia.
 *
 * Ojo con el criterio del endpoint, que es mixto y **de antes** de este cambio: el rango
 * de fechas filtra por la hora de inicio de la sesión, mientras la franja horaria filtra
 * por la hora del pallet. De ahí que su total no coincida con el KPI de producción, que
 * cuenta por hora del pallet: no es el filtro, es qué se considera «del día».
 */
export default function EstadisticasProduccion({
  apiBase, desde, hasta, horaDesde, horaHasta, periodo, intervalo, filtros,
}) {
  const dim = dimAutomatica(filtros);

  const { datos, cargando, error } = useApi(`${apiBase}/dashboard/estadisticas`, {
    /* Las horas y los filtros solo se envían si están puestos: sin ellos el endpoint
       responde como siempre. `useApi` compara los params por valor, así que cambiar el
       rango o un segmentador recarga la tarjeta sola. */
    params: {
      dim,
      desde,
      hasta,
      ...(horaDesde ? { hora_desde: horaDesde } : {}),
      ...(horaHasta ? { hora_hasta: horaHasta } : {}),
      ...paramsDeFiltros(filtros),
    },
    /* Los filtros son listas: sin este serializador viajarían como `maquina[]=A`, que el
       backend ignora — el ranking saldría sin segmentar aparentando estarlo. */
    serializar: serializarParams,
    intervalo,
  });

  const items = datos?.items || [];
  const maximo = items.length > 0 ? Math.max(...items.map((i) => i.pacas)) : 0;
  const etiquetaDim = RESUMEN_DIM[dim] || '';

  return (
    <Card
      titulo="Estadísticas de producción"
      meta={<Label caja="normal" className="shrink-0">{periodo}</Label>}
    >
      {/* Línea de totales */}
      <div className="flex flex-wrap items-baseline gap-x-5 gap-y-1 pb-4">
        <span className="text-[15px] font-bold tabular-nums text-sig-text">
          {num(datos?.total_pacas)}<span className="ml-1.5 text-[12px] font-normal text-sig-muted">pacas</span>
        </span>
        <span className="text-[15px] font-bold tabular-nums text-sig-text">
          {num(datos?.total_sesiones)}<span className="ml-1.5 text-[12px] font-normal text-sig-muted">sesiones</span>
        </span>
        <Label caja="normal">{etiquetaDim}</Label>
      </div>

      {items.length === 0 ? (
        <Estado cargando={cargando} error={error} vacio={`Sin producción · ${periodo}`} />
      ) : (
        <ol className="space-y-4">
          {items.map((item, idx) => (
            <li key={`${item.etiqueta}-${idx}`}>
              <div className="flex items-baseline justify-between gap-3">
                <p className="min-w-0 truncate">
                  <span className="font-mono text-[11px] tracking-label text-sig-dim mr-2.5">
                    {String(idx + 1).padStart(2, '0')}
                  </span>
                  <span className="text-[14px] font-bold text-sig-text">{item.etiqueta}</span>
                </p>
                <p className="shrink-0 text-[14px] font-bold tabular-nums text-sig-text">
                  {num(item.pacas)}
                  <span className="ml-1.5 text-[11px] font-normal text-sig-muted">pacas</span>
                </p>
              </div>
              <ProgressBar valor={item.pacas} max={maximo} pista={false} className="mt-2" />
              <Label caja="normal" className="block mt-1.5">
                {plural(item.sesiones, 'sesión', 'sesiones')} · {pct(item.pct)}
              </Label>
            </li>
          ))}
        </ol>
      )}
    </Card>
  );
}
