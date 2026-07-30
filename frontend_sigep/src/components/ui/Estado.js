import React from 'react';
import Label from './Label';

/**
 * Estados de carga / error / vacío de una tarjeta, centrados y tenues.
 * Existe para que las tres situaciones se vean igual en todo el dashboard y para
 * no inventarse nunca un dato de relleno cuando la API no responde.
 *
 * Props:
 *   cargando : muestra el esqueleto de carga
 *   error    : muestra el aviso de fallo (conservando el dato viejo si lo hay)
 *   vacio    : texto del estado vacío, p.ej. «Sin solicitudes en las últimas 24h»
 */
export default function Estado({ cargando, error, vacio, className = '' }) {
  let texto = vacio;
  if (cargando) texto = 'Cargando…';
  else if (error) texto = 'Sin conexión con el servidor';

  return (
    <div className={`flex items-center justify-center py-10 text-center ${className}`}>
      <Label caja="normal" className={error ? 'text-sig-amber/70' : 'text-sig-dim'}>
        {texto}
      </Label>
    </div>
  );
}

/** Bloque gris animado para ocupar el hueco de una cifra mientras carga. */
export function Esqueleto({ className = 'h-10 w-24' }) {
  return (
    <div
      className={`rounded-md bg-gradient-to-r from-white/[0.04] via-white/[0.08] to-white/[0.04]
                  bg-[length:200%_100%] animate-shimmer ${className}`}
    />
  );
}
