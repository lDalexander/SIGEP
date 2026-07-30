import { render, screen } from '@testing-library/react';
import axios from 'axios';
import App from './App';

jest.mock('axios');

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
};

beforeEach(() => {
  axios.get.mockImplementation((url) => {
    const clave = Object.keys(RESPUESTAS).find((k) => url.startsWith(k));
    return clave
      ? Promise.resolve({ data: RESPUESTAS[clave] })
      : Promise.reject(new Error(`sin mock para ${url}`));
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

test('si la API falla no se inventan datos y se avisa de la desconexión', async () => {
  axios.get.mockRejectedValue(new Error('network down'));
  render(<App />);

  expect(
    await screen.findByText(/se muestran los últimos datos recibidos/i)
  ).toBeInTheDocument();
  // Ningún número inventado en las tarjetas KPI.
  expect(screen.queryByText('2.272')).not.toBeInTheDocument();
});
