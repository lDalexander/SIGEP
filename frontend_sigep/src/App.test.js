import { render, screen, within, fireEvent } from '@testing-library/react';
import axios from 'axios';
import App from './App';

/* El automock no basta: `lib/adminApi` llama a axios.create() al importarse (App
   incluye la zona de administración), así que la instancia debe traer interceptors. */
jest.mock('axios', () => {
  const instancia = {
    get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn(),
    interceptors: { request: { use: jest.fn() }, response: { use: jest.fn() } },
  };
  return {
    get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn(),
    create: jest.fn(() => instancia),
  };
});

/* Respuestas mínimas con la forma real de cada endpoint (ver CLAUDE.md §3).
   El objetivo es comprobar que el árbol monta y pinta los datos, no la API. */
const RESPUESTAS = {
  '/api/dashboard/kpis': {
    pallets_hoy: 2372, pacas_hoy: 2272, sacos_hoy: 100,
    turnos_activos: 5, eficiencia: '94.8%',
  },
  '/api/dashboard/logs': [
    { hora: '10:40:59', mensaje: 'PALLET REGISTRADO: 50 pacas — Máquina 7B (PEDRO SARABIA)', tipo: 'pallet' },
  ],
  '/api/dashboard/produccion_hora': [
    { hora: '08:00', pallets: 150, detalle: [{ maquina: 'Máquina 7', operario: 'X', producto: 'ULTREX', pacas: 150 }] },
  ],
  '/api/dashboard/estado_operativo': [
    { sesion_id: 376, maquina: 'Máquina 7', operador: 'ANTHONY MERCADO', producto: 'ULTREX - Floral - 1 KG',
      inicio_turno: '10:19:45', tiempo_transcurrido: 288, total_pacas: 300, estado: 'Activo' },
    { sesion_id: 375, maquina: 'Máquina 9', operador: 'ALEX VALENZUELA', producto: 'TORBELLINO - Floral - 1 KG',
      inicio_turno: '09:19:11', tiempo_transcurrido: 50, total_pacas: 150, estado: 'Finalizado' },
  ],
  '/api/dashboard/top_produccion': [{ name: 'TORBELLINO', value: 1547 }],
  '/api/dashboard/estadisticas': {
    dim: 'maquina', rango: 'hoy', total_pacas: 1063, total_sesiones: 7,
    items: [{ etiqueta: 'Máquina 7', pacas: 300, sesiones: 1, pct: 28.2 }],
  },
  '/api/tablets/estado': [
    { device_id: 'eb48b8b9-c17e-4acb', nombre: 'JONATHAN VICUÑA', maquina: 'Máquina 16',
      pendientes: 0, en_linea: false, segundos_desde_heartbeat: 1860 },
  ],
  '/api/insumos/dashboard': {
    rango: { desde: '2026-07-30', hasta: '2026-07-30' },
    kpis: { total_pedidos: 0 }, pedidos: [], entregas: [],
  },
  '/api/dashboard/comentarios_turno': [
    { id: 12, sesion_id: 368, maquina: 'Máquina 7', operador: 'CRISTHIAN CEDEÑO',
      texto: 'Problemas con el sello del vertical', creado_en: '2026-07-29 20:58:08',
      fecha: '2026-07-29', hora: '20:58:08' },
  ],
  '/api/dashboard/paros': {
    kpis: {
      total_paros: 3, en_curso: 1, sin_cierre: 1, segundos_total: 55800.0,
      segundos_promedio: 18600.0, maquinas_paradas: 1, maquinas_produciendo: 1,
    },
    maquinas: [
      { maquina: 'Máquina 7', tipo: 'SOLIDO', estado: 'PARO', sesion_id: 420,
        operador: 'ANTHONY MERCADO', inicio_turno: '2026-08-05 07:20:59',
        paro_actual: { id: 108, categoria: 'MANTENIMIENTO', comentario: 'cambio de teflón',
          motivo: '[Mantenimiento] - cambio de teflón', inicio: '2026-08-05 10:00:00',
          duracion_segundos: 600 },
        paros: 2, segundos: 7200 },
      { maquina: 'Máquina 9', tipo: 'SOLIDO', estado: 'PRODUCIENDO', sesion_id: 421,
        operador: 'ROSENDO VALENZUELA', inicio_turno: '2026-08-05 07:05:00',
        paro_actual: null, paros: 0, segundos: 0 },
      { maquina: 'Máquina 16', tipo: 'SOLIDO', estado: 'SIN TURNO', sesion_id: null,
        operador: '—', inicio_turno: null, paro_actual: null, paros: 1, segundos: 48654 },
    ],
    paros: [
      { id: 108, sesion_id: 420, maquina: 'Máquina 7', operador: 'ANTHONY MERCADO',
        producto: 'ULTREX · 1 KG · Floral', categoria: 'MANTENIMIENTO',
        comentario: 'cambio de teflón', motivo: '[Mantenimiento] - cambio de teflón',
        inicio: '2026-08-05 10:00:00', fin: null, fin_estimado: null, estado: 'EN CURSO',
        en_curso: true, duracion_segundos: 600, duracion_estimada: false,
        inicio_turno: '2026-08-05 07:20:59', fin_turno: null },
      { id: 107, sesion_id: 419, maquina: 'Máquina 7', operador: 'PEDRO SARABIA',
        producto: 'TORBELLINO · 1 KG · Floral', categoria: 'ALMUERZO', comentario: null,
        motivo: 'ALMUERZO', inicio: '2026-08-05 12:00:00', fin: '2026-08-05 12:15:00',
        fin_estimado: null, estado: 'CERRADO', en_curso: false, duracion_segundos: 900,
        duracion_estimada: false, inicio_turno: '2026-08-05 07:20:59',
        fin_turno: '2026-08-05 16:00:00' },
      { id: 105, sesion_id: 416, maquina: 'Máquina 16', operador: 'ROLANDO MORAN',
        producto: 'ULTREX · 1 KG · Floral', categoria: 'BODEGA', comentario: 'producto',
        motivo: '[Bodega] - producto', inicio: '2026-08-04 19:22:39', fin: null,
        fin_estimado: '2026-08-05 08:53:33', estado: 'SIN CIERRE', en_curso: false,
        duracion_segundos: 48654, duracion_estimada: true,
        inicio_turno: '2026-08-04 19:22:28', fin_turno: '2026-08-05 08:53:33' },
    ],
    por_categoria: [
      { categoria: 'BODEGA', paros: 1, segundos: 48654 },
      { categoria: 'ALMUERZO', paros: 1, segundos: 900 },
      { categoria: 'MANTENIMIENTO', paros: 1, segundos: 600 },
    ],
  },
};

