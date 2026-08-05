import React from 'react';
import { Card, Badge, Label, Estado } from './ui';
import useApi from '../lib/useApi';
import { fechaDeTexto, horaDeTexto } from '../lib/format';

const RECIENTES = 8;

/**
 * «Comentarios de turno» — el texto libre que los operarios escriben desde la tablet.
 *
 * No depende del rango del dashboard, igual que la tarjeta de checklists: son los
 * últimos que han llegado. Son pocos y esporádicos (uno por turno como mucho), así
 * que atarlos al rango dejaría la tarjeta vacía casi todos los días.
 */
export default function ComentariosTurno({ apiBase, intervalo }) {
  const { datos, cargando, error } = useApi(`${apiBase}/dashboard/comentarios_turno`, {
    params: { limit: RECIENTES },
    intervalo,
  });

  const comentarios = Array.isArray(datos) ? datos : [];

  return (
    <Card titulo="Comentarios de turno" meta={`${RECIENTES} recientes`}>
      {comentarios.length === 0 ? (
        <Estado cargando={cargando} error={error} vacio="Sin comentarios registrados" />
      ) : (
        <ul className="space-y-2.5">
          {comentarios.map((c) => (
            <li
              key={c.id}
              className="rounded-xl border border-sig-line bg-white/[0.02] px-3.5 py-3"
            >
              <div className="flex items-center gap-2 flex-wrap">
                <Badge tono="gray" className="tabular-nums">
                  {fechaDeTexto(c.creado_en)} · {horaDeTexto(c.creado_en)}
                </Badge>
                <Label className="truncate">{c.maquina}</Label>
              </div>

              {/* El texto se respeta tal cual, con sus saltos de línea: el operario
                  escribe varias observaciones en un mismo comentario. */}
              <p className="mt-1.5 whitespace-pre-line text-[13px] leading-snug text-sig-text">
                {c.texto}
              </p>

              <Label caja="normal" className="block mt-1.5 text-sig-dim truncate">
                {c.operador}
              </Label>
            </li>
          ))}
        </ul>
      )}
      {error && comentarios.length > 0 && (
        <Label caja="normal" className="block mt-3 text-sig-amber/70">
          sin conexión — último dato recibido
        </Label>
      )}
    </Card>
  );
}
