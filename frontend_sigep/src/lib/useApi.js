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
 */
export default function useApi(url, { params, intervalo = 0 } = {}) {
  const [datos, setDatos] = useState(null);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState(false);

  /* Los params llegan como objeto literal y cambiarían de identidad en cada render;
     se serializan para que el efecto solo se vuelva a lanzar si cambia su contenido. */
  const clave = JSON.stringify(params ?? null);
  const paramsRef = useRef(params);
  paramsRef.current = params;

  const cargar = useCallback(async () => {
    try {
      const { data } = await axios.get(url, { params: paramsRef.current, timeout: 8000 });
      setDatos(data);
      setError(false);
    } catch (err) {
      console.error(`[SIGEP] Error en ${url}:`, err.message);
      setError(true);
    } finally {
      setCargando(false);
    }
    // `clave` entra a propósito: es el disparador cuando cambian los params.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, clave]);

  useEffect(() => {
    setCargando(true);
    cargar();
    if (!intervalo) return undefined;
    const id = setInterval(cargar, intervalo);
    return () => clearInterval(id);
  }, [cargar, intervalo]);

  return { datos, cargando, error, recargar: cargar };
}
