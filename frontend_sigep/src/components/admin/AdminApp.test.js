import React from 'react';
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react';
import axios from 'axios';
import AdminApp from './AdminApp';
// El cliente que llega aquí es el del mock de abajo: jest.mock se iza sobre los imports.
import {
  admin as mockAdmin,
  salir as mockSalir,
  registrarCaducidad as mockRegistrarCaducidad,
} from '../../lib/adminApi';

jest.mock('axios');

/* Se mockea el módulo de la API de administración en lugar de axios entero: así el
   test ejercita los componentes y comprueba QUÉ endpoint se llama, sin depender de
   los interceptores ni de localStorage. El cliente se crea DENTRO del factory porque
   este se evalúa antes que cualquier `const` del módulo. */
/* `mockNivel` permite probar la misma UI con distintos niveles de acceso: el
   prefijo `mock` es obligatorio — jest.mock se iza sobre los `let` y solo deja
   referenciar desde el factory variables que empiecen así. El
   backend es quien decide (403), pero la web no debe ofrecer lo que va a rechazar. */
let mockNivel = 'SUPERADMIN';

jest.mock('../../lib/adminApi', () => ({
  admin: { get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn() },
  entrar: jest.fn(),
  salir: jest.fn(() => Promise.resolve()),
  leerSesion: () => ({ token: 't0ken', username: 'admin', nivel: mockNivel }),
  registrarCaducidad: jest.fn(),
  msDeInactividad: () => 15 * 60 * 1000,
  AVISO_INACTIVIDAD: 'Sesión cerrada por inactividad. Vuelve a iniciar sesión.',
  mensajeDeError: (err, porDefecto) => err?.response?.data?.detail || porDefecto || 'error',
  NIVELES_OPERATIVOS: ['SUPERADMIN', 'ADMIN', 'ADMINPLANTA', 'ADMINBODEGA'],
  NIVELES_PLANTA: ['SUPERADMIN', 'ADMINPLANTA', 'ADMIN'],
  NIVELES_VER_PLANTA: ['SUPERADMIN', 'ADMINPLANTA', 'ADMIN', 'CONSULTA'],
  NIVELES_BODEGA: ['SUPERADMIN', 'ADMINBODEGA'],
  NIVELES_SISTEMA: ['SUPERADMIN'],
  nivelActual: () => mockNivel,
  esSuperadmin: () => mockNivel === 'SUPERADMIN',
  tieneAcceso: (niveles) => niveles.includes(mockNivel),
  puedeEditar: () =>
    ['SUPERADMIN', 'ADMIN', 'ADMINPLANTA', 'ADMINBODEGA'].includes(mockNivel),
}));