/* Los ítems llegan de la BD en caja mixta; la UI los pone en mayúsculas por CSS. */
const ITEMS = [
  'Revisión de conexión de cables de mordaza',
  'Revisión de presión de aire comprimido',
];

/* El checklist se pide dos veces al mismo endpoint con params distintos: `limit`
   para las tarjetas recientes y `desde`/`hasta` para la tabla de detalle. */
const CHECKLIST_RECIENTES = [
  { id: 222, maquina: 'Máquina 8', operador: 'KEVIN SORIANO', momento: 'ENTRADA',
    codigo_turno: 'DIA', fecha_turno: '2026-07-23', hora: '11:19', supervisor: 'Emilio',
    comentarios: null, total_items: 6, items_ok: 6,
    items: ITEMS.map((etiqueta) => ({ etiqueta, marcado: true })) },
  { id: 219, maquina: 'Máquina 9', operador: 'ROSENDO VALENZUELA', momento: 'SALIDA',
    codigo_turno: 'DIA', fecha_turno: '2026-07-23', hora: '09:33', supervisor: 'Maria',
    comentarios: 'Seguimos con la dosificacion', total_items: 6, items_ok: 1,
    items: ITEMS.map((etiqueta, i) => ({ etiqueta, marcado: i === 0 })) },
];

