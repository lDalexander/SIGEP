import React from 'react';
import Label from './Label';

/** Etiqueta mono encima del control, como en los editores de Administración. */
export default function Campo({ etiqueta, className = '', children }) {
  return (
    <label className={`block ${className}`}>
      <Label className="block mb-1.5">{etiqueta}</Label>
      {children}
    </label>
  );
}

export function Input({ className = '', ...rest }) {
  return <input className={`sig-input w-full ${className}`} {...rest} />;
}

export function Textarea({ className = '', rows = 3, ...rest }) {
  return <textarea rows={rows} className={`sig-input w-full resize-y ${className}`} {...rest} />;
}

/**
 * Select del sistema. `opciones` es un array de strings; si el valor actual no está
 * entre ellas se añade al principio, para no perder silenciosamente un dato que ya
 * está guardado (por ejemplo una marca que se desactivó del catálogo).
 */
export function Select({ opciones = [], value = '', vacio = '—', className = '', ...rest }) {
  const lista = value && !opciones.includes(value) ? [value, ...opciones] : opciones;
  return (
    <select value={value} className={`sig-input w-full ${className}`} {...rest}>
      <option value="">{vacio}</option>
      {lista.map((op) => (
        <option key={op} value={op}>{op}</option>
      ))}
    </select>
  );
}

/**
 * Casilla ámbar. El input nativo se oculta y se dibuja la caja, porque `accent-color`
 * no permite el trazo redondeado del check de las capturas.
 */
export function Checkbox({ checked = false, onChange, etiqueta, className = '', ...rest }) {
  return (
    <label className={`inline-flex items-center gap-3 cursor-pointer group ${className}`}>
      <input
        type="checkbox"
        checked={checked}
        onChange={onChange}
        className="sr-only peer"
        {...rest}
      />
      <span
        aria-hidden="true"
        className={`flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded
                    border transition-colors peer-focus-visible:ring-2 peer-focus-visible:ring-sig-amber/50
                    ${checked
                      ? 'bg-sig-amber border-sig-amber'
                      : 'border-white/25 group-hover:border-white/40'}`}
      >
        {checked && (
          <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
            <path
              d="M2.5 6.2l2.4 2.4L9.6 3.9"
              stroke="#0A100E"
              strokeWidth="2.2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        )}
      </span>
      {etiqueta && <span className="min-w-0">{etiqueta}</span>}
    </label>
  );
}
