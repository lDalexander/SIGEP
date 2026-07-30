import React from 'react';

/**
 * Etiqueta monoespaciada del sistema: MAYÚSCULAS, 11px, tracking amplio, atenuada.
 * Es el recurso más repetido del diseño — todo metadato pasa por aquí.
 *
 * Props:
 *   as    : etiqueta HTML a renderizar (default 'span')
 *   caja  : 'alta' (default, fuerza mayúsculas) | 'normal' (respeta el texto original,
 *           para metadatos como «9 checklists» o «últimas 24h»)
 */
export default function Label({ as: Tag = 'span', caja = 'alta', className = '', children, ...rest }) {
  const base = caja === 'alta' ? 'sig-label' : 'sig-meta';
  return (
    <Tag className={`${base} ${className}`} {...rest}>
      {children}
    </Tag>
  );
}