beforeEach(() => {
  /* jsdom conserva la URL entre tests del mismo archivo, y la vista se decide por la
     ruta: sin este reset, todo lo que corre después de un test que entra en /paros
     arrancaría ya dentro de esa vista. */
  window.history.pushState({}, '', '/');

  axios.get.mockImplementation((url, config) => {
    if (url.startsWith('/api/mantenimiento/checklist')) {
      // `limit` -> tarjetas recientes; `desde`/`hasta` -> tabla de detalle.
      return Promise.resolve({ data: CHECKLIST_RECIENTES });
    }
    const clave = Object.keys(RESPUESTAS).find((k) => url.startsWith(k));
    return clave
      ? Promise.resolve({ data: RESPUESTAS[clave] })
      : Promise.reject(new Error(`sin mock para ${url} (${JSON.stringify(config?.params)})`));
  });
});

afterEach(() => jest.clearAllMocks());

test('el dashboard monta y muestra los KPI con formato es-EC', async () => {
  render(<App />);

  expect(screen.getByRole('heading', { name: /producción en tiempo real/i })).toBeInTheDocument();

  // 2272 -> "2.272" con el separador de miles de es-EC.
  expect(await screen.findByText('2.272')).toBeInTheDocument();
  expect(screen.getByText('100')).toBeInTheDocument();
});

test('las sesiones se listan con su estado y duración', async () => {
  render(<App />);

  expect(await screen.findByText('ANTHONY MERCADO')).toBeInTheDocument();
  // 288 minutos -> "4h 48m"
  expect(screen.getByText(/Activo · 4h 48m/i)).toBeInTheDocument();
  expect(screen.getByText(/^Finalizado$/i)).toBeInTheDocument();
});

test('el ranking de estadísticas muestra porcentaje con un decimal', async () => {
  render(<App />);
  expect(await screen.findByText(/1 sesión · 28\.2%/)).toBeInTheDocument();
});

test('las tablets muestran su antigüedad de contacto', async () => {
  render(<App />);
  // 1860 s -> "31m"
  expect(await screen.findByText('31m')).toBeInTheDocument();
  expect(screen.getByText('0/1 en línea')).toBeInTheDocument();
});

test('las tarjetas de checklist muestran el anillo, el momento y el supervisor', async () => {
  render(<App />);

  // El anillo lleva la fracción dentro y una etiqueta accesible.
  expect(await screen.findByLabelText('6 de 6 ítems marcados')).toBeInTheDocument();
  expect(screen.getByLabelText('1 de 6 ítems marcados')).toBeInTheDocument();

  expect(screen.getAllByText('ENTRADA').length).toBeGreaterThan(0);
  expect(screen.getAllByText('SALIDA').length).toBeGreaterThan(0);

  // Supervisor y comentario entre comillas.
  expect(screen.getAllByText('Emilio').length).toBeGreaterThan(0);
  expect(screen.getByText('“Seguimos con la dosificacion”')).toBeInTheDocument();
});

test('el detalle de checklist genera una columna por ítem, con X y –', async () => {
  render(<App />);

  const tabla = await screen.findByRole('table');
  const cabeceras = within(tabla).getAllByRole('columnheader').map((th) => th.textContent);

  // Las 8 columnas fijas, en el orden del Excel de formularios, y luego los ítems.
  expect(cabeceras.slice(0, 8)).toEqual([
    'ID', 'Fecha turno', 'Turno', 'Máquina', 'Operador', 'Supervisor', 'Momento', 'Hora',
  ]);
  ITEMS.forEach((etiqueta) => expect(cabeceras).toContain(etiqueta));

  // Un ítem marcado sale como «X» y uno sin marcar como «–».
  expect(within(tabla).getAllByTitle('marcado').length).toBe(3);
  expect(within(tabla).getAllByTitle('sin marcar').length).toBe(1);
});

test('sin solicitudes de insumos se muestra el estado vacío de las capturas', async () => {
  render(<App />);
  expect(await screen.findByText('Sin solicitudes en las últimas 24h')).toBeInTheDocument();
});

