import React from 'react';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import axios from 'axios';
import AdminApp from './AdminApp';
// El cliente que llega aquí es el del mock de abajo: jest.mock se iza sobre los imports.
import { admin as mockAdmin } from '../../lib/adminApi';

jest.mock('axios');

/* Se mockea el módulo de la API de administración en lugar de axios entero: así el
   test ejercita los componentes y comprueba QUÉ endpoint se llama, sin depender de
   los interceptores ni de localStorage. El cliente se crea DENTRO del factory porque
   este se evalúa antes que cualquier `const` del módulo. */
jest.mock('../../lib/adminApi', () => ({
  admin: { get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn() },
  entrar: jest.fn(),
  salir: jest.fn(() => Promise.resolve()),
  leerSesion: () => ({ token: 't0ken', username: 'admin', nivel: 'SUPERADMIN' }),
  registrarCaducidad: jest.fn(),
  mensajeDeError: (err, porDefecto) => err?.response?.data?.detail || porDefecto || 'error',
}));

const OPERARIOS = [
  { id: 2, nombre: 'JONATHAN VICUÑA', activo: true },
  { id: 1, nombre: 'ALEX VALENZUELA', activo: true },
  { id: 9, nombre: 'ZOILO PEREZ', activo: false },
];

const MAQUINA_PRODUCTOS = [
  {
    maquina_id: 1, maquina: 'Máquina 7', tipo: 'SOLIDO', activa: true,
    productos: [
      { id: 11, marca: 'COMISARIATO', presentacion: '1.2 KG', activo: true },
      { id: 12, marca: 'TORBELLINO', presentacion: '1 KG', activo: true },
    ],
  },
];

const CATALOGOS = {
  maquinas: [{ id: 1, nombre: 'Máquina 7', tipo: 'SOLIDO' }],
  marcas: ['COMISARIATO', 'TORBELLINO', 'ULTREX'],
  presentaciones: ['1 KG', '1.2 KG', '500 GR'],
};

const SESIONES = [
  {
    id: 330, maquina: 'Máquina 8', operador: 'KEVIN SORIANO', marca: 'ULTREX',
    presentacion: '500 GR', fragancia: 'Limón', inicio: '2026-07-23 11:19',
    fin: null, estado: 'Activo', total_pacas: 0, n_registros: 0,
  },
];

const CHECKLISTS = [
  {
    id: 222, maquina: 'Máquina 8', operador: 'KEVIN SORIANO', momento: 'ENTRADA',
    codigo_turno: 'DIA', fecha_turno: '2026-07-23', hora: '11:19',
    supervisor: 'Emilio', comentarios: null, items_ok: 1, total_items: 2,
    items: [
      { id: 900, etiqueta: 'Revisión de presión de aire comprimido', marcado: true },
      { id: 901, etiqueta: 'Revisar el estado del teflón', marcado: false },
    ],
  },
];

const SESIONES_ACTIVAS = [
  {
    sesion_id: 372, maquina: 'Máquina 16', operador: 'ROLANDO MORAN',
    producto: 'TORBELLINO · 1 KG · Limón', inicio: '09:30', tablet_online: false,
  },
  {
    sesion_id: 376, maquina: 'Máquina 7', operador: 'WILSON BAYAS',
    producto: 'TORBELLINO · 1 KG · Floral', inicio: '07:33', tablet_online: true,
  },
];

beforeEach(() => {
  jest.clearAllMocks();
  window.confirm = jest.fn(() => true);

  mockAdmin.get.mockImplementation((url) => {
    if (url === '/operadores')        return Promise.resolve({ data: OPERARIOS });
    if (url === '/maquina_productos') return Promise.resolve({ data: MAQUINA_PRODUCTOS });
    if (url === '/catalogos')         return Promise.resolve({ data: CATALOGOS });
    if (url === '/sesiones')          return Promise.resolve({ data: SESIONES });
    if (url === '/checklists')        return Promise.resolve({ data: CHECKLISTS });
    if (url === '/sesiones_activas')  return Promise.resolve({ data: SESIONES_ACTIVAS });
    return Promise.reject(new Error(`sin mock para ${url}`));
  });
  mockAdmin.post.mockResolvedValue({ data: { ok: true } });
  mockAdmin.put.mockResolvedValue({ data: { ok: true } });

  // TabProduccion pide las fragancias al endpoint público (no hay tabla maestra).
  axios.get.mockResolvedValue({ data: { fragancia: ['Limón', 'Floral'] } });
});

