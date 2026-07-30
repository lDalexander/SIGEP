import React from 'react';
import { Card, Badge, Dot, Label, Estado } from './ui';
import { num, duracion, plural } from '../lib/format';

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