const OPERARIOS = [
  { id: 2, nombre: 'JONATHAN VICUÑA', tipo: 'SOLIDO', activo: true },
  { id: 1, nombre: 'ALEX VALENZUELA', tipo: 'SOLIDO', activo: true },
  { id: 9, nombre: 'ZOILO PEREZ', tipo: 'LIQUIDO', activo: false },
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

/* Fragancias: cuelgan de (máquina, marca) — sin la presentación. COMISARIATO tiene
   una activa y una quitada; TORBELLINO ninguna, que es el caso en que la API cae al
   catálogo completo y la tablet las ofrece todas. */
const MAQUINA_FRAGANCIAS = [
  {
    maquina_id: 1, maquina: 'Máquina 7', tipo: 'SOLIDO', activa: true,
    marcas: [
      {
        marca: 'COMISARIATO',
        produce: true,
        fragancias: [
          { id: 51, fragancia: 'Floral', activo: true },
          { id: 52, fragancia: 'Limón', activo: false },
        ],
      },
      { marca: 'TORBELLINO', produce: true, fragancias: [] },
    ],
  },
];

/* Velocidad estándar: cuelga de (máquina, presentación) — sin la marca, al revés que las
   fragancias. «1.2 KG» ya tiene rango; «1 KG» no lo tiene todavía (el caso de alta), y
   «500 GR» lo tuvo y se quitó, además de ser una presentación que esta máquina ya no
   produce: hay que poder quitarle el rango aunque no salga en su jerarquía. */
const VELOCIDAD_ESTANDAR = [
  {
    maquina_id: 1, maquina: 'Máquina 7', tipo: 'SOLIDO', activa: true,
    presentaciones: [
      { presentacion: '1 KG', produce: true, estandar: null },
      { presentacion: '1.2 KG', produce: true,
        estandar: { id: 71, vel_min: 32, vel_max: 38, activo: true } },
      { presentacion: '500 GR', produce: false,
        estandar: { id: 72, vel_min: 50, vel_max: 60, activo: false } },
    ],
  },
];

const CATALOGOS = {
  maquinas: [{ id: 1, nombre: 'Máquina 7', tipo: 'SOLIDO' }],
  marcas: ['COMISARIATO', 'TORBELLINO', 'ULTREX'],
  presentaciones: ['1 KG', '1.2 KG', '500 GR'],
  fragancias: ['Floral', 'Limón'],
};

const SESIONES = [
  {
    id: 330, maquina: 'Máquina 8', operador: 'KEVIN SORIANO', marca: 'ULTREX',
    presentacion: '500 GR', fragancia: 'Limón', inicio: '2026-07-23 11:19',
    fin: null, estado: 'Activo', total_pacas: 0, n_registros: 0,
    /* Promedio 43,2 contra una meta de 32–38: el turno se salió del rango. */
    velocidad_promedio: 43.2, velocidad_lecturas: 6,
    velocidad_min: 32, velocidad_max: 38,
  },
  /* Finalizada y con producción: no debe ofrecer «CERRAR TURNO», y su borrado
     exige teclear el número por tener pacas registradas. */
  {
    id: 331, maquina: 'Máquina 7', operador: 'PEDRO SARABIA', marca: 'PQP',
    presentacion: '25 KG', fragancia: 'Floral', inicio: '2026-07-23 07:10',
    fin: '15:40', estado: 'Finalizado', total_pacas: 480, n_registros: 12,
    /* Sin ninguna lectura: las sesiones anteriores a este cambio nunca tendrán. */
    velocidad_promedio: null, velocidad_lecturas: 0,
    velocidad_min: null, velocidad_max: null,
  },
];

/* Registros de pacas de la sesión 331: lo que devuelve /sesiones/{id}/pallets. */
const PALLETS_331 = [
  { id: 900, cantidad_pacas: 240, fecha_hora: '2026-07-23 08:15:32' },
  { id: 901, cantidad_pacas: 240, fecha_hora: '2026-07-23 11:47:05' },
];

const USUARIOS = [
  { id: 1, username: 'admin', nivel_acceso: 'SUPERADMIN', activo: true,
    password_migrada: true, es_tu_usuario: true },
  { id: 2, username: 'Planta', nivel_acceso: 'ADMINPLANTA', activo: true,
    password_migrada: false, es_tu_usuario: false },
];

const NIVELES = [
  { nivel: 'SUPERADMIN', descripcion: 'Todo, incluidos usuarios y eliminar sesiones' },
  { nivel: 'ADMINPLANTA', descripcion: 'Operación de planta' },
  { nivel: 'CONSULTA', descripcion: 'Solo lectura: ve todo, no modifica nada' },
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

const TABLETS_ADMIN = [
  {
    device_id: 'c439e96c-3ea1-47e9-9411-1752b22f821e', nombre: 'CRISTHIAN CEDEÑO',
    maquina: 'Máquina 7', pendientes: 0, ultimo_heartbeat: '2026-08-07 12:30:00',
    ultima_sincronizacion: '2026-08-07 12:30:00', segundos_desde_heartbeat: 642,
    conectada: true,
  },
  {
    /* Retirada: casi 9 días sin dar señales. Es la que hay que poder limpiar. */
    device_id: '7bf71ff0-64b0-4a1e-9f0e-000000000000', nombre: 'PRUEBAS',
    maquina: 'PRUEBA', pendientes: 3, ultimo_heartbeat: '2026-07-29 08:00:00',
    ultima_sincronizacion: null, segundos_desde_heartbeat: 758945, conectada: false,
  },
];

const REPORTES_APP = [
  {
    id: 3, sesion_id: 449, maquina: 'Máquina 7', operador: 'CRISTHIAN CEDEÑO',
    texto: 'No permite ingresar pacas', creado_en: '2026-08-07 09:12:00',
    atendido: false, atendido_en: null, atendido_por: null,
  },
  {
    id: 2, sesion_id: null, maquina: 'PRUEBA', operador: 'PRUEBAS',
    texto: 'reporte de prueba', creado_en: '2026-08-01 10:00:00',
    atendido: true, atendido_en: '2026-08-02 08:00:00', atendido_por: 'admin',
  },
];

const COMENTARIOS_TURNO = [
  {
    id: 15, sesion_id: 447, maquina: 'Máquina 9', operador: 'ROSENDO VALENZUELA',
    texto: 'Ya descansa hijito', creado_en: '2026-08-06 22:40:00',
  },
];

const INSUMOS = {
  kpis: { total_pedidos: 2, tiempo_resp_prom_seg: 240, con_discrepancia: 1, entregas_proactivas: 1 },
  pedidos: [
    /* Uno con descuadre entre lo entregado y lo recibido: es el caso que hay que
       poder corregir sin entrar a la base. */
    {
      id: 51, maquina: 'Máquina 7', operador: 'CRISTHIAN CEDEÑO', insumo: 'Bobina',
      categoria: 'EMPAQUE', insumista: 'LUIS BODEGA', estado: 'Entregado',
      hora_solicitud: '08:15:00', solicitada: 10, entregada: 10, recibida: 8,
    },
    {
      id: 52, maquina: 'Máquina 9', operador: 'ROSENDO VALENZUELA', insumo: 'Etiquetas',
      categoria: 'EMPAQUE', insumista: null, estado: 'Pendiente',
      hora_solicitud: '09:40:00', solicitada: 5, entregada: null, recibida: null,
    },
  ],
  entregas: [
    {
      id: 7, insumista: 'LUIS BODEGA', tipo_producto: 'EMPAQUE', insumo: 'Cinta',
      cantidad: 3, maquina: 'Máquina 7', observaciones: null, foto_url: null,
      fecha_hora: '2026-08-07 10:05:00',
    },
  ],
};

const PAROS = [
  /* Uno cerrado normal, uno sin cerrar con el turno ya cerrado (el caso «SIN CIERRE»
     que dejaba el recolector), uno con categoría que no está en la lista fija, y uno
     EN CURSO: ese es el que tiene una tablet detrás con el botón verde puesto. */
  {
    id: 142, sesion_id: 531, maquina: 'Máquina 7', operador: 'CRISTHIAN CEDEÑO',
    categoria: 'BODEGA', comentario: 'materia prima',
    motivo: '[Bodega] - materia prima',
    inicio_paro: '2026-08-19 15:01:28', fin_paro: null,
    duracion_segundos: 900, duracion_estimada: false, estado: 'EN CURSO', sesion_existe: true,
  },
  {
    id: 130, sesion_id: 447, maquina: 'Máquina 7', operador: 'CRISTHIAN CEDEÑO',
    categoria: 'ALMUERZO', comentario: null, motivo: 'ALMUERZO',
    inicio_paro: '2026-08-07 11:52:21', fin_paro: '2026-08-07 12:31:54',
    duracion_segundos: 2373, duracion_estimada: false, estado: 'CERRADO', sesion_existe: true,
    foto_url: '/static/paros/130_447_20260807115300.jpg',
  },
  {
    id: 118, sesion_id: 440, maquina: 'Máquina 9', operador: 'ROSENDO VALENZUELA',
    categoria: 'MANTENIMIENTO', comentario: 'cambio de teflón',
    motivo: '[Mantenimiento] - cambio de teflón',
    inicio_paro: '2026-08-06 12:56:00', fin_paro: null,
    duracion_segundos: 12540, duracion_estimada: true, estado: 'SIN CIERRE', sesion_existe: true,
  },
  {
    id: 99, sesion_id: null, maquina: '—', operador: '—',
    categoria: 'CATEGORÍA RARA', comentario: null, motivo: 'CATEGORÍA RARA',
    inicio_paro: '2026-08-05 08:00:00', fin_paro: '2026-08-05 08:30:00',
    duracion_segundos: 1800, duracion_estimada: false, estado: 'CERRADO', sesion_existe: false,
  },
];

const CONFIG_CORREO = {
  smtp_host: 'smtp-mail.outlook.com', smtp_port: 587,
  smtp_user: 'no-reply@detcuador.com', smtp_from: 'no-reply@detcuador.com',
  password_definida: true, password_en_bd: false,
  destinos: {
    semanal:  { to: ['agarcia@detcuador.com'], cc: [], origen_to: 'heredado', origen_cc: 'env' },
    reportes: { to: ['agarcia@detcuador.com'], cc: [], origen_to: 'env', origen_cc: 'env' },
    pedidos:  { to: ['agarcia@detcuador.com'], cc: ['bodega@detcuador.com'], origen_to: 'env', origen_cc: 'env' },
  },
  semanal_activo: true,
  semanal_ultimo_envio: null, semanal_ultima_ventana: null,
  semanal_proximo_envio: '2026-08-14 12:00:00',
  actualizado_en: null, actualizado_por: null,
};

const VISTA_PREVIA = {
  desde: '2026-07-24 12:00:00', hasta: '2026-07-31 12:00:00',
  total_horas: '20h 32m', total_paros: 17, promedio: '1h 12m',
  variacion_pct: 35.9, previo_horas: '15h 06m', sin_cierre: 0, en_curso: 0,
  excluidos_paros: 11, excluidos_horas: '3h 05m', excluidas: ['ALMUERZO'],
  por_categoria: [{ etiqueta: 'MANTENIMIENTO', paros: 8, horas: '14h 42m' }],
  por_maquina: [{ etiqueta: 'Máquina 9', paros: 4, horas: '10h 53m' }],
};

const SESIONES_ACTIVAS = [
  {
    sesion_id: 372, maquina: 'Máquina 16', operador: 'ROLANDO MORAN',
    producto: 'TORBELLINO · 1 KG · Limón', inicio: '09:30', tablet_online: false,
    segundos_desde_contacto: 1260,
  },
  {
    sesion_id: 376, maquina: 'Máquina 7', operador: 'WILSON BAYAS',
    producto: 'TORBELLINO · 1 KG · Floral', inicio: '07:33', tablet_online: true,
    segundos_desde_contacto: 40,
  },
];

beforeEach(() => {
  jest.clearAllMocks();
  window.confirm = jest.fn(() => true);
  window.prompt = jest.fn(() => '331');   // confirmación tecleada del borrado
  mockNivel = 'SUPERADMIN';

  mockAdmin.get.mockImplementation((url) => {
    if (url === '/operadores')        return Promise.resolve({ data: OPERARIOS });
    if (url === '/maquina_productos') return Promise.resolve({ data: MAQUINA_PRODUCTOS });
    if (url === '/maquina_fragancias') return Promise.resolve({ data: MAQUINA_FRAGANCIAS });
    if (url === '/velocidad_estandar') return Promise.resolve({ data: VELOCIDAD_ESTANDAR });
    if (url === '/catalogos')         return Promise.resolve({ data: CATALOGOS });
    if (url === '/sesiones')          return Promise.resolve({ data: SESIONES });
    if (url === '/checklists')        return Promise.resolve({ data: CHECKLISTS });
    if (url === '/sesiones_activas')  return Promise.resolve({ data: SESIONES_ACTIVAS });
    if (url === '/sesiones/331/pallets') return Promise.resolve({ data: PALLETS_331 });
    if (url === '/sesiones/330/pallets') return Promise.resolve({ data: [] });
    if (url === '/usuarios')          return Promise.resolve({ data: USUARIOS });
    if (url === '/niveles')           return Promise.resolve({ data: NIVELES });
    if (url === '/paros')             return Promise.resolve({ data: PAROS });
    if (url === '/tablets')           return Promise.resolve({ data: TABLETS_ADMIN });
    if (url === '/reportes_app')      return Promise.resolve({ data: REPORTES_APP });
    if (url === '/comentarios')       return Promise.resolve({ data: COMENTARIOS_TURNO });
    if (url === '/correo')            return Promise.resolve({ data: CONFIG_CORREO });
    if (url === '/correo/semanal_vista_previa') return Promise.resolve({ data: VISTA_PREVIA });
    return Promise.reject(new Error(`sin mock para ${url}`));
  });
  mockAdmin.post.mockResolvedValue({ data: { ok: true } });
  mockAdmin.put.mockResolvedValue({ data: { ok: true } });
  mockAdmin.delete.mockResolvedValue({ data: { eliminada: 331, borrado: { pallets: 12, paros: 2 } } });

  /* Endpoints públicos que consumen pestañas del admin: las fragancias en Producción
     y el dashboard de insumos en Insumos (es el único que arma pedidos y entregas). */
  axios.get.mockImplementation((url) => {
    if (String(url).includes('/insumos/dashboard')) return Promise.resolve({ data: INSUMOS });
    return Promise.resolve({ data: { fragancia: ['Limón', 'Floral'] } });
  });
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

  // El nombre se guarda en mayúsculas y por defecto en la línea sólida.
  fireEvent.change(screen.getByLabelText('Nombre del operario'), {
    target: { value: 'juan pérez' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Agregar' }));

  await waitFor(() =>
    expect(mockAdmin.post).toHaveBeenCalledWith('/operadores',
      { nombre: 'JUAN PÉREZ', tipo: 'Sólido' })
  );
});

test('Operarios: se puede dar de alta en la línea líquida', async () => {
  render(<AdminApp />);
  await screen.findByText('ALEX VALENZUELA');

  fireEvent.change(screen.getByLabelText('Nombre del operario'), {
    target: { value: 'nuevo operario' },
  });
  fireEvent.change(screen.getByLabelText('Línea del operario'), {
    target: { value: 'Líquido' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Agregar' }));

  await waitFor(() =>
    expect(mockAdmin.post).toHaveBeenCalledWith('/operadores',
      { nombre: 'NUEVO OPERARIO', tipo: 'Líquido' })
  );
});

test('Operarios: el filtro por línea separa sólido de líquido', async () => {
  render(<AdminApp />);
  await screen.findByText('ALEX VALENZUELA');

  // «Todos» los muestra a los tres.
  expect(screen.getByText('2 activos · 3 en total')).toBeInTheDocument();

  fireEvent.click(screen.getByRole('tab', { name: 'Líquido' }));
  expect(screen.getByText('ZOILO PEREZ')).toBeInTheDocument();
  expect(screen.queryByText('ALEX VALENZUELA')).not.toBeInTheDocument();
  expect(screen.getByText('0 activos · 1 en total')).toBeInTheDocument();

  fireEvent.click(screen.getByRole('tab', { name: 'Sólido' }));
  expect(screen.getByText('ALEX VALENZUELA')).toBeInTheDocument();
  expect(screen.queryByText('ZOILO PEREZ')).not.toBeInTheDocument();
  expect(screen.getByText('2 activos · 2 en total')).toBeInTheDocument();
});

test('Operarios: se puede cambiar a un operario de línea', async () => {
  render(<AdminApp />);
  await screen.findByText('ALEX VALENZUELA');

  const fila = screen.getByRole('listitem', { name: 'ALEX VALENZUELA' });
  fireEvent.click(within(fila).getByRole('button', { name: '→ Líquido' }));

  expect(window.confirm).toHaveBeenCalled();
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/operadores/1', { tipo: 'Líquido' })
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
  /* La marca encabeza su bloque y la fila lleva solo el gramaje; el nombre accesible
     de la fila sigue diciendo la combinación entera. */
  expect(screen.getByRole('heading', { level: 4, name: 'COMISARIATO' })).toBeInTheDocument();
  expect(screen.getByRole('listitem', { name: 'COMISARIATO · 1.2 KG' })).toBeInTheDocument();
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
      /* Máquina 7 es SÓLIDO: su selector de producto no se muestra, asi que la web
         manda vacio = comodin y el backend lo deduce (DETERGENTE EN POLVO). */
      maquina_id: 1, producto: '', marca: 'ULTREX', presentacion: '1 KG',
    })
  );
});

/* ─────────────────────────────────────────────────────────────────────────────
   Combinaciones agrupadas por producto (2026-08-31)

   La línea líquida rompió el supuesto de que una máquina hace un solo producto: la
   Máquina 3 llena SUAVIZANTE y DETERGENTE LIQUIDO, y ambos existen en COMISARIATO ·
   2.9 LT. NO son la misma combinación —el producto las distingue— pero la fila solo
   pintaba marca y presentación, así que en pantalla se leían como repetidas y el
   producto quedaba escondido en un desplegable al otro extremo de la fila.
   ───────────────────────────────────────────────────────────────────────────── */
const MAQUINA_3 = [{
  maquina_id: 8, maquina: 'Máquina 3', tipo: 'LIQUIDO', activa: true,
  productos: [
    /* Tal como los ordena el backend: por marca y presentación, sin mirar el producto. */
    { id: 61, producto: '', marca: 'COMISARIATO', presentacion: '1 LT', activo: true },
    { id: 62, producto: 'SUAVIZANTE', marca: 'COMISARIATO', presentacion: '2.9 LT', activo: true },
    { id: 63, producto: 'DETERGENTE LIQUIDO', marca: 'COMISARIATO', presentacion: '2.9 LT', activo: true },
    { id: 64, producto: 'SUAVIZANTE', marca: 'ULTREX', presentacion: '3 LT', activo: true },
    { id: 65, producto: 'DETERGENTE LIQUIDO', marca: 'ULTREX', presentacion: '3 LT', activo: true },
  ],
}];

function jerarquiaCon(maquinas, fragancias) {
  const previo = mockAdmin.get.getMockImplementation();
  mockAdmin.get.mockImplementation((url) => {
    if (url === '/maquina_productos') return Promise.resolve({ data: maquinas });
    if (url === '/maquina_fragancias' && fragancias) {
      return Promise.resolve({ data: fragancias });
    }
    if (url === '/catalogos') return Promise.resolve({
      data: { ...CATALOGOS, productos: [
        { nombre: 'SUAVIZANTE', tipo: 'LIQUIDO' },
        { nombre: 'DETERGENTE LIQUIDO', tipo: 'LIQUIDO' },
      ] },
    });
    return previo(url);
  });
}

/* Las fragancias de la Máquina 3, tal como están configuradas en planta. HIT es el
   caso de la marca que ya no se produce ahí pero conserva su fragancia: sin sitio en la
   jerarquía, y aun así hay que poder verla y quitarla. */
const FRAGANCIAS_M3 = [{
  maquina_id: 8, maquina: 'Máquina 3', tipo: 'LIQUIDO', activa: true,
  marcas: [
    { marca: 'COMISARIATO', produce: true, fragancias: [{ id: 71, fragancia: 'FLORAL', activo: true }] },
    { marca: 'ULTREX', produce: true, fragancias: [
      { id: 72, fragancia: 'FLORAL', activo: true },
      { id: 73, fragancia: 'FRUTOS ROJOS', activo: true },
    ] },
    { marca: 'HIT', produce: false, fragancias: [{ id: 74, fragancia: 'LAVANDA', activo: true }] },
  ],
}];

test('Jerarquía: la fragancia sale en su marca, dentro de la jerarquía del producto', async () => {
  jerarquiaCon(MAQUINA_3, FRAGANCIAS_M3);
  render(<AdminApp />);
  irA('Jerarquía');

  await screen.findByText('Máquina 3');
  /* La fragancia cuelga de la marca, y la marca vive dentro de su producto: se ve
     entera la cadena producto → marca → presentación → fragancia sin bajar a otro
     bloque. */
  expect(screen.getAllByRole('button', {
    name: 'Quitar FRUTOS ROJOS de ULTREX · Máquina 3',
  }).length).toBeGreaterThan(0);

  /* COMISARIATO produce en los tres grupos (SUAVIZANTE, DETERGENTE LIQUIDO y el
     comodín) y su FLORAL se pinta en los tres: la fragancia acompaña a la marca allá
     donde la marca aparezca, en vez de vivir en un bloque suelto al final. */
  expect(screen.getAllByRole('button', {
    name: 'Quitar FLORAL de COMISARIATO · Máquina 3',
  })).toHaveLength(3);
});

test('Jerarquía: una marca en dos productos avisa de que la fragancia es la misma', async () => {
  jerarquiaCon(MAQUINA_3, FRAGANCIAS_M3);
  render(<AdminApp />);
  irA('Jerarquía');

  await screen.findByText('Máquina 3');
  /* maquina_marca_fragancias va por (máquina, marca) y NO por producto: quitar FLORAL
     bajo SUAVIZANTE lo quita también en DETERGENTE LIQUIDO. Sin decirlo, el cambio
     parecería local y se tocaría a ciegas. */
  const avisos = screen.getAllByText(/las mismas fragancias en/);
  expect(avisos.length).toBeGreaterThan(0);
  expect(avisos.map((a) => a.textContent).join(' | ')).toContain('DETERGENTE LIQUIDO');
});

test('Jerarquía: el producto se reasigna con «Mover», no con un desplegable por fila', async () => {
  jerarquiaCon(MAQUINA_3, FRAGANCIAS_M3);
  render(<AdminApp />);
  irA('Jerarquía');

  await screen.findByText('Máquina 3');
  const fila = screen.getByRole('listitem', {
    name: 'DETERGENTE LIQUIDO · COMISARIATO · 2.9 LT',
  });
  /* La cabecera del grupo ya dice el producto: repetirlo en un desplegable fijo por
     fila era lo que ensuciaba la lista. */
  expect(within(fila).queryByRole('combobox')).not.toBeInTheDocument();

  fireEvent.click(within(fila).getByRole('button', { name: 'Mover' }));
  fireEvent.change(within(fila).getByRole('combobox'), { target: { value: 'SUAVIZANTE' } });
  /* Elegir en el desplegable no aplica nada: hace falta confirmar. Sin este paso no
     había forma de arrepentirse, ni de cerrar la fila si se elegía el producto que ya
     tenía —el `onChange` no llega a dispararse y se quedaba abierta. */
  expect(mockAdmin.put).not.toHaveBeenCalled();

  fireEvent.click(within(fila).getByRole('button', { name: 'Aplicar' }));
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/maquina_productos/63', {
      producto: 'SUAVIZANTE',
    })
  );
});

test('Jerarquía: «Cancelar» cierra el mover sin tocar nada', async () => {
  jerarquiaCon(MAQUINA_3, FRAGANCIAS_M3);
  render(<AdminApp />);
  irA('Jerarquía');

  await screen.findByText('Máquina 3');
  const fila = screen.getByRole('listitem', {
    name: 'DETERGENTE LIQUIDO · COMISARIATO · 2.9 LT',
  });

  fireEvent.click(within(fila).getByRole('button', { name: 'Mover' }));
  fireEvent.change(within(fila).getByRole('combobox'), { target: { value: 'SUAVIZANTE' } });
  fireEvent.click(within(fila).getByRole('button', { name: 'Cancelar' }));

  expect(mockAdmin.put).not.toHaveBeenCalled();
  expect(within(fila).getByRole('button', { name: 'Mover' })).toBeInTheDocument();
});

test('Jerarquía: mover al producto que ya tiene no se puede aplicar', async () => {
  jerarquiaCon(MAQUINA_3, FRAGANCIAS_M3);
  render(<AdminApp />);
  irA('Jerarquía');

  await screen.findByText('Máquina 3');
  const fila = screen.getByRole('listitem', {
    name: 'DETERGENTE LIQUIDO · COMISARIATO · 2.9 LT',
  });

  /* Al abrirlo, el desplegable ya viene con su producto actual: aplicar eso sería un
     PUT que no cambia nada, así que el botón nace deshabilitado y «Cancelar» es la
     salida. */
  fireEvent.click(within(fila).getByRole('button', { name: 'Mover' }));
  expect(within(fila).getByRole('button', { name: 'Aplicar' })).toBeDisabled();
});

test('Jerarquía: una marca que ya no se produce conserva sus fragancias, aparte', async () => {
  jerarquiaCon(MAQUINA_3, FRAGANCIAS_M3);
  render(<AdminApp />);
  irA('Jerarquía');

  await screen.findByText('Máquina 3');
  /* HIT no tiene ninguna combinación, así que no tiene sitio en la jerarquía. Si no
     saliera en ningún lado, su LAVANDA quedaría configurada sin que nadie pudiera
     verla ni quitarla. */
  expect(screen.getByText('Fragancias de marcas que esta máquina ya no produce'))
    .toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Quitar LAVANDA de HIT · Máquina 3' }))
    .toBeInTheDocument();
  // Y no se cuela como marca de la jerarquía.
  expect(screen.queryByRole('heading', { level: 4, name: 'HIT' })).not.toBeInTheDocument();
});

test('Jerarquía: las combinaciones se agrupan por producto cuando hay más de uno', async () => {
  jerarquiaCon(MAQUINA_3);
  render(<AdminApp />);
  irA('Jerarquía');

  await screen.findByText('Máquina 3');
  /* El producto pasa a ser la cabecera del grupo, no un dato perdido al final de la
     fila. Se busca por encabezado y no por texto: «SUAVIZANTE» aparece también en
     cada desplegable de producto, que es exactamente el sitio donde no se veía. */
  const cabeceras = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent);
  expect(cabeceras).toContain('SUAVIZANTE');
  expect(cabeceras).toContain('DETERGENTE LIQUIDO');
  // Y la máquina dice de cuántos productos habla, no solo de cuántas filas.
  expect(screen.getByText('5 combinaciones · 3 productos')).toBeInTheDocument();
});

test('Jerarquía: el grupo sin producto se rotula y va el último', async () => {
  jerarquiaCon(MAQUINA_3);
  render(<AdminApp />);
  irA('Jerarquía');

  await screen.findByText('Máquina 3');
  /* Un producto vacío es comodín, no un hueco: si no se dijera, la fila parecería
     estar a medio configurar. Va al final porque es lo que falta por clasificar. */
  const grupos = screen.getAllByRole('heading', { level: 3 });
  expect(grupos.map((g) => g.textContent)).toEqual([
    'DETERGENTE LIQUIDO', 'SUAVIZANTE', 'Sin producto · vale para cualquiera',
  ]);
});

test('Jerarquía: dos combinaciones de la misma marca y presentación se borran sin confundirse', async () => {
  jerarquiaCon(MAQUINA_3);
  render(<AdminApp />);
  irA('Jerarquía');

  await screen.findByText('Máquina 3');
  /* COMISARIATO · 2.9 LT existe dos veces con productos distintos. La confirmación
     tiene que decir CUÁL se borra: sin el producto delante, las dos preguntan lo
     mismo y se puede dar de baja la que no era. */
  /* Las dos filas dicen «2.9 LT» a secas: lo que las separa es el producto que
     encabeza su grupo. */
  expect(screen.getAllByText('2.9 LT')).toHaveLength(2);

  /* La fila se localiza por su nombre accesible, que sí las distingue. */
  const fila = screen.getByRole('listitem', {
    name: 'DETERGENTE LIQUIDO · COMISARIATO · 2.9 LT',
  });
  fireEvent.click(within(fila).getByRole('button', { name: 'Eliminar' }));
  expect(window.confirm).toHaveBeenCalledWith(
    expect.stringContaining('DETERGENTE LIQUIDO · COMISARIATO · 2.9 LT')
  );
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/maquina_productos/63', { activo: false })
  );
});

