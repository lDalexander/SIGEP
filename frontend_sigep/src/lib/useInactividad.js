import { useEffect, useRef } from 'react';

/* Eventos que cuentan como «hay alguien delante». No se escucha `mousemove`: un
   ratón rozado por una vibración de planta no debería renovar una sesión, y además
   dispararía cientos de veces por segundo. */
const EVENTOS = ['mousedown', 'keydown', 'touchstart', 'wheel'];

/* Cada cuánto se comprueba el reloj. No hace falta afinar más: el backend caduca el
   token por su cuenta, esto solo evita dejar el panel abierto y utilizable. */
const PASO_MS = 15000;

/**
 * Cierra por inactividad: llama a `alVencer` cuando pasa `limiteMs` sin que el
 * usuario toque nada.
 *
 * Se mide con la diferencia entre dos lecturas del MISMO reloj (`Date.now()`), no
 * comparando contra una hora del servidor: así un equipo con la hora mal puesta no
 * adelanta ni retrasa el cierre. Si el portátil se suspende, al despertar la
 * diferencia ya supera el límite y la sesión se cierra, que es lo correcto.
 *
 * @param {object}   opciones
 * @param {boolean}  opciones.activo    solo cuenta mientras hay sesión abierta
 * @param {number}   opciones.limiteMs  inactividad tolerada
 * @param {function} opciones.alVencer  qué hacer al cumplirse (cerrar sesión)
 */
export default function useInactividad({ activo, limiteMs, alVencer }) {
  /* En refs y no en estado: renovar el reloj no debe repintar nada. */
  const ultima = useRef(Date.now());
  const vencido = useRef(false);
  const callback = useRef(alVencer);
  callback.current = alVencer;

  useEffect(() => {
    if (!activo || !limiteMs) return undefined;

    ultima.current = Date.now();
    vencido.current = false;

    const tocar = () => { ultima.current = Date.now(); };
    EVENTOS.forEach((e) => window.addEventListener(e, tocar, { passive: true }));

    const id = setInterval(() => {
      if (vencido.current) return;
      if (Date.now() - ultima.current < limiteMs) return;
      /* Una sola vez: `alVencer` es asíncrono (revoca el token en el servidor) y el
         intervalo seguiría corriendo hasta que el efecto se limpie. */
      vencido.current = true;
      callback.current();
    }, PASO_MS);

    return () => {
      clearInterval(id);
      EVENTOS.forEach((e) => window.removeEventListener(e, tocar));
    };
  }, [activo, limiteMs]);
}