test('un endpoint caído no arrastra a las demás tarjetas', async () => {
  // Solo falla la actividad en vivo; el resto debe seguir mostrando sus datos.
  axios.get.mockImplementation((url) => {
    if (url.startsWith('/api/dashboard/logs')) return Promise.reject(new Error('boom'));
    if (url.startsWith('/api/mantenimiento/checklist')) {
      return Promise.resolve({ data: CHECKLIST_RECIENTES });
    }
    const clave = Object.keys(RESPUESTAS).find((k) => url.startsWith(k));
    return clave ? Promise.resolve({ data: RESPUESTAS[clave] }) : Promise.reject(new Error('nope'));
  });

  render(<App />);

  // La tarjeta caída avisa…
  expect(await screen.findByText('Sin conexión con el servidor')).toBeInTheDocument();
  // …y las demás siguen con sus datos, sin aviso de desconexión propio.
  expect(screen.getByText('2.272')).toBeInTheDocument();
  expect(screen.getByText('ANTHONY MERCADO')).toBeInTheDocument();
  expect(screen.queryByText(/se muestran los últimos datos recibidos/i)).not.toBeInTheDocument();
});

test('si la API falla no se inventan datos y se avisa de la desconexión', async () => {
  axios.get.mockRejectedValue(new Error('network down'));
  render(<App />);

  expect(
    await screen.findByText(/se muestran los últimos datos recibidos/i)
  ).toBeInTheDocument();
  // Ningún número inventado en las tarjetas KPI.
  expect(screen.queryByText('2.272')).not.toBeInTheDocument();
});

/* ── Agrupación del gráfico de producción ─────────────────────────────────
   El endpoint suma la misma hora de todos los días del rango, así que en un
   rango largo la serie por hora no es una línea de tiempo. La UI cambia sola. */

test('con el rango en hoy la serie va por hora y no envía `agrupar`', async () => {
  render(<App />);

  expect(await screen.findByText('Producción por hora · pacas')).toBeInTheDocument();

  const llamada = axios.get.mock.calls.find(([url]) =>
    url.startsWith('/api/dashboard/produccion_hora'));
  expect(llamada[1].params).not.toHaveProperty('agrupar');

  // Un solo día no da serie diaria: el toggle deja DÍA fuera de alcance.
  expect(screen.getByRole('tab', { name: 'DÍA' })).toBeDisabled();
});

test('un rango de varios días pasa el gráfico a por día y pide agrupar=dia', async () => {
  render(<App />);
  await screen.findByText('Producción por hora · pacas');

  axios.get.mockImplementation((url) => {
    if (url.startsWith('/api/dashboard/produccion_hora')) {
      return Promise.resolve({
        data: [
          { hora: '2026-07-29', pallets: 900, detalle: [] },
          { hora: '2026-07-30', pallets: 1200, detalle: [] },
        ],
      });
    }
    if (url.startsWith('/api/mantenimiento/checklist')) {
      return Promise.resolve({ data: CHECKLIST_RECIENTES });
    }
    const clave = Object.keys(RESPUESTAS).find((k) => url.startsWith(k));
    return clave ? Promise.resolve({ data: RESPUESTAS[clave] }) : Promise.reject(new Error(url));
  });

  fireEvent.change(screen.getByLabelText('Fecha desde'), { target: { value: '2026-07-29' } });
  fireEvent.change(screen.getByLabelText('Fecha hasta'), { target: { value: '2026-07-30' } });
  fireEvent.click(screen.getByRole('button', { name: /cargar/i }));

  expect(await screen.findByText('Producción por día · pacas')).toBeInTheDocument();

  const llamada = axios.get.mock.calls
    .filter(([url]) => url.startsWith('/api/dashboard/produccion_hora')).pop();
  expect(llamada[1].params).toMatchObject({ desde: '2026-07-29', hasta: '2026-07-30', agrupar: 'dia' });

  // Y se puede volver a la vista por hora a mano, que ya sí está habilitada.
  fireEvent.click(screen.getByRole('tab', { name: 'HORA' }));
  expect(await screen.findByText('Producción por hora · pacas')).toBeInTheDocument();
});