test('Jerarquía: una máquina de un solo producto no gana cabeceras de grupo', async () => {
  render(<AdminApp />);
  irA('Jerarquía');

  /* Máquina 7 es SÓLIDO y solo hace detergente en polvo: agrupar ahí sería una
     cabecera que no distingue nada, repetida en cada máquina de la planta. */
  await screen.findByText('Máquina 7');
  expect(screen.getByRole('listitem', { name: 'COMISARIATO · 1.2 KG' })).toBeInTheDocument();
  expect(screen.getByText('2 combinaciones')).toBeInTheDocument();
  /* Sin cabecera de producto (nivel 3), pero sí de marca (nivel 4): por marca se agrupa
     siempre, porque es de quien cuelgan las fragancias. */
  expect(screen.queryAllByRole('heading', { level: 3 })).toHaveLength(0);
  expect(screen.getAllByRole('heading', { level: 4 }).map((h) => h.textContent))
    .toEqual(['COMISARIATO', 'TORBELLINO']);
});

test('Jerarquía: las fragancias van por marca, no por presentación', async () => {
  render(<AdminApp />);
  irA('Jerarquía');
  await screen.findByText('Máquina 7');

  /* Las fragancias ya no viven en un bloque aparte: cuelgan de la marca dentro de la
     propia jerarquía, que es donde se está mirando qué hace la máquina. */
  expect(screen.queryByText('Fragancias por marca')).not.toBeInTheDocument();
  expect(screen.getByRole('heading', { level: 4, name: 'COMISARIATO' })).toBeInTheDocument();

  // La activa se muestra como chip con su acción de quitar...
  expect(screen.getByRole('button', {
    name: 'Quitar Floral de COMISARIATO · Máquina 7',
  })).toBeInTheDocument();
  // ...y la quitada sigue visible para poder reactivarla (baja lógica, no borrado).
  expect(screen.getByRole('button', {
    name: 'Reactivar Limón en COMISARIATO · Máquina 7',
  })).toBeInTheDocument();

  // TORBELLINO no tiene ninguna: la API cae al catálogo, así que la tablet ofrece
  // todas. Sin este aviso el hueco se leería como «esta marca no lleva fragancia».
  expect(screen.getByText('sin configurar · se ofrecen todas')).toBeInTheDocument();

  // El select de cada marca es independiente: uno por (máquina, marca).
  expect(screen.getByLabelText('Fragancia para COMISARIATO en Máquina 7')).toBeInTheDocument();
  expect(screen.getByLabelText('Fragancia para TORBELLINO en Máquina 7')).toBeInTheDocument();
});

