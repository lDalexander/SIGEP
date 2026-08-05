import React from 'react';
import { RefreshCw, Download, X } from 'lucide-react';
import { Button, Label } from './ui';

/* Los tres reportes descargables. `ruta` es el endpoint de exportación a Excel;
   todos aceptan ?desde&hasta y responden 404 si el rango está vacío.
   Nota: los endpoints de reportes NO aceptan franja horaria, así que el Excel sale
   con el día completo aunque el dashboard esté filtrado por horas. */
const DESCARGAS = [
  { clave: 'produccion',  etiqueta: 'Producción',  ruta: 'excel' },
  { clave: 'formularios', etiqueta: 'Formularios', ruta: 'formularios_excel' },
  { clave: 'insumos',     etiqueta: 'Insumos',     ruta: 'insumos_excel' },
];

/**
 * Rango de fechas + franja de horas + Cargar + las tres descargas.
 *
 * La franja horaria es opcional: vacía significa el día completo. Se permite a
 * propósito que la hora de inicio sea mayor que la de fin, porque el turno de noche
 * cruza medianoche (19:00 → 07:00); el backend lo interpreta así, de modo que los
 * inputs no llevan `min`/`max` cruzados como sí lo llevan los de fecha.
 *
 * Props:
 *   desde, hasta           : 'YYYY-MM-DD'
 *   horaDesde, horaHasta   : 'HH:MM' o '' (= sin límite por ese lado)
 *   onChange               : (campo, valor) => void  — campo ∈ desde|hasta|horaDesde|horaHasta
 *   onCargar               : () => void   — aplica fechas y horas a todo el dashboard
 *   onLimpiarHoras         : () => void
 *   onDescargar            : (ruta) => void
 *   descargas              : muestra los tres botones de Excel (la vista de paros no
 *                            tiene reporte que descargar, así que los oculta)
 *   avisoFranja            : reemplaza el texto de advertencia sobre el alcance de la
 *                            franja; cada vista sabe a qué afecta la suya
 */
export default function FiltroFecha({
  desde, hasta, horaDesde = '', horaHasta = '',
  onChange, onCargar, onLimpiarHoras, onDescargar,
  descargas = true,
  avisoFranja,
}) {
  const hayFranja = Boolean(horaDesde || horaHasta);

  return (
    <div className="flex flex-col items-end gap-2.5">
      {/* Fila 1 — fechas, Cargar y descargas */}
      <div className="flex flex-wrap items-center justify-end gap-2.5">
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

        {descargas && (
          <>
            <span aria-hidden="true" className="text-sig-dim px-1">·</span>

            {DESCARGAS.map((d) => (
              <Button key={d.clave} onClick={() => onDescargar(d.ruta)}>
                <Download size={13} />
                {d.etiqueta}
              </Button>
            ))}
          </>
        )}
      </div>

      {/* Fila 2 — franja de horas. Se aplica con el mismo botón «Cargar». */}
      <div className="flex flex-wrap items-center justify-end gap-2.5">
        <Label>Franja horaria</Label>
        <input
          type="time"
          value={horaDesde}
          onChange={(e) => onChange('horaDesde', e.target.value)}
          aria-label="Hora desde"
          className="sig-input font-mono text-[13px] py-1.5"
        />
        <span aria-hidden="true" className="text-sig-dim text-sm px-0.5">→</span>
        <input
          type="time"
          value={horaHasta}
          onChange={(e) => onChange('horaHasta', e.target.value)}
          aria-label="Hora hasta"
          className="sig-input font-mono text-[13px] py-1.5"
        />

        {hayFranja ? (
          <Button onClick={onLimpiarHoras}>
            <X size={13} />
            Todo el día
          </Button>
        ) : (
          <Label caja="normal" className="text-sig-dim">día completo</Label>
        )}
      </div>

      {/* La franja afecta a producción, pero ni a los Excel ni a checklists/insumos:
          se dice explícitamente en vez de dejar que se deduzca de las cifras. */}
      {hayFranja && (
        <Label caja="normal" className="text-sig-dim text-right">
          {avisoFranja ||
            'la franja afecta a producción; los Excel y las tarjetas de checklists e insumos salen con el día completo'}
        </Label>
      )}
    </div>
  );
}
