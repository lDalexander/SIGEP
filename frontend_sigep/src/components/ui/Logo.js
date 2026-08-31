import React from 'react';

/* Proporción del archivo (279 × 320). Se declara aquí para reservar el hueco antes de
   que la imagen cargue: sin ancho, la cabecera da un salto al terminar de pintarse. */
const RATIO = 279 / 320;

/**
 * Marca de SIGEP: los pallets apilados con la flecha ascendente.
 *
 * Es el logo original de la empresa (`public/logo_sigep.png`), en su sitio desde el
 * 2026-08-31. Hasta entonces aquí vivía un SVG dibujado a partir de las capturas de
 * `referencia_ui/`, porque el archivo no estaba en el repo.
 *
 * `tamano` es el ALTO en píxeles y el ancho sale de la proporción: el logo es más alto
 * que ancho y forzarlo a un cuadrado lo deformaría. Se sirve desde `public/`, así que
 * no pasa por el bundle y la ruta es absoluta.
 */
export default function Logo({ tamano = 34, className = '' }) {
  return (
    <img
      src={`${process.env.PUBLIC_URL}/logo_sigep.png`}
      alt="SIGEP"
      width={Math.round(tamano * RATIO)}
      height={tamano}
      draggable="false"
      className={`select-none ${className}`}
    />
  );
}
