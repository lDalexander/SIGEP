import React from 'react';
import Label from './Label';

/**
 * Tarjeta del dashboard: título en sans bold a la izquierda y un metadato mono
 * atenuado a la derecha («7 sesiones · hoy», «8 recientes», «hoy»).
 *
 * Props:
 *   titulo   : texto del encabezado (si se omite, la tarjeta va sin cabecera)
 *   meta     : nodo o string que se alinea a la derecha del título
 *   sinPad   : desactiva el padding del cuerpo (para tablas a sangre)
 *   className/bodyClassName
 */
export default function Card({
  titulo,
  meta,
  sinPad = false,
  className = '',
  bodyClassName = '',
  children,
}) {
  return (
    /* El título nombra la región: así cada tarjeta es localizable por su nombre
       accesible, tanto para un lector de pantalla como para los tests. */
    <section
      aria-label={typeof titulo === 'string' ? titulo : undefined}
      className={`sig-card animate-fade-in overflow-hidden ${className}`}
    >
      {(titulo || meta) && (
        <header className="flex items-center justify-between gap-3 px-5 pt-4 pb-3">
          {titulo && (
            <h2 className="text-[15px] font-bold text-sig-text leading-tight">{titulo}</h2>
          )}
          {meta && (typeof meta === 'string'
            ? <Label caja="normal" className="shrink-0">{meta}</Label>
            : <div className="shrink-0">{meta}</div>
          )}
        </header>
      )}
      <div className={sinPad ? bodyClassName : `px-5 pb-5 ${bodyClassName}`}>
        {children}
      </div>
    </section>
  );
}