/* ── Franja horaria ───────────────────────────────────────────────────────
   Los turnos de la planta no coinciden con el día natural (el de noche cruza
   medianoche), así que el rango de fechas se complementa con un rango de horas.
   Los parámetros son opcionales en la API: sin franja no deben viajar. */

/** Últimos params con los que se llamó a un endpoint del dashboard. */
const paramsDe = (ruta) =>
  axios.get.mock.calls.filter(([url]) => url.startsWith(`/api/dashboard/${ruta}`)).pop()[1].params;

const ENDPOINTS_CON_FRANJA = ['kpis', 'produccion_hora', 'estado_operativo', 'top_produccion', 'estadisticas'];

test('sin franja horaria no se envían hora_desde ni hora_hasta', async () => {
  render(<App />);
  await screen.findByText('2.272');

  ENDPOINTS_CON_FRANJA.forEach((ruta) => {
    expect(paramsDe(ruta)).not.toHaveProperty('hora_desde');
    expect(paramsDe(ruta)).not.toHaveProperty('hora_hasta');
  });
  expect(screen.getByText('día completo')).toBeInTheDocument();
});

test('la franja del turno de noche se envía a los endpoints de producción', async () => {
  render(<App />);
  await screen.findByText('2.272');

  // 19:00 → 07:00 cruza medianoche: se manda tal cual, el backend lo interpreta.
  fireEvent.change(screen.getByLabelText('Hora desde'), { target: { value: '19:00' } });
  fireEvent.change(screen.getByLabelText('Hora hasta'), { target: { value: '07:00' } });
  fireEvent.click(screen.getByRole('button', { name: /cargar/i }));

  await screen.findAllByText(/19:00→07:00/);

  ENDPOINTS_CON_FRANJA.forEach((ruta) => {
    expect(paramsDe(ruta)).toMatchObject({ hora_desde: '19:00', hora_hasta: '07:00' });
  });

  // Se dice en la UI que los Excel no la respetan, para no dar por hecho que sí.
  expect(screen.getByText(/los Excel .* salen con el día completo/i)).toBeInTheDocument();
});

test('«Todo el día» quita la franja sin tener que volver a pulsar Cargar', async () => {
  render(<App />);
  await screen.findByText('2.272');

  fireEvent.change(screen.getByLabelText('Hora desde'), { target: { value: '06:00' } });
  fireEvent.click(screen.getByRole('button', { name: /cargar/i }));
  await screen.findAllByText(/desde 06:00/);
  expect(paramsDe('kpis')).toMatchObject({ hora_desde: '06:00' });

  fireEvent.click(screen.getByRole('button', { name: /todo el día/i }));

  await screen.findByText('día completo');
  expect(paramsDe('kpis')).not.toHaveProperty('hora_desde');
});

/* ── Estadísticas de producción ───────────────────────────────────────────
   Antes tenía sus propios presets (Hoy / 7d / 30d / Todo) y no miraba el rango
   global, así que podía contradecir al resto del dashboard. */

test('las estadísticas usan el rango global y ya no tienen presets propios', async () => {
  render(<App />);
  await screen.findByText(/1 sesión · 28\.2%/);

  // El endpoint da precedencia a desde/hasta, así que `rango` sobra y no se manda.
  expect(paramsDe('estadisticas')).toMatchObject({ dim: 'maquina', desde: expect.any(String), hasta: expect.any(String) });
  expect(paramsDe('estadisticas')).not.toHaveProperty('rango');

  ['Hoy', '7d', '30d', 'Todo'].forEach((etiqueta) => {
    expect(screen.queryByRole('tab', { name: etiqueta })).not.toBeInTheDocument();
  });

  // Las agrupaciones, que son lo que se quería conservar, siguen ahí y funcionan.
  fireEvent.click(screen.getByRole('tab', { name: 'Operario' }));
  expect(await screen.findByText('por operario')).toBeInTheDocument();
  expect(paramsDe('estadisticas')).toMatchObject({ dim: 'operario' });
});

