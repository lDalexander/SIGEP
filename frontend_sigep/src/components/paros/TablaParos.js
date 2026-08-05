import React, { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { Card, Badge, Label, Estado } from '../ui';
import Cronometro from './Cronometro';
import { duracionSeg, horaDeTexto, fechaDeTexto, plural } from '../../lib/format';

/* Tono del badge según el estado que calcula el backend (ver `_estado_paro`):
   EN CURSO = ámbar y en vivo · CERRADO = gris · SIN CIERRE = ámbar tenue, es una
   anomalía de registro, no un paro en marcha. */
const TONOS = { 'EN CURSO': 'amber', CERRADO: 'gray', 'SIN CIERRE': 'amber' };

/**
 * «Paros del rango» — una fila por paro, desplegable para ver el detalle completo.
 *
 * Props:
 *   datos      : `paros` de /dashboard/paros
 *   recibidoEn : Date.now() de la última respuesta (cronómetro de los que están abiertos)
 *   periodo    : texto del metadato
 *   variosDias : muestra la fecha además de la hora (en un solo día sobra)
 */
export default function TablaParos({
  datos = [], recibidoEn, periodo = 'hoy', variosDias = false, cargando, error,
}) {
  const [abierto, setAbierto] = useState(null);

  return (
    <Card
      titulo="Paros del rango"
      meta={`${plural(datos.length, 'paro', 'paros')} · ${periodo}`}
      sinPad
    >
      {datos.length === 0 ? (
        <Estado cargando={cargando} error={error} vacio="Sin paros en el rango" />
      ) : (
        <ul className="divide-y divide-sig-line">
          {datos.map((p) => {
            const expandido = abierto === p.id;
            const enCurso = p.estado === 'EN CURSO';
            return (
              <li key={p.id}>
                <button
                  type="button"
                  onClick={() => setAbierto(expandido ? null : p.id)}
                  aria-expanded={expandido}
                  /* La fila es un botón con seis bloques de texto suelto; el nombre
                     accesible resume de qué paro se trata en vez de leerlos en fila. */
                  aria-label={`Paro de ${p.maquina} · ${p.categoria} · ${horaDeTexto(p.inicio)} · ${p.estado}`}
                  className="w-full flex items-center gap-4 px-5 py-3.5 text-left
                             hover:bg-white/[0.015] transition-colors"
                >
                  <span className="shrink-0 text-sig-dim">
                    {expandido ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                  </span>

                  {/* Horas de inicio y fin */}
                  <span className="shrink-0 w-[112px]">
                    <span className="block font-mono text-[13px] tabular-nums text-sig-text">
                      <span>{horaDeTexto(p.inicio)}</span>
                      <span className="text-sig-dim"> → </span>
                      <span>{p.fin ? horaDeTexto(p.fin) : '··'}</span>
                    </span>
                    {variosDias && (
                      <Label caja="normal" className="block mt-1 text-sig-dim">
                        {fechaDeTexto(p.inicio)}
                      </Label>
                    )}
                  </span>

                  {/* Máquina y operario */}
                  <span className="shrink-0 w-[150px] min-w-0">
                    <span className="block text-[14px] font-bold leading-tight text-sig-text truncate">
                      {p.maquina}
                    </span>
                    <Label className="block mt-1 truncate">{p.operador}</Label>
                  </span>

                  {/* Categoría y comentario del operario */}
                  <span className="flex-1 min-w-0">
                    <Label className="block text-sig-amber/90">{p.categoria}</Label>
                    <span className="block mt-1 text-[13px] text-sig-muted truncate">
                      {p.comentario || '—'}
                    </span>
                  </span>

                  {/* Duración */}
                  <span className="shrink-0 w-[92px] text-right">
                    <span className={`block text-[17px] font-bold leading-none tabular-nums
                                      ${enCurso ? 'text-sig-amber' : 'text-sig-text'}`}>
                      {enCurso
                        ? <Cronometro segundos={p.duracion_segundos} recibidoEn={recibidoEn} />
                        : duracionSeg(p.duracion_segundos)}
                    </span>
                    <Label className="block mt-1">
                      {p.duracion_estimada ? 'estimada' : 'parado'}
                    </Label>
                  </span>

                  {/* Estado */}
                  <span className="shrink-0 w-[104px] flex justify-end">
                    <Badge tono={TONOS[p.estado] || 'gray'} punto={enCurso}>
                      {p.estado}
                    </Badge>
                  </span>
                </button>

                {expandido && (
                  <div className="px-5 pb-4 pt-1 bg-white/[0.012]">
                    <dl className="grid grid-cols-1 sm:grid-cols-2 wide:grid-cols-4 gap-x-6 gap-y-3">
                      <Detalle rotulo="Producto" valor={p.producto} />
                      <Detalle rotulo="Inicio del paro" valor={`${fechaDeTexto(p.inicio)} · ${horaDeTexto(p.inicio, true)}`} />
                      <Detalle
                        rotulo={p.fin ? 'Fin del paro' : 'Fin del paro (sin registrar)'}
                        valor={p.fin
                          ? `${fechaDeTexto(p.fin)} · ${horaDeTexto(p.fin, true)}`
                          : p.fin_estimado
                            ? `hasta el cierre del turno · ${horaDeTexto(p.fin_estimado, true)}`
                            : 'en curso'}
                      />
                      <Detalle rotulo="Turno" valor={`${horaDeTexto(p.inicio_turno)} → ${p.fin_turno ? horaDeTexto(p.fin_turno) : 'abierto'}`} />
                      <Detalle rotulo="Sesión" valor={p.sesion_id ? `#${p.sesion_id}` : '—'} />
                      <Detalle rotulo="Paro" valor={`#${p.id}`} />
                      <Detalle
                        rotulo="Comentario del operario"
                        valor={p.comentario || '—'}
                        className="sm:col-span-2 wide:col-span-4"
                        multilinea
                      />
                      <Detalle
                        rotulo="Motivo tal como lo envió la tablet"
                        valor={p.motivo || '—'}
                        className="sm:col-span-2 wide:col-span-4"
                        multilinea
                      />
                    </dl>

                    {p.estado === 'SIN CIERRE' && (
                      /* No se afirma que la máquina siga parada: el paro se quedó abierto
                         y el turno lo cerró el sistema. Se dice tal cual. */
                      <p className="mt-3 text-[12px] leading-snug text-sig-amber/80">
                        El operario no cerró este paro y el turno se cerró después. La
                        duración es una estimación acotada al fin del turno, no un tiempo
                        medido.
                      </p>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}

/** Par rótulo/valor del panel desplegable. */
function Detalle({ rotulo, valor, className = '', multilinea = false }) {
  return (
    <div className={className}>
      <Label as="dt" className="block">{rotulo}</Label>
      <dd className={`mt-1 text-[13px] text-sig-text ${multilinea ? 'whitespace-pre-line' : 'truncate'}`}>
        {valor}
      </dd>
    </div>
  );
}
