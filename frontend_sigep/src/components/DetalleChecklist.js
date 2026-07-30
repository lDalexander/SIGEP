import React from 'react';
import { Card, Badge, Estado } from './ui';
import { plural } from '../lib/format';
import useApi from '../lib/useApi';

/* Columnas que siempre están, antes de las de ítems. */
const FIJAS = [
  { clave: 'id',           titulo: 'ID' },
  { clave: 'fecha_turno',  titulo: 'Fecha turno' },
  { clave: 'codigo_turno', titulo: 'Turno' },
  { clave: 'maquina',      titulo: 'Máquina' },
  { clave: 'operador',     titulo: 'Operador' },
  { clave: 'supervisor',   titulo: 'Supervisor' },
  { clave: 'momento',      titulo: 'Momento' },
  { clave: 'hora',         titulo: 'Hora' },
];

/**
 * Etiquetas de ítems en orden de primera aparición, exactamente como las genera
 * `_hoja_checklists` en api_produccion/routers/reportes.py, para que las columnas
 * coincidan con las del Excel descargable.
 */
function columnasDeItems(checklists) {
  const vistas = new Set();
  const etiquetas = [];
  checklists.forEach((c) => {
    (c.items || []).forEach((it) => {
      if (!vistas.has(it.etiqueta)) {
        vistas.add(it.etiqueta);
        etiquetas.push(it.etiqueta);
      }
    });
  });
  return etiquetas;
}

/**
 * «Detalle de checklist de mantenimiento» — tabla a ancho completo con una columna
 * por cada ítem del checklist. Usa el mismo endpoint, criterio y orden que el Excel
 * de formularios (`/reportes/formularios_excel`).
 *
 * Diferencia deliberada con el Excel: allí ENTRADA y SALIDA van en hojas separadas,
 * de modo que las columnas de ítems son homogéneas por hoja. Aquí los dos momentos
 * conviven en una sola tabla (con la columna MOMENTO), así que las columnas son la
 * unión de los ítems de ambos. Si un momento no usa un ítem, su celda sale como «–».
 */
export default function DetalleChecklist({ apiBase, desde, hasta, periodo, intervalo }) {
  const { datos, cargando, error } = useApi(`${apiBase}/mantenimiento/checklist`, {
    params: { desde, hasta },
    intervalo,
  });

  const checklists = Array.isArray(datos) ? datos : [];
  const items = columnasDeItems(checklists);

  return (
    <Card
      titulo="Detalle de checklist de mantenimiento"
      meta={`${plural(checklists.length, 'checklist', 'checklists')} · ${periodo}`}
      sinPad
    >
      {checklists.length === 0 ? (
        <Estado cargando={cargando} error={error} vacio="Sin checklists en el rango" />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-left">
            <thead>
              <tr className="border-y border-sig-line">
                {FIJAS.map((col) => (
                  <th key={col.clave} className="sig-label whitespace-nowrap px-3 py-2.5 font-normal">
                    {col.titulo}
                  </th>
                ))}
                {items.map((etiqueta) => (
                  <th
                    key={etiqueta}
                    title={etiqueta}
                    className="sig-label px-3 py-2.5 font-normal"
                  >
                    <span className="block max-w-[130px] truncate">{etiqueta}</span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-sig-line">
              {checklists.map((c) => {
                const marcado = new Map((c.items || []).map((it) => [it.etiqueta, it.marcado]));
                return (
                  <tr key={c.id} className="hover:bg-white/[0.015] transition-colors">
                    <td className="whitespace-nowrap px-3 py-3 font-mono text-[12px] tabular-nums text-sig-text">
                      {c.id}
                    </td>
                    <td className="whitespace-nowrap px-3 py-3 text-[12px] text-sig-muted">
                      {c.fecha_turno || '—'}
                    </td>
                    <td className="whitespace-nowrap px-3 py-3 text-[12px] text-sig-muted">
                      {c.codigo_turno || '—'}
                    </td>
                    <td className="whitespace-nowrap px-3 py-3 text-[13px] font-bold text-sig-text">
                      {c.maquina}
                    </td>
                    <td className="whitespace-nowrap px-3 py-3 text-[12px] text-sig-muted">
                      {c.operador}
                    </td>
                    <td className="whitespace-nowrap px-3 py-3 text-[12px] text-sig-muted">
                      {c.supervisor || '—'}
                    </td>
                    <td className="whitespace-nowrap px-3 py-3">
                      <Badge tono={c.momento === 'ENTRADA' ? 'ok' : 'amber'}>{c.momento}</Badge>
                    </td>
                    <td className="whitespace-nowrap px-3 py-3 font-mono text-[12px] tabular-nums text-sig-text">
                      {c.hora}
                    </td>

                    {items.map((etiqueta) => (
                      <td key={etiqueta} className="px-3 py-3 text-center">
                        {marcado.get(etiqueta) ? (
                          <span className="font-bold text-sig-ok" title="marcado">X</span>
                        ) : (
                          <span className="text-sig-dim" title="sin marcar">–</span>
                        )}
                      </td>
                    ))}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
