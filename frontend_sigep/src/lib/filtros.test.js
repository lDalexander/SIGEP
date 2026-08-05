import {
  DIMENSIONES, SIN_FILTROS, contarFiltros, paramsDeFiltros, serializarParams, resumenFiltros,
} from './filtros';

/* Estas cinco claves son a la vez el query-param del backend y la clave que devuelve
   /dashboard/opciones_filtros. Si alguna se renombra, el filtro deja de aplicarse en
   silencio: la API ignora los parámetros que no conoce. */
test('las dimensiones son exactamente las que acepta el backend', () => {
  expect(DIMENSIONES.map((d) => d.key))
    .toEqual(['maquina', 'operador', 'marca', 'presentacion', 'fragancia']);
});

test('sin selección no se envía ningún parámetro de filtro', () => {
  expect(paramsDeFiltros(SIN_FILTROS)).toEqual({});
  expect(contarFiltros(SIN_FILTROS)).toBe(0);
});

test('una dimensión vacía no viaja, aunque otras estén seleccionadas', () => {
  const params = paramsDeFiltros({ ...SIN_FILTROS, maquina: ['Máquina 7'], marca: [] });
  expect(params).toEqual({ maquina: ['Máquina 7'] });
  expect(params).not.toHaveProperty('marca');
});

test('contarFiltros suma los valores de todas las dimensiones', () => {
  expect(contarFiltros({ ...SIN_FILTROS, maquina: ['A', 'B'], operador: ['X'] })).toBe(3);
});

/* El punto crítico: FastAPI lee `List[str]` de claves repetidas. Con la forma que usa
   axios por defecto (`maquina[]=A`) el backend recibiría un parámetro desconocido, lo
   ignoraría, y la web mostraría datos sin segmentar como si estuvieran segmentados. */
test('las listas se serializan como claves repetidas, sin corchetes', () => {
  const qs = serializarParams({ desde: '2026-08-05', maquina: ['Máquina 7', 'Máquina 9'] });

  /* La clave se repite tal cual; el espacio va como «+», que es la codificación de
     formulario que starlette decodifica de vuelta a espacio. */
  expect(qs).toBe('desde=2026-08-05&maquina=M%C3%A1quina+7&maquina=M%C3%A1quina+9');
  expect(qs).not.toContain('%5B%5D');
  expect(new URLSearchParams(qs).getAll('maquina')).toEqual(['Máquina 7', 'Máquina 9']);
});

test('los valores con espacios y acentos se codifican para la URL', () => {
  const qs = serializarParams({ operador: ['JUAN PÉREZ'] });
  // Espacio como «+» y la É percent-encoded: así lo decodifica starlette.
  expect(qs).toBe('operador=JUAN+P%C3%89REZ');
});

test('los valores vacíos o nulos no ensucian la query', () => {
  expect(serializarParams({ desde: '2026-08-05', hora_desde: '', hora_hasta: null, maquina: [] }))
    .toBe('desde=2026-08-05');
});

/* Con un solo valor se nombra («Máquina 7» dice más que «1 máquina»); con varios se
   cuenta, porque la lista entera no cabe en el metadato de una tarjeta. */
test('el resumen nombra un valor único y cuenta los múltiples', () => {
  expect(resumenFiltros(SIN_FILTROS)).toBeNull();
  expect(resumenFiltros({ ...SIN_FILTROS, maquina: ['Máquina 7'] })).toBe('Máquina 7');
  expect(resumenFiltros({ ...SIN_FILTROS, operador: ['A', 'B'] })).toBe('2 operarios');
  expect(resumenFiltros({ ...SIN_FILTROS, maquina: ['Máquina 7'], marca: ['X', 'Y'] }))
    .toBe('Máquina 7 · 2 marcas');
});
