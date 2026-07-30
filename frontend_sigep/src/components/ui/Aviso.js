import React, { useCallback, useEffect, useRef, useState } from 'react';

/** Resultado de una acción del admin: verde si salió bien, ámbar si falló. */
export default function Aviso({ aviso, className = '' }) {
  if (!aviso) return null;
  const ok = aviso.tipo === 'ok';
  return (
    <p
      role="status"
      className={`sig-meta ${ok ? 'text-sig-ok' : 'text-sig-amber'} ${className}`}
    >
      {aviso.texto}
    </p>
  );
}

/**
 * Estado del aviso con desaparición automática. Devuelve el aviso actual y dos
 * funciones para publicarlo.
 */
export function useAviso(msDuracion = 5000) {
  const [aviso, setAviso] = useState(null);
  const temporizador = useRef(null);

  const publicar = useCallback((tipo, texto) => {
    clearTimeout(temporizador.current);
    setAviso({ tipo, texto });
    temporizador.current = setTimeout(() => setAviso(null), msDuracion);
  }, [msDuracion]);

  useEffect(() => () => clearTimeout(temporizador.current), []);

  return {
    aviso,
    ok: useCallback((texto) => publicar('ok', texto), [publicar]),
    fallo: useCallback((texto) => publicar('error', texto), [publicar]),
  };
}