test('Jerarquía: añadir una fragancia manda máquina y marca, sin presentación', async () => {
  render(<AdminApp />);
  irA('Jerarquía');
  await screen.findByText('Máquina 7');

  const select = screen.getByLabelText('Fragancia para TORBELLINO en Máquina 7');
  // Ninguna activa todavía, así que el desplegable ofrece el catálogo entero.
  expect(within(select).getAllByRole('option').map((o) => o.textContent))
    .toEqual(['Fragancia…', 'Floral', 'Limón']);

  fireEvent.change(select, { target: { value: 'Limón' } });
  fireEvent.click(screen.getAllByRole('button', { name: 'Añadir' })[1]);

  await waitFor(() =>
    expect(mockAdmin.post).toHaveBeenCalledWith('/maquina_fragancias', {
      maquina_id: 1, marca: 'TORBELLINO', fragancia: 'Limón',
    })
  );
});

test('Jerarquía: el desplegable no repite una fragancia ya activa', async () => {
  render(<AdminApp />);
  irA('Jerarquía');
  await screen.findByText('Máquina 7');

  // COMISARIATO ya tiene Floral activa: ofrecerla otra vez solo daría el 409 del
  // backend, así que se cae de la lista.
  const select = screen.getByLabelText('Fragancia para COMISARIATO en Máquina 7');
  expect(within(select).getAllByRole('option').map((o) => o.textContent))
    .toEqual(['Fragancia…', 'Limón']);
});

test('Jerarquía: quitar una fragancia hace PUT {activo:false}, nunca DELETE', async () => {
  render(<AdminApp />);
  irA('Jerarquía');
  await screen.findByText('Máquina 7');

  fireEvent.click(screen.getByRole('button', {
    name: 'Quitar Floral de COMISARIATO · Máquina 7',
  }));

  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/maquina_fragancias/51', { activo: false })
  );
  // El histórico de sesiones se cruza con la fragancia por texto: un borrado físico
  // lo dejaría colgando.
  expect(mockAdmin.delete).not.toHaveBeenCalled();
});

/* ─────────────────────────────────────────────────────────────────────────────
   Velocidad estándar por (máquina, presentación) — 2026-08-21

   Es la tercera jerarquía de esta pestaña y la única que NO viaja a las tablets: la
   app manda su lectura y la web la interpreta. Lo que se protege aquí es la
   granularidad (presentación, sin marca), que quitar sea baja lógica como todo lo
   demás, y que un rango imposible no salga de la web.
   ───────────────────────────────────────────────────────────────────────────── */

test('Jerarquía: la velocidad estándar va por presentación, no por marca', async () => {
  render(<AdminApp />);
  irA('Jerarquía');
  await screen.findByText('Máquina 7');

  /* Ya no hay un bloque «Velocidad estándar por presentación» al final: el rango vive
     en la fila de su gramaje, dentro de la jerarquía. */
  expect(screen.queryByText('Velocidad estándar por presentación · unidades/min'))
    .not.toBeInTheDocument();
  expect(within(screen.getByRole('listitem', { name: 'COMISARIATO · 1.2 KG' }))
    .getByLabelText('Velocidad mínima de 1.2 KG en Máquina 7')).toBeInTheDocument();

  // Lo guardado se precarga en las casillas: se corrige encima, no se vuelve a teclear.
  expect(screen.getByLabelText('Velocidad mínima de 1.2 KG en Máquina 7')).toHaveValue(32);
  expect(screen.getByLabelText('Velocidad máxima de 1.2 KG en Máquina 7')).toHaveValue(38);

  /* Se teclea el minuto y se enseña la hora calculada, nunca al revés: en planta se
     habla por hora, pero lo que manda la tablet son unidades por minuto. */
  expect(screen.getByText('≈ 1.920 – 2.280 /hora')).toBeInTheDocument();

  // 1 KG todavía no tiene rango: se dice, en vez de enseñar un 0 – 0 que parecería puesto.
  expect(screen.getByLabelText('Velocidad mínima de 1 KG en Máquina 7')).toHaveValue(null);
  expect(screen.getAllByText('sin meta').length).toBeGreaterThan(0);

  /* 500 GR lo tuvo, se quitó y además ya no se produce: sin combinación no tiene fila
     en la jerarquía, así que baja al bloque de las que quedaron sueltas — donde se
     puede seguir viendo y quitando lo que se le configuró. */
  expect(screen.getByText(
    'Velocidad de presentaciones que esta máquina ya no produce · unidades/min'
  )).toBeInTheDocument();
  expect(screen.getByText('quitada')).toBeInTheDocument();
  expect(screen.getByLabelText('Velocidad mínima de 500 GR en Máquina 7')).toHaveValue(50);
});

test('Jerarquía: dos combinaciones del mismo gramaje avisan de que la meta es una sola', async () => {
  jerarquiaCon(MAQUINA_3, FRAGANCIAS_M3);
  render(<AdminApp />);
  irA('Jerarquía');

  await screen.findByText('Máquina 3');
  /* maquina_presentacion_velocidad va por (máquina, presentación): 2.9 LT es una sola
     fila en la BD aunque salga bajo SUAVIZANTE y bajo DETERGENTE LIQUIDO. Teclear en
     una mueve la otra, así que hay que decirlo. */
  const avisos = screen.getAllByText(/misma meta en/);
  expect(avisos.length).toBeGreaterThan(0);
  expect(avisos.map((a) => a.textContent).join(' | ')).toContain('COMISARIATO');
});

