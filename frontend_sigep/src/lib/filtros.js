/**
 * Segmentación multi-selección del dashboard.
 *
 * Las cinco dimensiones son las columnas de `sesiones` que el backend acepta como
 * filtro repetible (`?maquina=A&maquina=B`) en `kpis`, `produccion_hora`,
 * `estado_operativo` y `top_produccion`. Las claves de este archivo son a la vez el
 * nombre del query-param y la clave que devuelve `/dashboard/opciones_filtros`: son
 * iguales en la API, y aquí se aprovecha para no tener que traducir entre las dos.
 */

export const DIMENSIONES = [
  { key: 'maquina',      label: 'Máquina' },
  { key: 'operador',     label: 'Operario' },
  { key: 'marca',        label: 'Marca' },
  { key: 'presentacion', label: 'Presentación' },
  { key: 'fragancia',    label: 'Fragancia' },
];

/** Estado inicial: ninguna dimensión filtrada = «todos», que es el arranque. */
export const SIN_FILTROS = Object.freeze(
  DIMENSIONES.reduce((acc, d) => ({ ...acc, [d.key]: [] }), {}),
);

/** Cuántos valores hay seleccionados en total, para el contador de «Limpiar (n)». */
export function contarFiltros(filtros) {
  return DIMENSIONES.reduce((total, d) => total + (filtros?.[d.key]?.length || 0), 0);
}

/**
 * Las dimensiones con selección, como query-params.
 *
 * Una dimensión sin valores **no viaja**, igual que la franja horaria: así, sin
 * segmentar, la petición es exactamente la de antes de existir este filtro y no hay
 * forma de que el cambio altere lo que ya funcionaba.
 */
export function paramsDeFiltros(filtros) {
  const params = {};
  DIMENSIONES.forEach(({ key }) => {
    const valores = filtros?.[key];
    if (valores && valores.length > 0) params[key] = valores;
  });
  return params;
}

/**
 * Serializa los params con **claves repetidas** (`maquina=A&maquina=B`), que es lo que
 * FastAPI lee como `List[str]`.
 *
 * Hace falta explícitamente porque axios serializa los arrays con corchetes
 * (`maquina[]=A`), y con esa forma el backend recibe un parámetro llamado `maquina[]`
 * que no existe: ignoraría el filtro y devolvería los datos sin segmentar como si
 * estuvieran segmentados. Se pasa como `paramsSerializer` en la llamada.
 */
export function serializarParams(params) {
  const qs = new URLSearchParams();
  const anadir = (clave, valor) => {
    if (valor === null || valor === undefined || valor === '') return;
    qs.append(clave, String(valor));
  };

  Object.entries(params || {}).forEach(([clave, valor]) => {
    if (Array.isArray(valor)) valor.forEach((v) => anadir(clave, v));
    else anadir(clave, valor);
  });

  return qs.toString();
}

/**
 * Agrupación del ranking de «Estadísticas de producción» deducida de lo segmentado.
 *
 * La tarjeta ya no tiene selector propio de agrupación: tener dos controles con los
 * mismos nombres («Máquina», «Operario») a dos dedos de distancia, uno para filtrar y
 * otro para agrupar, era la parte confusa. Ahora la agrupación responde a la pregunta
 * que deja abierta el filtro: si acotas máquinas, lo que falta saber es **quién**
 * produjo en ellas; si acotas operarios, **qué** producían.
 *
 * El orden de las reglas es la precedencia, y al filtrar por máquina Y operario gana
 * el operario: con los dos ya fijados, lo único que queda por desglosar es el producto.
 *
 * Devuelve un valor de `dim` de `/dashboard/estadisticas`; nunca uno inventado, porque
 * el endpoint responde 400 a los que no conoce.
 */
export function dimAutomatica(filtros) {
  if (filtros?.operador?.length > 0) return 'marca_presentacion';
  if (filtros?.maquina?.length > 0) return 'operario';
  return 'maquina';
}

/**
 * Resumen legible de la segmentación activa, para el metadato de las tarjetas.
 * `null` cuando no hay nada seleccionado — el llamador decide qué poner en su lugar.
 */
export function resumenFiltros(filtros) {
  const partes = DIMENSIONES
    .filter(({ key }) => filtros?.[key]?.length > 0)
    .map(({ key, label }) => {
      const valores = filtros[key];
      /* Con un solo valor se nombra: «Máquina 3» dice más que «1 máquina». */
      return valores.length === 1
        ? valores[0]
        : `${valores.length} ${label.toLowerCase()}s`;
    });

  return partes.length > 0 ? partes.join(' · ') : null;
}