test('el rango de fechas de la cabecera arrastra a las estadísticas', async () => {
  render(<App />);
  await screen.findByText(/1 sesión · 28\.2%/);

  fireEvent.change(screen.getByLabelText('Fecha desde'), { target: { value: '2026-07-01' } });
  fireEvent.change(screen.getByLabelText('Fecha hasta'), { target: { value: '2026-07-31' } });
  fireEvent.click(screen.getByRole('button', { name: /cargar/i }));

  await screen.findAllByText(/2026-07-01 → 2026-07-31/);
  expect(paramsDe('estadisticas')).toMatchObject({ desde: '2026-07-01', hasta: '2026-07-31' });
});

/* ── Comentarios de turno ─────────────────────────────────────────────────
   Las tablets los envían desde hace tiempo pero no había forma de leerlos. */

test('el dashboard muestra los comentarios de turno de los operarios', async () => {
  render(<App />);

  expect(await screen.findByText('Problemas con el sello del vertical')).toBeInTheDocument();
  expect(screen.getByText('29 jul · 20:58')).toBeInTheDocument();
  expect(screen.getByText('CRISTHIAN CEDEÑO')).toBeInTheDocument();
  // Son los últimos que han llegado, no los del rango: la tarjeta no manda fechas.
  const llamada = axios.get.mock.calls.find(([url]) =>
    url.startsWith('/api/dashboard/comentarios_turno'));
  expect(llamada[1].params).not.toHaveProperty('desde');
});

/* ── Vista de paros (/paros) ──────────────────────────────────────────────
   Un solo endpoint la alimenta. El estado de las máquinas es de AHORA y los
   acumulados son del rango; los paros sin cerrar no se cuentan como en curso. */

/** Entra en la vista de paros desde el dashboard y espera a que pinte. */
async function irAParos() {
  render(<App />);
  await screen.findByText('2.272');
  fireEvent.click(screen.getByRole('button', { name: /^paros$/i }));
  expect(await screen.findByRole('heading', { name: /monitoreo de paros/i })).toBeInTheDocument();
}

test('la vista de paros resume las líneas detenidas y el tiempo perdido', async () => {
  await irAParos();

  // KPI en vivo: 1 máquina parada (en singular), 1 produciendo.
  expect(await screen.findByText('línea detenida')).toBeInTheDocument();
  expect(screen.getByText(/1 produciendo · en vivo/)).toBeInTheDocument();
  // 55800 s -> "15h 30m"; promedio 18600 s -> "5h 10m".
  expect(screen.getByText('15h 30m')).toBeInTheDocument();
  expect(screen.getByText('5h 10m')).toBeInTheDocument();
  // El recuento del rango distingue lo que sigue abierto de lo que quedó sin cerrar.
  expect(screen.getByText(/1 en curso · 1 sin cierre/)).toBeInTheDocument();
});

test('el semáforo de máquinas separa PARO, PRODUCIENDO y SIN TURNO', async () => {
  await irAParos();

  expect(await screen.findByText('PARO')).toBeInTheDocument();
  expect(screen.getByText('PRODUCIENDO')).toBeInTheDocument();
  expect(screen.getByText('SIN TURNO')).toBeInTheDocument();

  // La máquina parada muestra la categoría del paro y su comentario.
  expect(screen.getAllByText('MANTENIMIENTO').length).toBeGreaterThan(0);
  expect(screen.getAllByText('cambio de teflón').length).toBeGreaterThan(0);
  expect(screen.getByText('desde 10:00')).toBeInTheDocument();
  // La que no tiene turno lo dice en vez de fingir que produce.
  expect(screen.getByText('sin turno abierto')).toBeInTheDocument();
});

test('cada paro se puede desplegar para ver el detalle y el motivo original', async () => {
  await irAParos();

  // Cada fila es un botón que se nombra por máquina, categoría, hora y estado.
  const fila = await screen.findByRole('button', { name: /Máquina 7 · ALMUERZO · 12:00 · CERRADO/ });
  // El paro de almuerzo no trae comentario: se muestra "—", no un texto inventado.
  expect(within(fila).getByText('—')).toBeInTheDocument();
  expect(within(fila).getByText('15m')).toBeInTheDocument();     // 900 s
  expect(within(fila).getByText('12:15')).toBeInTheDocument();   // hora de fin

  fireEvent.click(fila);

  // Al desplegar aparece el motivo tal como lo mandó la tablet y la sesión.
  expect(await screen.findByText('Motivo tal como lo envió la tablet')).toBeInTheDocument();
  expect(screen.getByText('ALMUERZO', { selector: 'dd' })).toBeInTheDocument();
  expect(screen.getByText('#419')).toBeInTheDocument();
});