/** Cambia de pestaña por su rótulo. */
function irA(nombre) {
  fireEvent.click(screen.getByRole('tab', { name: nombre }));
}

test('la cabecera muestra el nivel de acceso y las cinco pestañas', async () => {
  render(<AdminApp />);

  expect(screen.getByText('SIGEP · Administración')).toBeInTheDocument();
  expect(screen.getByText('SUPERADMIN')).toBeInTheDocument();

  ['Operarios', 'Producción', 'Checklists', 'Jerarquía', 'Mensajes'].forEach((p) =>
    expect(screen.getByRole('tab', { name: p })).toBeInTheDocument()
  );
  expect(screen.getByRole('button', { name: 'Salir' })).toBeInTheDocument();

  // Se espera la carga de la pestaña inicial para no dejar estado pendiente.
  await screen.findByText('ALEX VALENZUELA');
});

test('Operarios: orden alfabético, contador y alta en mayúsculas', async () => {
  render(<AdminApp />);

  expect(await screen.findByText('ALEX VALENZUELA')).toBeInTheDocument();
  expect(screen.getByText('2 activos · 3 en total')).toBeInTheDocument();

  const nombres = screen.getAllByText(/VALENZUELA|VICUÑA|PEREZ/).map((n) => n.textContent);
  expect(nombres).toEqual(['ALEX VALENZUELA', 'JONATHAN VICUÑA', 'ZOILO PEREZ']);

  // El nombre se guarda en mayúsculas aunque se escriba en minúsculas.
  fireEvent.change(screen.getByLabelText('Nombre del operario'), {
    target: { value: 'juan pérez' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Agregar' }));

  await waitFor(() =>
    expect(mockAdmin.post).toHaveBeenCalledWith('/operadores', { nombre: 'JUAN PÉREZ' })
  );
});

test('Operarios: «Eliminar» es baja lógica, nunca DELETE', async () => {
  render(<AdminApp />);
  await screen.findByText('ALEX VALENZUELA');

  const fila = screen.getByRole('listitem', { name: 'ALEX VALENZUELA' });
  fireEvent.click(within(fila).getByRole('button', { name: 'Eliminar' }));

  expect(window.confirm).toHaveBeenCalled();
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/operadores/1', { activo: false })
  );
  expect(mockAdmin.delete).not.toHaveBeenCalled();
});

test('Jerarquía: combinaciones por máquina y alternar el tipo de línea', async () => {
  render(<AdminApp />);
  irA('Jerarquía');

  expect(await screen.findByText('Máquina 7')).toBeInTheDocument();
  expect(screen.getByText('COMISARIATO · 1.2 KG')).toBeInTheDocument();
  expect(screen.getByText('2 combinaciones')).toBeInTheDocument();

  // La máquina es SOLIDO, así que el botón ofrece pasarla a Líquido.
  fireEvent.click(screen.getByRole('button', { name: '→ Líquido' }));
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/maquinas/1', { tipo: 'Líquido' })
  );
});

test('Jerarquía: el tipo de línea ofrece Sólido y Líquido una sola vez', async () => {
  render(<AdminApp />);
  irA('Jerarquía');
  await screen.findByText('Máquina 7');

  const tipo = screen.getByLabelText('Tipo de línea');
  // Campo obligatorio: sin opción vacía, y por tanto sin «Sólido» duplicado.
  expect(within(tipo).getAllByRole('option').map((o) => o.textContent))
    .toEqual(['Sólido', 'Líquido']);
});

test('Jerarquía: se puede añadir una combinación marca + presentación', async () => {
  render(<AdminApp />);
  irA('Jerarquía');
  await screen.findByText('Máquina 7');

  fireEvent.change(screen.getByLabelText('Marca para Máquina 7'), {
    target: { value: 'ULTREX' },
  });
  fireEvent.change(screen.getByLabelText('Presentación para Máquina 7'), {
    target: { value: '1 KG' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Agregar combinación' }));

  await waitFor(() =>
    expect(mockAdmin.post).toHaveBeenCalledWith('/maquina_productos', {
      maquina_id: 1, marca: 'ULTREX', presentacion: '1 KG',
    })
  );
});

test('Producción: los campos son selects de catálogo y solo guardan lo cambiado', async () => {
  render(<AdminApp />);
  irA('Producción');

  expect(await screen.findByText(/Sesión #330 · 2026-07-23 11:19/)).toBeInTheDocument();
  expect(screen.getByText('Pacas: 0 (0 reg.)')).toBeInTheDocument();

  const guardar = screen.getByRole('button', { name: 'Guardar sesión' });
  expect(guardar).toBeDisabled(); // sin cambios no hay nada que guardar

  const maquina = screen.getByLabelText(/^maquina$/i, { selector: 'select' });
  expect(maquina.tagName).toBe('SELECT');
  fireEvent.change(maquina, { target: { value: 'Máquina 7' } });

  fireEvent.click(screen.getByRole('button', { name: 'Guardar sesión' }));
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/sesiones/330', { maquina: 'Máquina 7' })
  );
});

test('Checklists: los ítems vienen de la API y se guardan por id', async () => {
  render(<AdminApp />);
  irA('Checklists');

  expect(await screen.findByText(/#222 · Máquina 8 · KEVIN SORIANO/)).toBeInTheDocument();
  expect(screen.getByText('2026-07-23 · DIA · 11:19')).toBeInTheDocument();

  // Ningún ítem está escrito en el código: son los que devolvió la API.
  const casilla = screen.getByText('Revisar el estado del teflón');
  fireEvent.click(casilla);

  fireEvent.click(screen.getByRole('button', { name: 'Guardar checklist' }));
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/checklists/222', {
      items: [{ id: 901, marcado: true }],
    })
  );
});

test('Mensajes: sin selección no se puede enviar, y «a todas» omite sesion_ids', async () => {
  render(<AdminApp />);
  irA('Mensajes');

  expect(await screen.findByText(/MÁQUINA 16 · ROLANDO MORAN/i)).toBeInTheDocument();
  expect(screen.getByText('OFFLINE')).toBeInTheDocument();
  expect(screen.getByText('ONLINE')).toBeInTheDocument();

  const aSeleccionadas = screen.getByRole('button', { name: 'Enviar a seleccionadas' });
  expect(aSeleccionadas).toBeDisabled();

  // Una plantilla rellena el textarea.
  fireEvent.click(screen.getByRole('button', { name: 'Sube la velocidad' }));
  expect(screen.getByLabelText('Texto de la alerta')).toHaveValue('Sube la velocidad');

  // Con una sesión elegida se envían solo sus ids.
  fireEvent.click(screen.getByLabelText('Seleccionar Máquina 16 · ROLANDO MORAN'));
  expect(screen.getByText('1 seleccionadas')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Enviar a seleccionadas' }));
  await waitFor(() =>
    expect(mockAdmin.post).toHaveBeenCalledWith('/mensajes/masivo', {
      texto: 'Sube la velocidad', sesion_ids: [372],
    })
  );

  // El envío general omite sesion_ids: el backend lo interpreta como TODAS.
  mockAdmin.post.mockClear();
  fireEvent.change(screen.getByLabelText('Texto de la alerta'), {
    target: { value: 'Parada general' },
  });
  fireEvent.click(screen.getByRole('button', { name: /Enviar a TODAS las activas/ }));
  expect(window.confirm).toHaveBeenCalled();
  await waitFor(() =>
    expect(mockAdmin.post).toHaveBeenCalledWith('/mensajes/masivo', { texto: 'Parada general' })
  );
});
