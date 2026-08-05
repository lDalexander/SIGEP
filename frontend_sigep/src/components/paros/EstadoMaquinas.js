import React from 'react';
import { Card, Badge, Dot, Label, Estado } from '../ui';
import Cronometro from './Cronometro';
import { duracionSeg, horaDeTexto, plural } from '../../lib/format';

/* El estado de cada máquina es de AHORA (lo dice el backend en `maquinas[].estado`),
   mientras `paros`/`segundos` son los acumulados del rango consultado. */
const TONOS = {
  PARO:        { badge: 'amber', punto: 'amber' },
  PRODUCIENDO: { badge: 'ok',    punto: 'ok' },
  'SIN TURNO': { badge: 'gray',  punto: 'off' },
};

/**
 * «Estado de máquinas» — semáforo en vivo, una tarjeta por máquina.
 *
 * Props:
 *   datos      : `maquinas` de /dashboard/paros
 *   recibidoEn : Date.now() de la última respuesta (para el cronómetro)
 *   periodo    : texto del metadato, para dejar claro de qué rango son los acumulados
 */
export default function EstadoMaquinas({ datos = [], recibidoEn, periodo = 'hoy', cargando, error }) {
  const paradas = datos.filter((m) => m.estado === 'PARO').length;

  return (
    <Card
      titulo="Estado de máquinas"
      meta={
        datos.length === 0
          ? undefined
          : `${plural(paradas, 'máquina parada', 'máquinas paradas')} · ahora`
      }
    >
      {datos.length === 0 ? (
        <Estado cargando={cargando} error={error} vacio="Sin máquinas registradas" />
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 wide:grid-cols-3 gap-3">
          {datos.map((m) => {
            const tono = TONOS[m.estado] || TONOS['SIN TURNO'];
            const enParo = m.estado === 'PARO';
            return (
              <div
                key={m.maquina}
                className={`rounded-xl border p-4 transition-colors
                            ${enParo
                              ? 'border-sig-amber/40 bg-sig-amber/[0.04]'
                              : 'border-sig-line bg-white/[0.012]'}`}
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <p className="flex items-center gap-2 text-[15px] font-bold leading-tight text-sig-text">
                      <Dot tono={tono.punto} pulso={enParo} />
                      <span className="truncate">{m.maquina}</span>
                    </p>
                    <Label className="block mt-1.5 truncate">{m.operador}</Label>
                  </div>
                  <Badge tono={tono.badge}>{m.estado}</Badge>
                </div>

                {/* Cuerpo: el paro en curso manda; si no, la hora de inicio del turno. */}
                {enParo && m.paro_actual ? (
                  <div className="mt-3 pt-3 border-t border-sig-amber/20">
                    <p className="text-[22px] font-extrabold leading-none text-sig-amber">
                      <Cronometro
                        segundos={m.paro_actual.duracion_segundos}
                        recibidoEn={recibidoEn}
                      />
                    </p>
                    <Label className="block mt-2 text-sig-amber/80">
                      {m.paro_actual.categoria}
                    </Label>
                    {m.paro_actual.comentario && (
                      <p className="mt-1.5 text-[12px] leading-snug text-sig-muted line-clamp-2">
                        {m.paro_actual.comentario}
                      </p>
                    )}
                    <Label caja="normal" className="block mt-1.5 text-sig-dim">
                      desde {horaDeTexto(m.paro_actual.inicio)}
                    </Label>
                  </div>
                ) : (
                  <div className="mt-3 pt-3 border-t border-sig-line">
                    <Label caja="normal" className="block text-sig-dim">
                      {m.inicio_turno
                        ? `turno desde ${horaDeTexto(m.inicio_turno)}`
                        : 'sin turno abierto'}
                    </Label>
                  </div>
                )}

                {/* Acumulado del rango — la única cifra que no es «ahora». */}
                <div className="mt-3 flex items-baseline justify-between gap-2">
                  <Label>{periodo}</Label>
                  <Label caja="normal" className="text-sig-muted">
                    {m.paros === 0
                      ? 'sin paros'
                      : `${plural(m.paros, 'paro', 'paros')} · ${duracionSeg(m.segundos)}`}
                  </Label>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </Card>
  );
}
