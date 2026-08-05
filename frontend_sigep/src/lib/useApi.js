import { useCallback, useEffect, useRef, useState } from 'react';
import axios from 'axios';

/**
 * GET a la API con polling opcional.
 *
 * Conserva el último dato bueno cuando una petición falla: en el centro de control
 * es mejor una cifra de hace 15 segundos que un hueco vacío. Nunca devuelve datos
 * de relleno.
 *
 * @param {string} url      ruta completa, p.ej. '/api/mantenimiento/checklist'
 * @param {object} params   query params (se comparan por valor, no por identidad)
 * @param {number} intervalo ms entre refrescos; 0 o ausente = una sola carga
 * @param {object} cliente  instancia de axios; por defecto la global. La zona de
 *                          administración pasa la de `lib/adminApi` para que la
 *                          petición lleve la cabecera X-Admin-Token.
 * @param {function} serializar  serializador de query params. Hace falta cuando algún
 *                          param es una lista: el de axios la manda como `clave[]=v`
 *                          y FastAPI espera la clave repetida (`lib/filtros.js`).
 */
export default function useApi(url, {
  params, intervalo = 0, cliente = axios, serializar,
} = {}) {
  const [datos, setDatos] = useState(null);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState(false);

  /* Los params llegan como objeto literal y cambiarían de identidad en cada render;
     se serializan para que el efecto solo se vuelva a lanzar si cambia su contenido. */
  const clave = JSON.stringify(params ?? null);
  const paramsRef = useRef(params);
  paramsRef.current = params;

  /* Evita tocar el estado de un componente ya desmontado: al cambiar de pestaña en
     Administración quedan peticiones en vuelo. */
  const montado = useRef(true);
  useEffect(() => {
    montado.current = true;
    return () => { montado.current = false; };
  }, []);

  const cargar = useCallback(async () => {
    try {
      const { data } = await cliente.get(url, {
        params: paramsRef.current,
        timeout: 8000,
        ...(serializar ? { paramsSerializer: { serialize: serializar } } : {}),
      });
      if (!montado.current) return;
      setDatos(data);
      setError(false);
    } catch (err) {
      console.error(`[SIGEP] Error en ${url}:`, err.message);
      if (montado.current) setError(true);
    } finally {
      if (montado.current) setCargando(false);
    }
    // `clave` entra a propósito: es el disparador cuando cambian los params.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, clave, cliente, serializar]);

  useEffect(() => {
    setCargando(true);
    cargar();
    if (!intervalo) return undefined;
    const id = setInterval(cargar, intervalo);
    return () => clearInterval(id);
  }, [cargar, intervalo]);

  return { datos, cargando, error, recargar: cargar };
}
