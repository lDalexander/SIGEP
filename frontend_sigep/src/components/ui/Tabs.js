import React from 'react';

/**
 * Dos variantes, las dos presentes en las capturas:
 *   'pill'      — pestañas de «Estadísticas de producción»: la activa lleva fondo
 *                 ámbar y texto oscuro.
 *   'underline' — pestañas de Administración: la activa va en ámbar con un
 *                 subrayado de 2px, y una línea de borde recorre toda la fila.
 *
 * Props:
 *   items    : [{ value, label, disabled?, title? }]
 *   value    : value activo
 *   onChange : (value) => void
 */
export default function Tabs({ items, value, onChange, variante = 'pill', className = '' }) {
  if (variante === 'underline') {
    return (
      <div className={`flex items-center gap-6 border-b border-sig-line ${className}`} role="tablist">
        {items.map((it) => {
          const activo = it.value === value;
          return (
            <button
              key={it.value}
              type="button"
              role="tab"
              aria-selected={activo}
              onClick={() => onChange(it.value)}
              className={`relative -mb-px pb-2.5 pt-1 text-[13px] font-semibold transition-colors
                ${activo ? 'text-sig-amber' : 'text-sig-muted hover:text-sig-text'}`}
            >
              {it.label}
              {activo && <span className="absolute inset-x-0 -bottom-px h-[2px] bg-sig-amber" />}
            </button>
          );
        })}
      </div>
    );
  }

  return (
    <div className={`inline-flex items-center gap-1 ${className}`} role="tablist">
      {items.map((it) => {
        const activo = it.value === value;
        return (
          <button
            key={it.value}
            type="button"
            role="tab"
            aria-selected={activo}
            disabled={it.disabled}
            title={it.title}
            onClick={() => onChange(it.value)}
            className={`rounded-lg px-3 py-1.5 text-[12px] font-semibold transition-colors whitespace-nowrap
              ${activo
                ? 'bg-sig-amber text-sig-bg'
                : 'text-sig-muted hover:text-sig-text hover:bg-white/[0.04]'}
              ${it.disabled ? 'opacity-40 cursor-not-allowed hover:bg-transparent hover:text-sig-muted' : ''}`}
          >
            {it.label}
          </button>
        );
      })}
    </div>
  );
}
