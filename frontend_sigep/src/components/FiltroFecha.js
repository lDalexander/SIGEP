import React from 'react';
import { RefreshCw, Download } from 'lucide-react';
import { Button } from './ui';

/* Los tres reportes descargables. `ruta` es el endpoint de exportación a Excel;
   todos aceptan ?desde&hasta y responden 404 si el rango está vacío. */
const DESCARGAS = [
  { clave: 'produccion',  etiqueta: 'Producción',  ruta: 'excel' },
  { clave: 'formularios', etiqueta: 'Formularios', ruta: 'formularios_excel' },
  { clave: 'insumos',     etiqueta: 'Insumos',     ruta: 'insumos_excel' },
];

/**
 * Rango de fechas + Cargar + las tres descargas.
 *
 * Props:
 *   desde, hasta : 'YYYY-MM-DD'
 *   onChange     : (campo, valor) => void
 *   onCargar     : () => void   — aplica el rango a todo el dashboard
 *   onDescargar  : (ruta) => void
 */
export default function FiltroFecha({ desde, hasta, onChange, onCargar, onDescargar }) {
  return (
    <div className="flex flex-wrap items-center gap-2.5">
      <input
        type="date"
        value={desde}
        max={hasta || undefined}
        onChange={(e) => onChange('desde', e.target.value)}
        aria-label="Fecha desde"
        className="sig-input font-mono text-[13px] py-1.5"
      />
      <span aria-hidden="true" className="text-sig-dim text-sm px-0.5">→</span>
      <input
        type="date"
        value={hasta}
        min={desde || undefined}
        onChange={(e) => onChange('hasta', e.target.value)}
        aria-label="Fecha hasta"
        className="sig-input font-mono text-[13px] py-1.5"
      />

      <Button onClick={onCargar}>
        <RefreshCw size={13} />
        Cargar
      </Button>

      <span aria-hidden="true" className="text-sig-dim px-1">·</span>

      {DESCARGAS.map((d) => (
        <Button key={d.clave} onClick={() => onDescargar(d.ruta)}>
          <Download size={13} />
          {d.etiqueta}
        </Button>
      ))}
    </div>
  );
}
