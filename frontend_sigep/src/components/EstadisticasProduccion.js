import React, { useState } from 'react';
import { Card, Tabs, ProgressBar, Label, Badge, Estado } from './ui';
import { num, pct, plural } from '../lib/format';
import useApi from '../lib/useApi';

/* Agrupaciones que acepta /dashboard/estadisticas en `dim`. `resumen` es cómo se
   nombra la agrupación en la línea de totales («por máquina»). */
const DIMENSIONES = [
  { value: 'maquina',                      label: 'Máquina',            resumen: 'por máquina' },
  { value: 'operario',                     label: 'Operario',           resumen: 'por operario' },
  { value: 'marca_presentacion',           label: 'Marca+Pres.',        resumen: 'por marca y presentación' },
  { value: 'marca_presentacion_fragancia', label: 'Marca+Pres.+Frag.',  resumen: 'por marca, presentación y fragancia' },
];

/**
 * «Estadísticas de producción» — ranking configurable por agrupación.
 *
 * Ya no tiene presets temporales propios (Hoy / 7d / 30d / Todo): usa el rango de
 * fechas y la franja horaria del filtro de la cabecera, igual que el resto del
 * dashboard, para que todas las tarjetas hablen siempre del mismo período. El
 * endpoint da precedencia a `desde`/`hasta` sobre su parámetro `rango`, así que basta
 * con enviarlos.
 *
 * Ojo con el criterio del endpoint: el rango de fechas filtra por la hora de inicio de
 * la sesión, mientras la franja horaria filtra por la hora del pallet. Con franja
 * activa, quien no produjo nada dentro de ella desaparece del ranking.
 *
 * `sinSegmentar` avisa de que hay segmentadores activos en el dashboard que **esta**
 * tarjeta no está aplicando: `/dashboard/estadisticas` es el único de los endpoints
 * con rango que no acepta `maquina`/`operador`/`marca`/`presentacion`/`fragancia`, y un
 * ranking que ignora en silencio el filtro puesto arriba se leería como si lo aplicara.
 */
export default function EstadisticasProduccion({
  apiBase, desde, hasta, horaDesde, horaHasta, periodo, intervalo, sinSegmentar = false,
}) {
  const [dim, setDim] = useState('maquina');

  const { datos, cargando, error } = useApi(`${apiBase}/dashboard/estadisticas`, {
    /* Las horas solo se envían si están puestas: sin ellas el endpoint responde como
       siempre. `useApi` serializa los params, así que cambiar el rango recarga solo. */
    params: {
      dim,
      desde,
      hasta,
      ...(horaDesde ? { hora_desde: horaDesde } : {}),
      ...(horaHasta ? { hora_hasta: horaHasta } : {}),
    },
    intervalo,
  });

  const items = datos?.items || [];
  const maximo = items.length > 0 ? Math.max(...items.map((i) => i.pacas)) : 0;
  const etiquetaDim = DIMENSIONES.find((d) => d.value === dim)?.resumen || '';

  return (
    <Card
      titulo="Estadísticas de producción"
      meta={
        <div className="flex flex-wrap items-center gap-3">
          <Tabs items={DIMENSIONES} value={dim} onChange={setDim} />
          <Label caja="normal" className="shrink-0">{periodo}</Label>
          {sinSegmentar && (
            <Badge tono="amber">sin segmentar</Badge>
          )}
        </div>
      }
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