test('Jerarquía: fijar la velocidad de una presentación sin estándar la crea', async () => {
  render(<AdminApp />);
  irA('Jerarquía');
  await screen.findByText('Máquina 7');

  fireEvent.change(screen.getByLabelText('Velocidad mínima de 1 KG en Máquina 7'), {
    target: { value: '28' },
  });
  fireEvent.change(screen.getByLabelText('Velocidad máxima de 1 KG en Máquina 7'), {
    target: { value: '32' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Aplicar velocidad de 1 KG en Máquina 7' }));

  await waitFor(() =>
    expect(mockAdmin.post).toHaveBeenCalledWith('/velocidad_estandar', {
      maquina_id: 1, presentacion: '1 KG', vel_min: 28, vel_max: 32,
    })
  );
});

test('Jerarquía: corregir un rango existente va por PUT y lo deja aplicando', async () => {
  render(<AdminApp />);
  irA('Jerarquía');
  await screen.findByText('Máquina 7');

  fireEvent.change(screen.getByLabelText('Velocidad mínima de 1.2 KG en Máquina 7'), {
    target: { value: '30' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Guardar velocidad de 1.2 KG en Máquina 7' }));

  /* `activo: true` va siempre: guardar un rango en una presentación dada de baja es
     decir que vuelve a aplicar, y si no, el cambio se guardaría sin efecto visible. */
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/velocidad_estandar/71', {
      vel_min: 30, vel_max: 38, activo: true,
    })
  );
});

test('Jerarquía: quitar la velocidad estándar es PUT {activo:false}, nunca DELETE', async () => {
  render(<AdminApp />);
  irA('Jerarquía');
  await screen.findByText('Máquina 7');

  fireEvent.click(screen.getByRole('button', { name: 'Quitar velocidad de 1.2 KG en Máquina 7' }));

  /* La confirmación dice qué se pierde y qué no: las lecturas siguen llegando, lo que
     se apaga es la comparación. Sin eso se leería como «deja de medirse la velocidad». */
  expect(window.confirm.mock.calls[0][0]).toMatch(/dejarán de compararse con un rango/);
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/velocidad_estandar/71', { activo: false })
  );
  expect(mockAdmin.delete).not.toHaveBeenCalled();
});

test('Jerarquía: un rango imposible no sale de la web', async () => {
  render(<AdminApp />);
  irA('Jerarquía');
  await screen.findByText('Máquina 7');

  fireEvent.change(screen.getByLabelText('Velocidad mínima de 1 KG en Máquina 7'), {
    target: { value: '40' },
  });
  fireEvent.change(screen.getByLabelText('Velocidad máxima de 1 KG en Máquina 7'), {
    target: { value: '20' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Aplicar velocidad de 1 KG en Máquina 7' }));

  /* El backend lo rechazaría igual; se para aquí para que el aviso salga al lado de lo
     que se acaba de teclear y no como un error de servidor. */
  expect(await screen.findByText(/El mínimo no puede ser mayor que el máximo/)).toBeInTheDocument();
  expect(mockAdmin.post).not.toHaveBeenCalled();
});

test('Jerarquía: el catálogo maestro de fragancias se da de alta como marcas y presentaciones', async () => {
  render(<AdminApp />);
  irA('Jerarquía');
  await screen.findByText('Máquina 7');

  fireEvent.change(screen.getByLabelText('Nueva fragancia'), {
    target: { value: '  Lavanda  ' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Agregar fragancia' }));

  await waitFor(() =>
    expect(mockAdmin.post).toHaveBeenCalledWith('/fragancias', { nombre: 'Lavanda' })
  );
});

test('Producción: los campos son selects de catálogo y solo guardan lo cambiado', async () => {
  render(<AdminApp />);
  irA('Producción');

  expect(await screen.findByText(/Sesión #330 · 2026-07-23 11:19/)).toBeInTheDocument();
  expect(screen.getByText('Pacas: 0 (0 reg.)')).toBeInTheDocument();

  /* Hay más de una sesión en pantalla, así que todo se busca DENTRO de la tarjeta
     de la #330: si no, «Guardar sesión» sería ambiguo. Cada tarjeta es una región
     con su nombre accesible. */
  const tarjeta = within(screen.getByRole('region', { name: 'Sesión #330' }));

  // Sin cambios no se manda ningún PUT: se avisa en la propia pestaña.
  fireEvent.click(tarjeta.getByRole('button', { name: 'Guardar sesión' }));
  expect(await screen.findByText('No hay cambios en esta sesión')).toBeInTheDocument();
  expect(mockAdmin.put).not.toHaveBeenCalled();

  const maquina = tarjeta.getByLabelText(/^maquina$/i, { selector: 'select' });
  expect(maquina.tagName).toBe('SELECT');
  fireEvent.change(maquina, { target: { value: 'Máquina 7' } });

  fireEvent.click(tarjeta.getByRole('button', { name: 'Guardar sesión' }));
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/sesiones/330', { maquina: 'Máquina 7' })
  );
});

test('Producción: CERRAR TURNO solo aparece en las sesiones activas', async () => {
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #330/);

  // Una sola sesión activa (#330) → un solo botón de cerrar.
  const cerrar = screen.getAllByRole('button', { name: 'CERRAR TURNO' });
  expect(cerrar).toHaveLength(1);

  fireEvent.click(cerrar[0]);
  await waitFor(() =>
    expect(mockAdmin.post).toHaveBeenCalledWith('/sesiones/330/cerrar')
  );
});

test('Producción: cerrar un turno con la tablet conectada avisa antes', async () => {
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #330/);

  fireEvent.click(screen.getByRole('button', { name: 'CERRAR TURNO' }));

  // La sesión 330 no está en SESIONES_ACTIVAS, así que no debe salir el aviso...
  expect(window.confirm.mock.calls[0][0]).not.toMatch(/SEÑALES DE VIDA/);
  expect(window.confirm.mock.calls[0][0]).toMatch(/paro abierto y los pedidos/);
  await waitFor(() => expect(mockAdmin.post).toHaveBeenCalled());
});

test('Producción: eliminar una sesión con producción pide teclear el número', async () => {
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #331/);

  const tarjeta = within(screen.getByRole('region', { name: 'Sesión #331' }));
  fireEvent.click(tarjeta.getByRole('button', { name: 'Eliminar' }));

  await waitFor(() => expect(window.prompt).toHaveBeenCalled());
  expect(window.prompt.mock.calls[0][0]).toMatch(/480 pacas/);
  await waitFor(() => expect(mockAdmin.delete).toHaveBeenCalledWith('/sesiones/331'));
});

test('Producción: si no se teclea bien el número, no se borra nada', async () => {
  window.prompt = jest.fn(() => '999');   // número equivocado
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #331/);

  const tarjeta = within(screen.getByRole('region', { name: 'Sesión #331' }));
  fireEvent.click(tarjeta.getByRole('button', { name: 'Eliminar' }));

  expect(await screen.findByText('Eliminación cancelada')).toBeInTheDocument();
  expect(mockAdmin.delete).not.toHaveBeenCalled();
});

test('Producción: un ADMINPLANTA no puede eliminar, pero sí cerrar turnos', async () => {
  mockNivel = 'ADMINPLANTA';
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #330/);

  expect(screen.getByRole('button', { name: 'CERRAR TURNO' })).toBeInTheDocument();
  expect(screen.getAllByRole('button', { name: 'Guardar sesión' })).not.toHaveLength(0);
  // Eliminar es irreversible y borra en cascada: solo SUPERADMIN.
  expect(screen.queryByRole('button', { name: 'Eliminar' })).not.toBeInTheDocument();
});

test('Producción: un usuario de CONSULTA no ve ninguna acción de escritura', async () => {
  mockNivel = 'CONSULTA';
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #330/);

  ['Guardar sesión', 'CERRAR TURNO', 'Eliminar'].forEach((rotulo) =>
    expect(screen.queryByRole('button', { name: rotulo })).not.toBeInTheDocument()
  );
});

test('Producción: el total despliega el historial de pacas, y se pide al abrirlo', async () => {
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #331/);

  // No se piden los registros de todas las sesiones al entrar: sería una petición
  // por sesión para algo que casi nunca se abre.
  expect(mockAdmin.get).not.toHaveBeenCalledWith('/sesiones/331/pallets');

  fireEvent.click(screen.getByRole('button', { name: 'Registros de pacas de la sesión 331' }));

  await waitFor(() => expect(mockAdmin.get).toHaveBeenCalledWith('/sesiones/331/pallets'));
  expect(await screen.findByLabelText('Pacas del registro 900')).toHaveValue(240);
  // La hora llega como «YYYY-MM-DD HH:MM:SS» y el input la necesita con T y sin segundos.
  expect(screen.getByLabelText('Fecha y hora del registro 901')).toHaveValue('2026-07-23T11:47');
});

test('Producción: cambiar solo la cantidad no manda la hora, y viceversa', async () => {
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #331/);
  fireEvent.click(screen.getByRole('button', { name: 'Registros de pacas de la sesión 331' }));
  await screen.findByLabelText('Pacas del registro 900');

  fireEvent.change(screen.getByLabelText('Pacas del registro 900'), { target: { value: '200' } });
  fireEvent.click(within(screen.getByRole('region', { name: 'Sesión #331' }))
    .getAllByRole('button', { name: 'Guardar' })[0]);

  /* Solo viaja lo que cambió: reenviar la hora sin tocarla le pondría los segundos
     a cero, porque el input `datetime-local` no los maneja. */
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/pallets/900', { cantidad_pacas: 200 })
  );
});

test('Producción: corregir la hora de un registro la manda sin la T', async () => {
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #331/);
  fireEvent.click(screen.getByRole('button', { name: 'Registros de pacas de la sesión 331' }));
  await screen.findByLabelText('Fecha y hora del registro 901');

  fireEvent.change(screen.getByLabelText('Fecha y hora del registro 901'), {
    target: { value: '2026-07-23T09:30' },
  });
  fireEvent.click(within(screen.getByRole('region', { name: 'Sesión #331' }))
    .getAllByRole('button', { name: 'Guardar' })[1]);

  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/pallets/901', { fecha_hora: '2026-07-23 09:30' })
  );
});

test('Producción: eliminar un registro de pacas es DELETE y pide confirmación', async () => {
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #331/);
  fireEvent.click(screen.getByRole('button', { name: 'Registros de pacas de la sesión 331' }));
  await screen.findByLabelText('Pacas del registro 900');

  const tarjeta = within(screen.getByRole('region', { name: 'Sesión #331' }));
  // El primer «Eliminar» de la tarjeta es el de la sesión entera; los siguientes,
  // los de cada registro.
  fireEvent.click(tarjeta.getAllByRole('button', { name: 'Eliminar' })[1]);

  expect(window.confirm.mock.calls[0][0]).toMatch(/240 pacas de las 08:15/);
  // Se ofrece la salida no destructiva, que cualquier operativo puede hacer.
  expect(window.confirm.mock.calls[0][0]).toMatch(/ponle 0 pacas/);
  await waitFor(() => expect(mockAdmin.delete).toHaveBeenCalledWith('/pallets/900'));
});

test('Producción: un ADMINPLANTA edita los registros pero no los borra', async () => {
  mockNivel = 'ADMINPLANTA';
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #331/);
  fireEvent.click(screen.getByRole('button', { name: 'Registros de pacas de la sesión 331' }));
  await screen.findByLabelText('Pacas del registro 900');

  const tarjeta = within(screen.getByRole('region', { name: 'Sesión #331' }));
  expect(tarjeta.getAllByRole('button', { name: 'Guardar' })).toHaveLength(2);
  expect(tarjeta.queryByRole('button', { name: 'Eliminar' })).not.toBeInTheDocument();
});

test('Producción: un CONSULTA ve el historial pero no puede tocarlo', async () => {
  mockNivel = 'CONSULTA';
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #331/);
  fireEvent.click(screen.getByRole('button', { name: 'Registros de pacas de la sesión 331' }));

  expect(await screen.findByLabelText('Pacas del registro 900')).toBeDisabled();
  expect(screen.getByLabelText('Fecha y hora del registro 900')).toBeDisabled();
  const tarjeta = within(screen.getByRole('region', { name: 'Sesión #331' }));
  expect(tarjeta.queryByRole('button', { name: 'Guardar' })).not.toBeInTheDocument();
});

test('Usuarios: la pestaña solo existe para un SUPERADMIN', async () => {
  mockNivel = 'ADMINPLANTA';
  const { unmount } = render(<AdminApp />);
  expect(screen.queryByRole('tab', { name: 'Usuarios' })).not.toBeInTheDocument();
  await screen.findByText('ALEX VALENZUELA');
  unmount();

  mockNivel = 'SUPERADMIN';
  render(<AdminApp />);
  expect(screen.getByRole('tab', { name: 'Usuarios' })).toBeInTheDocument();
  await screen.findByText('ALEX VALENZUELA');
});

test('Usuarios: alta con nivel, y la contraseña sin cifrar se señala', async () => {
  render(<AdminApp />);
  irA('Usuarios');
  await screen.findByText('Planta');

  // El backend migra la contraseña sola en el próximo login; hasta entonces se avisa.
  expect(screen.getByText('contraseña sin cifrar')).toBeInTheDocument();
  expect(screen.getByText('tú')).toBeInTheDocument();

  fireEvent.change(screen.getByLabelText('Nuevo usuario'), { target: { value: '  agarcia ' } });
  fireEvent.change(screen.getByLabelText('Contraseña del nuevo usuario'), {
    target: { value: 'clave-larga' },
  });
  fireEvent.change(screen.getByLabelText('Nivel del nuevo usuario'), {
    target: { value: 'SUPERADMIN' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Crear usuario' }));

  await waitFor(() =>
    expect(mockAdmin.post).toHaveBeenCalledWith('/usuarios', {
      username: 'agarcia', password: 'clave-larga', nivel_acceso: 'SUPERADMIN',
    })
  );
});

test('Usuarios: una contraseña corta no llega a salir de la web', async () => {
  render(<AdminApp />);
  irA('Usuarios');
  await screen.findByText('Planta');

  fireEvent.change(screen.getByLabelText('Nuevo usuario'), { target: { value: 'pepe' } });
  fireEvent.change(screen.getByLabelText('Contraseña del nuevo usuario'), {
    target: { value: '123' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Crear usuario' }));

  expect(await screen.findByText(/al menos 6 caracteres/)).toBeInTheDocument();
  expect(mockAdmin.post).not.toHaveBeenCalled();
});

test('Usuarios: «Eliminar» hace PUT {activo:false} y nunca DELETE', async () => {
  render(<AdminApp />);
  irA('Usuarios');
  await screen.findByText('Planta');

  // El propio usuario no puede desactivarse: su botón está deshabilitado.
  const botones = screen.getAllByRole('button', { name: 'Eliminar' });
  expect(botones[0]).toBeDisabled();

  fireEvent.click(botones[1]);   // Planta
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/usuarios/2', { activo: false })
  );
  expect(mockAdmin.delete).not.toHaveBeenCalled();
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
  /* El chip dice cuándo verá el mensaje, no si la tablet «está encendida»: con el
     WebSocket abierto sale al instante, y si no, queda en cola con la antigüedad del
     último contacto. El ONLINE/OFFLINE anterior salía del heartbeat con un umbral de
     60 s y marcaba OFFLINE a máquinas que estaban produciendo. */
  expect(screen.getByText('EN COLA')).toBeInTheDocument();
  expect(screen.getByText('contacto hace 21m')).toBeInTheDocument();
  expect(screen.getByText('AL INSTANTE')).toBeInTheDocument();
  expect(screen.getByText('conectada')).toBeInTheDocument();

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

/* ─────────────────────────────────────────────────────────────────────────────
   Caducidad por inactividad (2026-08-07)

   Quien corta de verdad es el backend (`INACTIVIDAD_MAX`, 15 min, en
   routers/admin.py). Lo que se prueba aquí es lo que hace la web: no dejar el
   panel abierto y con aspecto de operativo cuando ya no hay nadie delante, y
   revocar el token en el servidor al hacerlo — si solo se limpiara el estado
   local, el token seguiría vivo hasta que alguien lo usara.
   ───────────────────────────────────────────────────────────────────────────── */
const MINUTO = 60 * 1000;

test('Inactividad: a los 15 minutos sin tocar nada vuelve al login y revoca el token', async () => {
  jest.useFakeTimers();
  try {
    render(<AdminApp />);
    await screen.findByText('JONATHAN VICUÑA');

    // A los 14 minutos la sesión sigue abierta: el límite no se ha cumplido.
    await act(async () => { jest.advanceTimersByTime(14 * MINUTO); });
    expect(screen.getByText('SIGEP · Administración')).toBeInTheDocument();

    await act(async () => { jest.advanceTimersByTime(2 * MINUTO); });

    // `salir()` es POST /admin/logout: el token deja de valer también en el servidor.
    expect(mockSalir).toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(/inactividad/i);
  } finally {
    jest.useRealTimers();
  }
});

test('Inactividad: cualquier interacción reinicia la cuenta', async () => {
  jest.useFakeTimers();
  try {
    render(<AdminApp />);
    await screen.findByText('JONATHAN VICUÑA');

    await act(async () => { jest.advanceTimersByTime(14 * MINUTO); });
    // Una tecla a los 14 minutos: el reloj vuelve a cero.
    fireEvent.keyDown(window, { key: 'a' });
    await act(async () => { jest.advanceTimersByTime(14 * MINUTO); });

    expect(mockSalir).not.toHaveBeenCalled();
    expect(screen.getByText('SIGEP · Administración')).toBeInTheDocument();

    // Y desde ese momento sí caduca a los 15.
    await act(async () => { jest.advanceTimersByTime(2 * MINUTO); });
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeInTheDocument();
  } finally {
    jest.useRealTimers();
  }
});

test('Inactividad: el 401 del backend explica el motivo en el login', async () => {
  render(<AdminApp />);
  await screen.findByText('JONATHAN VICUÑA');

  /* El interceptor de adminApi llama al handler registrado con el `detail` del 401.
     Se distingue del reinicio del servicio, que trae otro texto. */
  const alCaducar = mockRegistrarCaducidad.mock.calls.at(-1)[0];
  await act(async () => {
    alCaducar('Sesión cerrada por inactividad. Vuelve a iniciar sesión.');
  });

  expect(screen.getByRole('button', { name: 'Entrar' })).toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent(/inactividad/i);
});

/* ─────────────────────────────────────────────────────────────────────────────
   Estado de la tablet (2026-08-07)

   `tablet_online` pasó a significar «tiene el WebSocket abierto», que es lo que
   decide si un mensaje sale al instante. Para cerrar un turno a mano hace falta
   otra cosa: saber si queda alguien trabajando, y eso incluye a una tablet que
   produce con la conexión caída. Por eso ahí cuenta también el último contacto.
   ───────────────────────────────────────────────────────────────────────────── */
function activasCon(extra) {
  mockAdmin.get.mockImplementation((url) => {
    if (url === '/sesiones_activas') {
      return Promise.resolve({ data: [{
        sesion_id: 330, maquina: 'Máquina 8', operador: 'KEVIN SORIANO',
        producto: 'ULTREX · 500 GR · Limón', inicio: '11:19', ...extra,
      }] });
    }
    if (url === '/sesiones')   return Promise.resolve({ data: SESIONES });
    if (url === '/catalogos')  return Promise.resolve({ data: CATALOGOS });
    if (url === '/operadores') return Promise.resolve({ data: OPERARIOS });
    return Promise.reject(new Error(`sin mock para ${url}`));
  });
}

test('Producción: una tablet sin conexión pero con contacto reciente también avisa', async () => {
  // Sin WebSocket, pero dio señales hace 5 minutos: puede haber alguien produciendo.
  activasCon({ tablet_online: false, segundos_desde_contacto: 300 });
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #330/);

  fireEvent.click(screen.getByRole('button', { name: 'CERRAR TURNO' }));
  expect(window.confirm.mock.calls[0][0]).toMatch(/SEÑALES DE VIDA/);
});

test('Producción: una tablet con el último contacto muy antiguo no avisa', async () => {
  // Dos horas sin dar señales: el turno está abandonado, cerrarlo no pisa a nadie.
  activasCon({ tablet_online: false, segundos_desde_contacto: 7200 });
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #330/);

  fireEvent.click(screen.getByRole('button', { name: 'CERRAR TURNO' }));
  expect(window.confirm.mock.calls[0][0]).not.toMatch(/SEÑALES DE VIDA/);
});

test('Producción: una tablet que nunca reportó no avisa (sin contacto no es contacto 0)', async () => {
  activasCon({ tablet_online: false, segundos_desde_contacto: null });
  render(<AdminApp />);
  irA('Producción');
  await screen.findByText(/Sesión #330/);

  fireEvent.click(screen.getByRole('button', { name: 'CERRAR TURNO' }));
  expect(window.confirm.mock.calls[0][0]).not.toMatch(/SEÑALES DE VIDA/);
});

/* ─────────────────────────────────────────────────────────────────────────────
   Correo (2026-08-07)

   La pantalla administra el servidor de salida, las tres listas de destinatarios y
   el reporte semanal de paros. Lo que se protege aquí: que la contraseña no viaje
   sin querer, que un campo vacío se entienda como «usa el del .env» y no como «sin
   destinatarios», y que la pestaña solo exista para un SUPERADMIN.
   ───────────────────────────────────────────────────────────────────────────── */

test('Correo: la pestaña solo existe para un SUPERADMIN', async () => {
  mockNivel = 'ADMINPLANTA';
  render(<AdminApp />);
  expect(screen.queryByRole('tab', { name: 'Correo' })).not.toBeInTheDocument();
  await screen.findByText('JONATHAN VICUÑA');
});

test('Correo: muestra el servidor y de dónde sale cada lista, sin la contraseña', async () => {
  render(<AdminApp />);
  irA('Correo');

  expect(await screen.findByLabelText('Servidor SMTP')).toHaveValue('smtp-mail.outlook.com');
  expect(screen.getByLabelText('Puerto SMTP')).toHaveValue('587');
  // La API nunca devuelve la contraseña: el campo arranca vacío y solo dice si existe.
  expect(screen.getByLabelText('Contraseña SMTP')).toHaveValue('');
  expect(screen.getByText('contraseña del .env')).toBeInTheDocument();

  // El origen de cada lista es visible: sin esto se editaría el .env creyendo que manda.
  expect(screen.getByText('heredado de reportes')).toBeInTheDocument();
  expect(screen.getAllByText('del servidor (.env)')).toHaveLength(2);
});

test('Correo: guardar el servidor sin tocar la contraseña no la manda', async () => {
  render(<AdminApp />);
  irA('Correo');

  const host = await screen.findByLabelText('Servidor SMTP');
  fireEvent.change(host, { target: { value: 'smtp.office365.com' } });
  fireEvent.click(screen.getByRole('button', { name: 'Guardar servidor' }));

  await waitFor(() => expect(mockAdmin.put).toHaveBeenCalled());
  const [ruta, cuerpo] = mockAdmin.put.mock.calls[0];
  expect(ruta).toBe('/correo');
  expect(cuerpo.smtp_host).toBe('smtp.office365.com');
  expect(cuerpo.smtp_port).toBe(587);            // número, no el texto del input
  expect('smtp_pass' in cuerpo).toBe(false);     // lo importante: no viaja vacía
});

test('Correo: añadir un destinatario manda la lista completa, y rechaza lo que no es correo', async () => {
  render(<AdminApp />);
  irA('Correo');

  const campo = await screen.findByLabelText('Añadir a copia de Reporte semanal de paros');
  fireEvent.change(campo, { target: { value: 'esto-no-es-un-correo' } });
  fireEvent.keyDown(campo, { key: 'Enter' });
  expect(mockAdmin.put).not.toHaveBeenCalled();
  expect(await screen.findByText(/no parece una dirección/)).toBeInTheDocument();

  fireEvent.change(campo, { target: { value: 'jflorez@detcuador.com' } });
  fireEvent.keyDown(campo, { key: 'Enter' });
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/correo', {
      semanal_cc: ['jflorez@detcuador.com'],
    })
  );
});

test('Correo: quitar el último destinatario avisa de que se volverá al .env', async () => {
  render(<AdminApp />);
  irA('Correo');

  const quitar = await screen.findByLabelText(
    'Quitar agarcia@detcuador.com de destinatarios de Reporte semanal de paros'
  );
  fireEvent.click(quitar);

  expect(window.confirm.mock.calls[0][0]).toMatch(/se volverán a usar los destinatarios/i);
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/correo', { semanal_to: [] })
  );
});

test('Correo: la vista previa enseña los números que saldrían, y «enviar ahora» confirma', async () => {
  render(<AdminApp />);
  irA('Correo');

  expect(await screen.findByText('20h 32m')).toBeInTheDocument();
  expect(screen.getByText(/17 paro\(s\) · media 1h 12m/)).toBeInTheDocument();
  expect(screen.getByText(/▲ 35.9% vs 15h 06m/)).toBeInTheDocument();
  expect(screen.getByText('MANTENIMIENTO')).toBeInTheDocument();
  expect(screen.getByText('Máquina 9')).toBeInTheDocument();
  /* El almuerzo no cuenta como tiempo perdido, y decirlo es lo que evita que alguien
     compare este total con el de /paros —que sí lo cuenta— y lo tome por un error. */
  expect(screen.getByText(/no se cuentan 3h 05m de almuerzo \(11 paros\)/)).toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Enviar ahora' }));
  expect(window.confirm.mock.calls[0][0]).toMatch(/última semana cerrada/i);
  await waitFor(() => expect(mockAdmin.post).toHaveBeenCalledWith('/correo/semanal_ahora'));
});

test('Correo: el botón de prueba manda a la lista de esa tarjeta', async () => {
  render(<AdminApp />);
  irA('Correo');

  const botones = await screen.findAllByRole('button', { name: 'Enviar correo de prueba' });
  expect(botones).toHaveLength(3);          // una por lista
  fireEvent.click(botones[0]);              // la primera tarjeta es el reporte semanal
  await waitFor(() =>
    expect(mockAdmin.post).toHaveBeenCalledWith('/correo/prueba', { tipo: 'semanal' })
  );
});

/* ─────────────────────────────────────────────────────────────────────────────
   Paros (2026-08-07)

   Nace para no tener que entrar a MySQL: así se borró el paro 105 sin ver de qué
   sesión colgaba. Lo que se protege: que solo viaje lo que se tocó, que mover el
   inicio avise (cambia de día y de semana en KPIs y reporte), y que los niveles
   se respeten.
   ───────────────────────────────────────────────────────────────────────────── */

test('Paros: lista los del rango con su estado y el desplegable trae el detalle', async () => {
  render(<AdminApp />);
  irA('Paros');

  expect(await screen.findByText(/ALMUERZO · Máquina 7/)).toBeInTheDocument();
  expect(screen.getByText('SIN CIERRE')).toBeInTheDocument();

  // Un paro sin cerrar avisa de que su duración es estimada, no un dato registrado.
  fireEvent.click(screen.getByRole('button', { name: 'Paro 118' }));
  expect(screen.getByText(/Duración estimada/)).toBeInTheDocument();
  expect(screen.getByLabelText('Comentario del paro 118')).toHaveValue('cambio de teflón');
  // La hora llega como "YYYY-MM-DD HH:MM:SS" y el input la muestra sin segundos.
  expect(screen.getByLabelText('Inicio del paro 118')).toHaveValue('2026-08-06T12:56');
});

test('Paros: solo viaja el campo que se tocó', async () => {
  render(<AdminApp />);
  irA('Paros');
  fireEvent.click(await screen.findByRole('button', { name: 'Paro 118' }));

  fireEvent.change(screen.getByLabelText('Comentario del paro 118'), {
    target: { value: 'cambio de teflón del tubo formador' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Guardar cambios' }));

  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/paros/118', {
      comentario: 'cambio de teflón del tubo formador',
    })
  );
  // Ni categoría ni horas: reenviar una hora intacta le pondría los segundos a cero.
  expect(window.confirm).not.toHaveBeenCalled();
});

test('Paros: mover el inicio avisa de que cambia de día y de semana', async () => {
  render(<AdminApp />);
  irA('Paros');
  fireEvent.click(await screen.findByRole('button', { name: 'Paro 118' }));

  fireEvent.change(screen.getByLabelText('Inicio del paro 118'), {
    target: { value: '2026-08-04T10:00' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Guardar cambios' }));

  expect(window.confirm.mock.calls[0][0]).toMatch(/cambiar de día y de semana/);
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/paros/118', { inicio_paro: '2026-08-04T10:00' })
  );
});

test('Paros: «Cerrar ahora» solo sale en los que siguen abiertos', async () => {
  render(<AdminApp />);
  irA('Paros');

  // El 130 está cerrado: no ofrece cerrarlo.
  fireEvent.click(await screen.findByRole('button', { name: 'Paro 130' }));
  expect(screen.queryByRole('button', { name: 'Cerrar ahora' })).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Paro 118' }));
  fireEvent.click(screen.getByRole('button', { name: 'Cerrar ahora' }));
  await waitFor(() => expect(mockAdmin.post).toHaveBeenCalledWith('/paros/118/cerrar', {}));
});

test('Paros: una categoría fuera de la lista no se pierde al editar', async () => {
  render(<AdminApp />);
  irA('Paros');
  fireEvent.click(await screen.findByRole('button', { name: 'Paro 99' }));

  /* Si el desplegable no la incluyera, el select caería en la primera opción y
     editar el comentario cambiaría la categoría sin querer. */
  expect(screen.getByLabelText('Categoría del paro 99')).toHaveValue('CATEGORÍA RARA');
  expect(screen.getByText(/no tiene turno asociado/)).toBeInTheDocument();
});

test('Paros: un ADMINPLANTA edita pero no borra; un CONSULTA no edita', async () => {
  mockNivel = 'ADMINPLANTA';
  const { unmount } = render(<AdminApp />);
  irA('Paros');
  fireEvent.click(await screen.findByRole('button', { name: 'Paro 130' }));
  expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Eliminar' })).not.toBeInTheDocument();
  unmount();

  mockNivel = 'CONSULTA';
  render(<AdminApp />);
  irA('Paros');
  fireEvent.click(await screen.findByRole('button', { name: 'Paro 130' }));
  expect(screen.queryByRole('button', { name: 'Guardar cambios' })).not.toBeInTheDocument();
  expect(screen.getByLabelText('Comentario del paro 130')).toBeDisabled();
});

/* El incidente del 2026-08-19: un supervisor cerró desde aquí el paro 237, que estaba
   vivo en la tablet de la Máquina 7. La tablet guarda su estado de paro en local y solo
   lo apaga cuando el servidor le contesta bien, así que se quedó con el botón verde y
   «no hay paro activo» al pulsarlo: una hora sin registrar producción. Lo que se protege
   abajo es lo único que puede evitarlo desde la web —avisar antes— y la salida cuando ya
   ha pasado: reabrir el paro de un clic. */

test('Paros: un paro EN CURSO avisa de que hay una tablet detrás', async () => {
  render(<AdminApp />);
  irA('Paros');
  fireEvent.click(await screen.findByRole('button', { name: 'Paro 142' }));

  expect(screen.getByText(/su botón de reanudar es local/)).toBeInTheDocument();
});

test('Paros: cerrar un paro EN CURSO avisa de que la tablet se queda sin reanudar', async () => {
  render(<AdminApp />);
  irA('Paros');
  fireEvent.click(await screen.findByRole('button', { name: 'Paro 142' }));
  fireEvent.click(screen.getByRole('button', { name: 'Cerrar ahora' }));

  /* Lo que importa del texto: que nombra la consecuencia concreta (el «no hay paro
     activo» que verá el operario) y la salida. Un «¿estás seguro?» genérico no habría
     frenado el cierre del 2026-08-19. */
  const texto = window.confirm.mock.calls[0][0];
  expect(texto).toMatch(/no hay paro activo/);
  expect(texto).toMatch(/vuelva a abrir/);
  await waitFor(() => expect(mockAdmin.post).toHaveBeenCalledWith('/paros/142/cerrar', {}));
});

test('Paros: cerrar un SIN CIERRE no habla de tablets, que ahí ya no hay ninguna', async () => {
  render(<AdminApp />);
  irA('Paros');
  fireEvent.click(await screen.findByRole('button', { name: 'Paro 118' }));
  fireEvent.click(screen.getByRole('button', { name: 'Cerrar ahora' }));

  const texto = window.confirm.mock.calls[0][0];
  expect(texto).toMatch(/Se registrará la hora actual/);
  expect(texto).not.toMatch(/tablet/);
});

test('Paros: «Reabrir» manda fin_paro vacío, que es lo que deja el paro abierto', async () => {
  render(<AdminApp />);
  irA('Paros');
  fireEvent.click(await screen.findByRole('button', { name: 'Paro 130' }));
  fireEvent.click(screen.getByRole('button', { name: 'Reabrir' }));

  expect(window.confirm.mock.calls[0][0]).toMatch(/no hay paro activo/);
  /* Cadena vacía, NO ausencia de la clave: el backend distingue «déjalo abierto» de
     «no toques el fin», y mandar la clave fuera dejaría el paro cerrado igual. */
  await waitFor(() => expect(mockAdmin.put).toHaveBeenCalledWith('/paros/130', { fin_paro: '' }));
});

test('Paros: «Reabrir» y «Cerrar ahora» nunca salen a la vez', async () => {
  render(<AdminApp />);
  irA('Paros');

  fireEvent.click(await screen.findByRole('button', { name: 'Paro 142' }));
  expect(screen.getByRole('button', { name: 'Cerrar ahora' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Reabrir' })).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Paro 130' }));
  expect(screen.getByRole('button', { name: 'Reabrir' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Cerrar ahora' })).not.toBeInTheDocument();
});

test('Paros: un CONSULTA no puede reabrir', async () => {
  mockNivel = 'CONSULTA';
  render(<AdminApp />);
  irA('Paros');
  fireEvent.click(await screen.findByRole('button', { name: 'Paro 130' }));

  expect(screen.queryByRole('button', { name: 'Reabrir' })).not.toBeInTheDocument();
});

/* Foto del paro y velocidad de máquina (2026-08-19). Las dos son datos que las tablets
   empiezan a mandar; lo que se protege aquí es que la web no finja tenerlos cuando no
   están, y que quitar una foto no se lleve por delante el registro del paro. */

test('Paros: la foto se pide al 8000 y se ve en el mismo visor que en /paros', async () => {
  render(<AdminApp />);
  irA('Paros');
  fireEvent.click(await screen.findByRole('button', { name: 'Paro 130' }));

  /* Mismo componente que la vista /paros, a propósito: aquí es donde se decide si la
     foto sirve o se quita, así que tiene que verse igual de bien. Y la URL resuelta al
     8000 por lo mismo que allí — con la ruta relativa el 3000 devuelve la SPA. */
  const foto = screen.getByAltText('Evidencia fotográfica · paro #130 de Máquina 7');
  expect(foto).toHaveAttribute('src', 'http://localhost:8000/static/paros/130_447_20260807115300.jpg');

  fireEvent.click(screen.getByRole('button', { name: /Ver a tamaño completo la evidencia · paro #130/ }));
  const visor = await screen.findByRole('dialog', { name: /paro #130 de Máquina 7/ });
  expect(within(visor).getByRole('link', { name: /Abrir aparte/ }))
    .toHaveAttribute('href', 'http://localhost:8000/static/paros/130_447_20260807115300.jpg');
});

test('Paros: «Quitar foto» solo sale si hay foto, y no toca el paro', async () => {
  render(<AdminApp />);
  irA('Paros');

  // El 118 no tiene foto: no ofrece quitarla ni deja un recuadro vacío.
  fireEvent.click(await screen.findByRole('button', { name: 'Paro 118' }));
  expect(screen.queryByRole('button', { name: 'Quitar foto' })).not.toBeInTheDocument();
  expect(screen.queryByAltText(/paro #118/)).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Paro 130' }));
  fireEvent.click(screen.getByRole('button', { name: 'Quitar foto' }));

  /* Va a la subruta /foto, NO al paro: borrar el paro para arreglar una foto movida
     perdería el registro del tiempo parado. */
  expect(window.confirm.mock.calls[0][0]).toMatch(/El paro y su duración se quedan como están/);
  await waitFor(() => expect(mockAdmin.delete).toHaveBeenCalledWith('/paros/130/foto'));
});

test('Producción: la velocidad media sale solo cuando hay lecturas', async () => {
  render(<AdminApp />);
  irA('Producción');

  // La sesión 330 tiene 6 lecturas: promedio y recuento juntos, que es lo que da contexto.
  expect(await screen.findByText(/43,2 unidades\/min · 6 lecturas/)).toBeInTheDocument();
  /* La 331 no tiene ninguna, y no se pinta un «—» permanente: las sesiones anteriores al
     cambio nunca van a tener lecturas y sería ruido en cada fila. */
  expect(screen.queryByText(/0 lecturas/)).not.toBeInTheDocument();
});

test('Producción: el promedio del turno se compara con la meta de esa presentación', async () => {
  render(<AdminApp />);
  irA('Producción');

  /* La 330 promedió 43,2 con una meta de 32–38: se salió, y se marca en ámbar. Se juzga
     el PROMEDIO y no la última lectura, al revés que el dashboard: aquí el turno ya
     terminó y lo que interesa es cómo fue entero. */
  const meta = await screen.findByText(/meta 32 – 38/);
  expect(meta).toHaveClass('text-sig-amber');

  /* La 331 no tiene meta configurada: no se pinta ninguna, ni siquiera vacía. */
  expect(screen.queryAllByText(/meta /).length).toBe(1);
});

test('Paros: eliminar avisa de que es definitivo y sugiere corregir las horas', async () => {
  render(<AdminApp />);
  irA('Paros');
  fireEvent.click(await screen.findByRole('button', { name: 'Paro 130' }));
  fireEvent.click(screen.getByRole('button', { name: 'Eliminar' }));

  expect(window.confirm.mock.calls[0][0]).toMatch(/borrado definitivo/);
  await waitFor(() => expect(mockAdmin.delete).toHaveBeenCalledWith('/paros/130'));
});

/* ─────────────────────────────────────────────────────────────────────────────
   Insumos (2026-08-07)

   Los endpoints de corrección existían desde antes y nunca tuvieron pantalla. Lo
   que se protege: que solo se editen CANTIDADES (las horas las escribe el flujo de
   bodega), que un campo vacío no se interprete como cero, y los niveles.
   ───────────────────────────────────────────────────────────────────────────── */

test('Insumos: marca el pedido cuyas cantidades no cuadran', async () => {
  render(<AdminApp />);
  irA('Insumos');

  expect(await screen.findByText(/Bobina · Máquina 7/)).toBeInTheDocument();
  expect(screen.getByText('descuadre')).toBeInTheDocument();      // 10 entregadas, 8 recibidas
  expect(screen.getByText('1 con cantidades distintas')).toBeInTheDocument();
});

test('Insumos: corregir manda solo la cantidad que cambió', async () => {
  render(<AdminApp />);
  irA('Insumos');

  const recibida = await screen.findByLabelText('Cantidad recibida del pedido 51');
  fireEvent.change(recibida, { target: { value: '10' } });
  fireEvent.click(screen.getAllByRole('button', { name: 'Guardar' })[0]);

  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/pedidos/51', { cantidad_recibida: 10 })
  );
});

test('Insumos: una cantidad negativa o con decimales ni sale de la web', async () => {
  render(<AdminApp />);
  irA('Insumos');

  const entregada = await screen.findByLabelText('Cantidad entregada del pedido 51');
  fireEvent.change(entregada, { target: { value: '-3' } });
  fireEvent.click(screen.getAllByRole('button', { name: 'Guardar' })[0]);

  expect(await screen.findByText(/números enteros de cero en adelante/)).toBeInTheDocument();
  expect(mockAdmin.put).not.toHaveBeenCalled();
});

test('Insumos: la cantidad solicitada no se puede editar', async () => {
  render(<AdminApp />);
  irA('Insumos');
  await screen.findByText(/Bobina · Máquina 7/);

  /* Es lo que pidió el operario desde la tablet: corregirla reescribiría la petición,
     no el hecho. Solo se editan las cantidades de lo que pasó después. */
  expect(screen.queryByLabelText(/Cantidad solicitada/)).not.toBeInTheDocument();
});

test('Insumos: corregir una entrega proactiva manda su cantidad', async () => {
  render(<AdminApp />);
  irA('Insumos');

  const cantidad = await screen.findByLabelText('Cantidad de la entrega 7');
  fireEvent.change(cantidad, { target: { value: '4' } });
  const botones = screen.getAllByRole('button', { name: 'Guardar' });
  fireEvent.click(botones[botones.length - 1]);

  await waitFor(() => expect(mockAdmin.put).toHaveBeenCalledWith('/entregas/7', { cantidad: 4 }));
});

test('Insumos: un ADMINBODEGA corrige cantidades pero no borra registros', async () => {
  /* Desde el reparto de áreas, Insumos es de bodega y de SUPERADMIN: un CONSULTA ni
     siquiera ve la pestaña (lo cubren los tests de niveles). */
  mockNivel = 'ADMINBODEGA';
  render(<AdminApp />);
  irA('Insumos');

  await screen.findByText(/Bobina · Máquina 7/);
  expect(screen.getByLabelText('Cantidad recibida del pedido 51')).not.toBeDisabled();
  expect(screen.getAllByRole('button', { name: 'Guardar' }).length).toBeGreaterThan(0);
  expect(screen.queryByRole('button', { name: 'Eliminar' })).not.toBeInTheDocument();
});

/* ─────────────────────────────────────────────────────────────────────────────
   Reportes de la app y comentarios de turno (2026-08-07)

   Lo que se protege: que los reportes se ATIENDAN en vez de borrarse (el borrado
   pierde el historial de qué falló en planta), y que atender deje quién y cuándo.
   ───────────────────────────────────────────────────────────────────────────── */

test('Reportes: separa pendientes de atendidos y dice quién atendió', async () => {
  render(<AdminApp />);
  irA('Reportes');

  expect(await screen.findByText('No permite ingresar pacas')).toBeInTheDocument();
  expect(screen.getByText('1 sin atender')).toBeInTheDocument();
  expect(screen.getByText('atendido por admin')).toBeInTheDocument();
  // Los comentarios de turno comparten pantalla pero son otra lista.
  expect(screen.getByText('Ya descansa hijito')).toBeInTheDocument();
});

test('Reportes: marcar atendido no borra nada', async () => {
  render(<AdminApp />);
  irA('Reportes');

  fireEvent.click((await screen.findAllByRole('button', { name: 'Marcar atendido' }))[0]);
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/reportes_app/3', { atendido: true })
  );
  expect(mockAdmin.delete).not.toHaveBeenCalled();
});

test('Reportes: uno ya atendido se puede reabrir', async () => {
  render(<AdminApp />);
  irA('Reportes');

  fireEvent.click(await screen.findByRole('button', { name: 'Reabrir' }));
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/reportes_app/2', { atendido: false })
  );
});

test('Reportes: eliminar recuerda que atender conserva el historial', async () => {
  render(<AdminApp />);
  irA('Reportes');
  await screen.findByText('No permite ingresar pacas');

  fireEvent.click(screen.getAllByRole('button', { name: 'Eliminar' })[0]);
  expect(window.confirm.mock.calls[0][0]).toMatch(/márcalo como atendido en vez de borrarlo/);
  await waitFor(() => expect(mockAdmin.delete).toHaveBeenCalledWith('/reportes_app/3'));
});

test('Reportes: corregir el texto no admite dejarlo vacío', async () => {
  render(<AdminApp />);
  irA('Reportes');
  await screen.findByText('No permite ingresar pacas');

  fireEvent.click(screen.getAllByRole('button', { name: 'Corregir texto' })[0]);
  const campo = screen.getByLabelText('Texto del reporte 3');
  fireEvent.change(campo, { target: { value: '   ' } });
  fireEvent.click(screen.getByRole('button', { name: 'Guardar' }));

  expect(await screen.findByText(/no puede quedar vacío/)).toBeInTheDocument();
  expect(mockAdmin.put).not.toHaveBeenCalled();

  fireEvent.change(campo, { target: { value: 'No permite ingresar pacas al reintentar' } });
  fireEvent.click(screen.getByRole('button', { name: 'Guardar' }));
  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith('/reportes_app/3', {
      texto: 'No permite ingresar pacas al reintentar',
    })
  );
});

test('Reportes: la pestaña es solo de SUPERADMIN', async () => {
  /* Reportes, Tablets, Usuarios y Correo son administración del sistema, no operación
     diaria: ni planta ni bodega los ven, y el backend responde 403 a sus endpoints. */
  for (const nivel of ['ADMINPLANTA', 'ADMINBODEGA', 'CONSULTA']) {
    mockNivel = nivel;
    const { unmount } = render(<AdminApp />);
    expect(screen.queryByRole('tab', { name: 'Reportes' })).not.toBeInTheDocument();
    unmount();
  }
});

/* ─────────────────────────────────────────────────────────────────────────────
   Tablets (2026-08-07)

   Limpieza del registro de dispositivos, que hasta ahora solo se podía hacer en
   MySQL. Lo que se protege: que «conectada» siga significando WebSocket abierto y
   que el borrado avise de que no es permanente si el equipo sigue vivo.
   ───────────────────────────────────────────────────────────────────────────── */

test('Tablets: distingue conectada de olvidada y cuenta las que no dan señales', async () => {
  render(<AdminApp />);
  irA('Tablets');

  expect(await screen.findByText('conectada')).toBeInTheDocument();
  expect(screen.getByText('sin señales')).toBeInTheDocument();
  expect(screen.getByText(/1 sin señales en más de un día/)).toBeInTheDocument();
});

test('Tablets: reasignar la máquina manda solo ese campo', async () => {
  render(<AdminApp />);
  irA('Tablets');

  const select = await screen.findByLabelText('Máquina de la tablet 7bf71ff0');
  /* Hay que esperar al catálogo: un <select> ignora un valor cuya opción todavía no
     existe y se quedaría en «sin máquina» — exactamente lo que vería un usuario que
     abre el desplegable antes de que carguen las máquinas. */
  await waitFor(() =>
    expect(Array.from(select.options).map((o) => o.value)).toContain('Máquina 7')
  );
  fireEvent.change(select, { target: { value: 'Máquina 7' } });
  fireEvent.click(screen.getAllByRole('button', { name: 'Guardar' })[1]);

  await waitFor(() =>
    expect(mockAdmin.put).toHaveBeenCalledWith(
      '/tablets/7bf71ff0-64b0-4a1e-9f0e-000000000000', { maquina: 'Máquina 7' })
  );
});

test('Tablets: el desplegable ofrece las máquinas del catálogo, no solo la actual', async () => {
  render(<AdminApp />);
  irA('Tablets');

  const select = await screen.findByLabelText('Máquina de la tablet 7bf71ff0');
  /* `Select` del sistema de diseño toma `opciones`, no children: pasarle <option> los
     ignora en silencio y el desplegable se queda con la máquina actual y nada más. */
  await waitFor(() =>
    expect(Array.from(select.options).map((o) => o.value)).toEqual(
      expect.arrayContaining(['', 'PRUEBA', 'Máquina 7'])
    )
  );
});

test('Tablets: quitar del registro avisa de que volverá si el equipo sigue encendido', async () => {
  render(<AdminApp />);
  irA('Tablets');
  await screen.findByText('conectada');

  fireEvent.click(screen.getAllByRole('button', { name: 'Quitar del registro' })[1]);
  expect(window.confirm.mock.calls[0][0]).toMatch(/volverá a aparecer/);
  expect(window.confirm.mock.calls[0][0]).toMatch(/No borra ninguna producción/);
  await waitFor(() =>
    expect(mockAdmin.delete).toHaveBeenCalledWith('/tablets/7bf71ff0-64b0-4a1e-9f0e-000000000000')
  );
});

test('Tablets: la pestaña es solo de SUPERADMIN', async () => {
  mockNivel = 'ADMINPLANTA';
  render(<AdminApp />);
  expect(screen.queryByRole('tab', { name: 'Tablets' })).not.toBeInTheDocument();
});

/* ─────────────────────────────────────────────────────────────────────────────
   Reparto de pestañas por nivel (2026-08-07)

   Cada nivel trabaja en su área. Ocultar la pestaña no es el permiso —el backend
   exige el nivel y responde 403— pero sí es lo que evita ofrecer a alguien una
   sección que no le corresponde.
   ───────────────────────────────────────────────────────────────────────────── */
const PESTANAS_PLANTA = ['Operarios', 'Producción', 'Paros', 'Checklists', 'Jerarquía', 'Mensajes'];
const PESTANAS_SISTEMA = ['Insumos', 'Reportes', 'Tablets', 'Usuarios', 'Correo'];

/* Las pestañas del admin son el PRIMER tablist de la página: los componentes de dentro
   traen los suyos (el filtro por línea de Operarios son otros tres `role="tab"`). */
const pestanas = () => within(screen.getAllByRole('tablist')[0]).getAllByRole('tab')
  .map((t) => t.textContent);

test('Niveles: un SUPERADMIN ve las once pestañas', async () => {
  render(<AdminApp />);
  expect(pestanas()).toEqual([
    'Operarios', 'Producción', 'Paros', 'Checklists', 'Jerarquía',
    'Insumos', 'Reportes', 'Mensajes', 'Tablets', 'Usuarios', 'Correo',
  ]);
  await screen.findByText('JONATHAN VICUÑA');
});

test('Niveles: un ADMINPLANTA ve solo las seis de planta', async () => {
  mockNivel = 'ADMINPLANTA';
  render(<AdminApp />);

  expect(pestanas()).toEqual(PESTANAS_PLANTA);
  PESTANAS_SISTEMA.forEach((p) =>
    expect(screen.queryByRole('tab', { name: p })).not.toBeInTheDocument()
  );
  await screen.findByText('JONATHAN VICUÑA');
});

test('Niveles: ADMIN se comporta como ADMINPLANTA', async () => {
  mockNivel = 'ADMIN';
  render(<AdminApp />);
  expect(pestanas()).toEqual(PESTANAS_PLANTA);
  expect(screen.queryByRole('tab', { name: 'Insumos' })).not.toBeInTheDocument();
  await screen.findByText('JONATHAN VICUÑA');
});

test('Niveles: un ADMINBODEGA solo ve Insumos, y es la que se abre', async () => {
  mockNivel = 'ADMINBODEGA';
  render(<AdminApp />);

  expect(pestanas()).toEqual(['Insumos']);
  /* La pestaña inicial del contenedor es «operarios», que este nivel no ve: tiene que
     caer en la primera suya en vez de quedarse en blanco. */
  expect(await screen.findByText(/Bobina · Máquina 7/)).toBeInTheDocument();
});

test('Niveles: un CONSULTA ve las de planta pero sin acciones', async () => {
  mockNivel = 'CONSULTA';
  render(<AdminApp />);

  expect(pestanas()).toEqual(PESTANAS_PLANTA);
  expect(screen.queryByRole('tab', { name: 'Usuarios' })).not.toBeInTheDocument();
  await screen.findByText('JONATHAN VICUÑA');
  expect(screen.queryByRole('button', { name: 'Añadir' })).not.toBeInTheDocument();
});

test('Niveles: un nivel sin áreas lo dice en vez de romperse', async () => {
  mockNivel = 'INVENTADO';
  render(<AdminApp />);

  expect(screen.queryAllByRole('tab')).toHaveLength(0);
  expect(screen.getByText(/no tiene ninguna sección asignada/)).toBeInTheDocument();
});
