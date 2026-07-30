import React from 'react';

/* Primario = fondo ámbar con texto oscuro bold. Secundario = transparente con borde.
   `Eliminar` usa el secundario, sin rojo: así está en las capturas. */
const VARIANTES = {
  primary: `bg-sig-amber text-sig-bg font-bold border border-sig-amber
            hover:bg-sig-amber/90 disabled:bg-sig-amber/25 disabled:text-sig-bg/50
            disabled:border-sig-amber/25`,
  secondary: `bg-transparent text-sig-text font-semibold border border-sig-line
              hover:border-white/20 hover:bg-white/[0.03] disabled:text-sig-dim`,
};

const TAMANOS = {
  sm: 'px-3 py-1.5 text-[12px]',
  md: 'px-4 py-2 text-[13px]',
};

export default function Button({
  variante = 'secondary',
  tamano = 'md',
  className = '',
  type = 'button',
  disabled = false,
  children,
  ...rest
}) {
  return (
    <button
      type={type}
      disabled={disabled}
      className={`inline-flex items-center justify-center gap-2 rounded-lg
                  transition-colors duration-150 disabled:cursor-not-allowed
                  ${VARIANTES[variante] || VARIANTES.secondary} ${TAMANOS[tamano]} ${className}`}
      {...rest}
    >
      {children}
    </button>
  );
}
