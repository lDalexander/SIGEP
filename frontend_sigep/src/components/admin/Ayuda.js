import React from 'react';
import { Label, Button } from '../ui';

/**
 * Línea de ayuda en mono tenue con la que abre cada pestaña, y el botón «Recargar»
 * a la derecha cuando la pestaña lo tiene.
 */
export default function Ayuda({ texto, onRecargar }) {
  return (
    <div className="flex items-start justify-between gap-4 mb-5">
      <Label caja="normal" className="text-sig-dim">{texto}</Label>
      {onRecargar && (
        <Button onClick={onRecargar} className="shrink-0">Recargar</Button>
      )}
    </div>
  );
}
