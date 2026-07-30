import React from 'react';
import { Card, Badge, Label, Estado } from './ui';
import { num } from '../lib/format';
import useApi from '../lib/useApi';

/* Estados del flujo de pedidos de bodega (models.PedidoBodegaDB). */
function tonoEstado(estado) {
  const e = String(estado || '').toUpperCase();
  if (e === 'ENTREGADO') return 'ok';
  if (e === 'PENDIENTE' || e === 'EN CAMINO') return 'amber';
  return 'gray';
}

/**
 * «Solicitudes de insumos» — pedidos de insumo del rango consultado.
 *
 * NOTA sobre «últimas 24h»: /insumos/dashboard filtra por día natural, no por
 * ventana móvil, y cada pedido solo trae la hora (`hora_solicitud`), no su fecha
 * completa, así que no se puede recortar a 24h exactas en el cliente sin inventar
 * el dato. Se muestra el rango consultado tal cual; el rótulo de las capturas se
 * conserva solo cuando ese rango es el día de hoy.
 */
export default function SolicitudesInsumos({ apiBase, desde, hasta, intervalo, esHoy }) {
  const { datos, cargando, error } = useApi(`${apiBase}/insumos/dashboard`, {
    params: { desde, hasta },
    intervalo,
  });

  const pedidos = datos?.pedidos || [];

  return (
    <Card titulo="Solicitudes de insumos" meta={esHoy ? 'últimas 24h' : `${desde} → ${hasta}`}>
      {pedidos.length === 0 ? (
        <Estado
          cargando={cargando}
          error={error}
          vacio={esHoy ? 'Sin solicitudes en las últimas 24h' : 'Sin solicitudes en el rango'}
        />
      ) : (
        <ul className="max-h-[280px] overflow-y-auto divide-y divide-sig-line">
          {pedidos.map((p) => (
            <li key={p.id} className="flex items-start gap-3 py-2.5 first:pt-0">
              <span className="shrink-0 font-mono text-[11px] tabular-nums tracking-label text-sig-dim mt-0.5">
                {p.hora_solicitud || '—'}
              </span>

              <div className="min-w-0 flex-1">
                <p className="text-[13px] font-semibold text-sig-text truncate">{p.insumo}</p>
                <Label className="block mt-1 truncate">
                  {[p.maquina, p.operador].filter(Boolean).join(' · ') || 'sin sesión'}
                  {p.solicitada != null && ` · ${num(p.solicitada)} u`}
                </Label>
              </div>

              <Badge tono={tonoEstado(p.estado)}>{p.estado}</Badge>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
