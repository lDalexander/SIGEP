import { render, screen, within } from '@testing-library/react';
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