test('un paro sin cerrar no se presenta como en curso y avisa de la estimación', async () => {
  await irAParos();

  const fila = await screen.findByRole('button', { name: /Máquina 16 · BODEGA · 19:22 · SIN CIERRE/ });
  expect(within(fila).getByText('SIN CIERRE')).toBeInTheDocument();
  // 48654 s -> "13h 31m", y se rotula como estimada, no como tiempo medido.
  expect(within(fila).getByText('13h 31m')).toBeInTheDocument();
  expect(within(fila).getByText('estimada')).toBeInTheDocument();

  fireEvent.click(fila);
  expect(await screen.findByText(/no cerró este paro/i)).toBeInTheDocument();
  expect(screen.getByText(/hasta el cierre del turno · 08:53:33/)).toBeInTheDocument();
});

test('el ranking por categoría ordena por tiempo parado, no por número de paros', async () => {
  await irAParos();

  const tarjeta = await screen.findByRole('region', { name: 'Paros por categoría' });
  const categorias = within(tarjeta).getAllByText(/^(BODEGA|ALMUERZO|MANTENIMIENTO)$/)
    .map((el) => el.textContent);
  expect(categorias).toEqual(['BODEGA', 'ALMUERZO', 'MANTENIMIENTO']);
  expect(within(tarjeta).getByText(/13h 31m/)).toBeInTheDocument();
});

test('la vista de paros comparte el rango y la franja de la cabecera', async () => {
  await irAParos();

  // Sin franja no viajan las horas, igual que en el resto del dashboard.
  expect(paramsDe('paros')).not.toHaveProperty('hora_desde');

  fireEvent.change(screen.getByLabelText('Hora desde'), { target: { value: '19:00' } });
  fireEvent.change(screen.getByLabelText('Hora hasta'), { target: { value: '07:00' } });
  fireEvent.click(screen.getByRole('button', { name: /cargar/i }));

  await screen.findAllByText(/19:00→07:00/);
  expect(paramsDe('paros')).toMatchObject({ hora_desde: '19:00', hora_hasta: '07:00' });

  // Aquí la advertencia es otra: la franja sí filtra los paros, por su hora de inicio.
  expect(screen.getByText(/hora de inicio del paro/i)).toBeInTheDocument();
  // Y no se ofrecen los Excel, que no existen para paros.
  expect(screen.queryByRole('button', { name: /formularios/i })).not.toBeInTheDocument();
});

test('la vista de paros es una URL propia y el botón atrás vuelve al dashboard', async () => {
  await irAParos();
  expect(window.location.pathname).toBe('/paros');

  window.history.back();
  await screen.findByRole('heading', { name: /producción en tiempo real/i });
});

test('desde paros se vuelve al dashboard sin recargar, por el botón o por el logo', async () => {
  await irAParos();

  // Botón explícito de la cabecera.
  fireEvent.click(screen.getByRole('button', { name: /^dashboard$/i }));
  expect(await screen.findByRole('heading', { name: /producción en tiempo real/i })).toBeInTheDocument();
  expect(window.location.pathname).toBe('/');

  // Y la marca también lleva de vuelta, que es donde todo el mundo pulsa primero.
  fireEvent.click(screen.getByRole('button', { name: /^paros$/i }));
  await screen.findByRole('heading', { name: /monitoreo de paros/i });
  fireEvent.click(screen.getByRole('button', { name: 'Ir al dashboard' }));
  expect(await screen.findByRole('heading', { name: /producción en tiempo real/i })).toBeInTheDocument();
  expect(window.location.pathname).toBe('/');
});
