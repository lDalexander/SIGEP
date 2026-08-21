import React from 'react';
import { Card, Badge, Dot, Label, Estado } from './ui';
import { num, duracion, plural, velocidad, rangoVelocidad, estadoVelocidad } from '../lib/format';

/* Solo se colorea lo que se ha comparado de verdad. Fuera de rango es ámbar por los dos
   lados —por debajo la línea va lenta; por encima, o la máquina se pasó de lo previsto o
   el operario tecleó de más, y las dos cosas hay que mirarlas— y el `title` dice cuál. */
const COLOR_VELOCIDAD = {
  dentro: 'text-sig-ok',
  bajo: 'text-sig-amber',
  sobre: 'text-sig-amber',
};

/** Todo lo que no cabe en la celda: promedio, nº de lecturas y veredicto contra la meta. */
function tituloVelocidad(fila) {
  if (!fila.velocidad_lecturas) return 'La tablet no registró ninguna velocidad en este turno';
  const partes = [
    `Última lectura${fila.velocidad_hora ? ` a las ${fila.velocidad_hora}` : ''}`,
    `promedio ${velocidad(fila.velocidad_promedio)} de ${plural(fila.velocidad_lecturas, 'lectura', 'lecturas')}`,
  ];
  const rango = rangoVelocidad(fila.velocidad_min, fila.velocidad_max);
  if (rango) {
    const estado = estadoVelocidad(fila.velocidad_ultima, fila.velocidad_min, fila.velocidad_max);
    const veredicto = { bajo: 'por debajo de', sobre: 'por encima de', dentro: 'dentro de' }[estado];
    partes.push(`${veredicto} la meta de ${rango} unidades/min`);
  } else {
    partes.push('sin velocidad estándar configurada para esta presentación');
  }
  return partes.join(' · ');
}

/**
 * «Estado operativo · líneas» — una fila por sesión de trabajo.
 *
 * Props:
 *   datos   : respuesta de /dashboard/estado_operativo
 *   periodo : texto del metadato («hoy» o el rango elegido)
 */
export default function OperationsTable({ datos = [], periodo = 'hoy', cargando, error }) {
  return (
    <Card
      titulo="Estado operativo · líneas"
      meta={`${plural(datos.length, 'sesión', 'sesiones')} · ${periodo}`}
      sinPad
    >
      {datos.length === 0 ? (
        <Estado cargando={cargando} error={error} vacio="Sin sesiones en el rango" />
      ) : (
        <ul className="divide-y divide-sig-line">
          {datos.map((fila) => {
            const activa = fila.estado === 'Activo';
            return (
              <li
                key={fila.sesion_id}
                className="flex items-center gap-4 px-5 py-3.5 hover:bg-white/[0.015] transition-colors"
              >
                <Dot tono={activa ? 'ok' : 'off'} />

                {/* Máquina y operario */}
                <div className="w-[150px] shrink-0">
                  <p className="text-[15px] font-bold leading-tight text-sig-text">{fila.maquina}</p>
                  <Label className="block mt-1">{fila.operador}</Label>
                </div>

                {/* Producto — centrado en la fila, como en las capturas */}
                <p className="flex-1 min-w-0 truncate text-center text-[13px] text-sig-muted">
                  {fila.producto || '—'}
                </p>

                {/* Velocidad de máquina (2026-08-19). «—» cuando nadie la registró: un
                    cero ahí se leería como una máquina detenida. El título lleva el
                    promedio y el número de lecturas, porque una media de una sola lectura
                    no describe un turno.

                    Debajo, el rango estándar de esa (máquina, presentación) si está
                    configurado en /admin → Jerarquía (2026-08-21), y la cifra se colorea
                    según lo cumpla. Sin estándar la lectura se enseña SIN COLOR: no hay
                    contra qué compararla, y un color ahí sería un juicio inventado. */}
                <div
                  className="text-right shrink-0 w-[104px]"
                  title={tituloVelocidad(fila)}
                >
                  <p className={`text-[15px] font-bold leading-none tabular-nums ${
                    COLOR_VELOCIDAD[estadoVelocidad(
                      fila.velocidad_ultima, fila.velocidad_min, fila.velocidad_max,
                    )] || 'text-sig-text'
                  }`}>
                    {velocidad(fila.velocidad_ultima)}
                  </p>
                  <Label className="block mt-1">Unid./min</Label>
                  {rangoVelocidad(fila.velocidad_min, fila.velocidad_max) && (
                    <Label className="block mt-0.5 text-sig-dim">
                      meta {rangoVelocidad(fila.velocidad_min, fila.velocidad_max)}
                    </Label>
                  )}
                </div>

                {/* Pacas */}
                <div className="text-right shrink-0 w-[80px]">
                  <p className="text-[19px] font-bold leading-none tabular-nums text-sig-text">
                    {num(fila.total_pacas)}
                  </p>
                  <Label className="block mt-1">Pacas</Label>
                </div>

                {/* Estado */}
                <div className="shrink-0 w-[130px] flex justify-end">
                  {activa ? (
                    <Badge tono="ok">Activo · {duracion(fila.tiempo_transcurrido)}</Badge>
                  ) : (
                    <Badge tono="gray">Finalizado</Badge>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}
