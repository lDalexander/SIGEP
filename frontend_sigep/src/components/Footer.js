import React from 'react';
import { Label } from './ui';
import { hora } from '../lib/format';

/**
 * Pie del dashboard. `actualizado` es la hora real del último refresco correcto;
 * si todavía no ha habido ninguno no se inventa una hora.
 */
export default function Footer({ actualizado }) {
  return (
    <footer className="mt-8 border-t border-sig-line py-5 flex flex-wrap items-center justify-between gap-2">
      <Label caja="normal" className="text-sig-dim">
        SIGEP · Centro de Control de Producción
      </Label>
      <Label caja="normal" className="text-sig-dim">
        © Admin&nbsp;&nbsp; Actualizado {actualizado ? hora(actualizado) : '—'}
      </Label>
    </footer>
  );
}
