import React from 'react';

/**
 * Marca de SIGEP: caja isométrica con una flecha ascendente, en ámbar.
 *
 * NOTA: el archivo del logo original no está en el repo (`public/logo192.png` es el
 * logo por defecto de Create React App). Este SVG lo reproduce a partir de las
 * capturas; si aparece el original, basta sustituir este componente por un <img>.
 */
export default function Logo({ tamano = 34, className = '' }) {
  return (
    <svg
      width={tamano}
      height={tamano}
      viewBox="0 0 48 48"
      fill="none"
      className={className}
      role="img"
      aria-label="SIGEP"
    >
      {/* Caja isométrica */}
      <path d="M24 6 44 16v22L24 48 4 38V16z" fill="#F5A623" fillOpacity="0.16" />
      <path d="M24 6 44 16 24 26 4 16z" fill="#F5A623" fillOpacity="0.55" />
      <path d="M4 16v22l20 10V26z" fill="#C8821A" fillOpacity="0.7" />
      <path d="M44 16v22L24 48V26z" fill="#F5A623" fillOpacity="0.9" />
      {/* Flecha ascendente */}
      <path
        d="M14 34l8-8 5 5 9-9"
        stroke="#0A100E"
        strokeWidth="3"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path d="M31 22h6v6" stroke="#0A100E" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
