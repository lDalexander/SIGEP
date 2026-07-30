import React from 'react';
import { Card, Badge, Ring, Label, Estado } from './ui';
import useApi from '../lib/useApi';

const RECIENTES = 8;

/**
 * «Checklist de mantenimiento» — los checklists más recientes, cada uno con su
 * anillo de progreso. No depende del rango de fechas del dashboard: siempre son
 * los últimos, como en las capturas.
 */
export default function ChecklistMantenimiento({ apiBase, intervalo }) {
  const { datos, cargando, error } = useApi(`${apiBase}/mantenimiento/checklist`, {
    params: { limit: RECIENTES },
    intervalo,
  });

  const checklists = Array.isArray(datos) ? datos : [];

  return (
    <Card titulo="Checklist de mantenimiento" meta={`${RECIENTES} recientes`}>
      {checklists.length === 0 ? (
        <Estado cargando={cargando} error={error} vacio="Sin checklists registrados" />
      ) : (
        <ul className="space-y-2.5">
          {checklists.map((c) => (
            <li
              key={c.id}
              className="flex items-center gap-3.5 rounded-xl border border-sig-line bg-white/[0.02] px-3.5 py-3"
            >
              <Ring valor={c.items_ok} max={c.total_items} />

              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2 flex-wrap">
                  <Badge tono={c.momento === 'ENTRADA' ? 'ok' : 'amber'}>{c.momento}</Badge>
                  <span className="font-mono text-[11px] tabular-nums tracking-label text-sig-muted">
                    {c.hora}
                  </span>
                  <Badge tono="gray">{c.codigo_turno}</Badge>
                </div>

                <p className="mt-1.5 text-[12px] text-sig-muted truncate">
                  {c.maquina} · {c.operador}
                  {c.supervisor && (
                    <>
                      {' · sup. '}
                      <span className="font-bold text-sig-text">{c.supervisor}</span>
                    </>
                  )}
                </p>

                {c.comentarios && (
                  <p className="mt-1 text-[12px] italic text-sig-dim truncate">
                    «{c.comentarios}»
                  </p>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      {error && checklists.length > 0 && (
        <Label caja="normal" className="block mt-3 text-sig-amber/70">
          sin conexión — último dato recibido
        </Label>
      )}
    </Card>
  );
}
