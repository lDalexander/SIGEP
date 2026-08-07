import axios from 'axios';

/**
 * Cliente de la zona de administración.
 *
 * Es la única parte del backend autenticada: el login emite un token que viaja en la
 * cabecera `X-Admin-Token`. Ese token vive en la MEMORIA DEL PROCESO del servidor, así
 * que un `systemctl restart sigep` invalida todas las sesiones; por eso cualquier 401
 * se trata como sesión caducada y devuelve al login en lugar de mostrar un error.
 */
const CLAVE = 'sigep_admin_sesion';

export const admin = axios.create({ baseURL: '/api/admin', timeout: 8000 });

/** Handler que el contenedor del admin registra para volver al login ante un 401.
 *  Recibe el motivo que dio el backend (`detail`), que puede ser el de inactividad. */
let alCaducar = () => {};
export function registrarCaducidad(fn) {
  alCaducar = typeof fn === 'function' ? fn : () => {};
}

export function leerSesion() {
  try {
    const bruto = window.localStorage.getItem(CLAVE);
    return bruto ? JSON.parse(bruto) : null;
  } catch {
    return null;
  }
}

function guardarSesion(sesion) {
  try {
    window.localStorage.setItem(CLAVE, JSON.stringify(sesion));
  } catch {
    /* Modo privado o almacenamiento lleno: la sesión seguirá viva en memoria. */
  }
}

export function borrarSesion() {
  try {
    window.localStorage.removeItem(CLAVE);
  } catch {
    /* sin efecto */
  }
}

admin.interceptors.request.use((config) => {
  const sesion = leerSesion();
  if (sesion?.token) config.headers['X-Admin-Token'] = sesion.token;
  return config;
});

admin.interceptors.response.use(
  (respuesta) => respuesta,
  (error) => {
    if (error.response?.status === 401) {
      borrarSesion();
      alCaducar(error.response?.data?.detail || null);
    }
    return Promise.reject(error);
  }
);

/** POST /api/admin/auth — valida credenciales y guarda la sesión. */
export async function entrar(nombre, pin) {
  const { data } = await axios.post('/api/admin/auth', { nombre, pin }, { timeout: 8000 });
  const sesion = {
    token: data.token,
    username: data.username,
    nivel: data.nivel_acceso,
    inactividad: data.inactividad_segundos,
  };
  guardarSesion(sesion);
  return sesion;
}

/* ── Caducidad por inactividad (2026-08-07) ───────────────────────────────────
   Quien corta de verdad es el backend (`INACTIVIDAD_MAX` en routers/admin.py): la
   web no puede darse permiso a sí misma. Esto es para que el panel no se quede
   abierto y aparentemente usable después de que el token haya muerto.

   El límite lo manda el servidor en el login (`inactividad_segundos`); el valor de
   aquí solo cubre una sesión guardada por una versión anterior de la web. */
const INACTIVIDAD_POR_DEFECTO_S = 15 * 60;

/* La web cierra un poco antes que el servidor para que el aviso lo dé ella y no un
   401 a medio camino; el suelo de 60 s evita que un valor pequeño lo deje en cero. */
const MARGEN_MS = 20000;

export function msDeInactividad(sesion = leerSesion()) {
  const segundos = Number(sesion?.inactividad) || INACTIVIDAD_POR_DEFECTO_S;
  return Math.max(60000, segundos * 1000 - MARGEN_MS);
}

/** Texto que ve el usuario cuando es la propia web la que cierra la sesión. */
export const AVISO_INACTIVIDAD = 'Sesión cerrada por inactividad. Vuelve a iniciar sesión.';

/** POST /api/admin/logout — revoca el token en el servidor y limpia el local. */
export async function salir() {
  try {
    await admin.post('/logout');
  } catch {
    /* Si el token ya no era válido da igual: lo importante es limpiar el local. */
  }
  borrarSesion();
}

/* ── Niveles de acceso ────────────────────────────────────────────────────────
   Espejo de las áreas de `routers/admin.py`. Sirve solo para no enseñar controles
   que el servidor va a rechazar: quien decide es el backend, que exige el nivel en
   cada endpoint (403). Ocultar un botón no es un permiso.

   Reparto acordado el 2026-08-07:
     SUPERADMIN   todo
     ADMINPLANTA  planta: operarios, producción, paros, checklists, jerarquía, mensajes
     ADMIN        igual que ADMINPLANTA (nivel heredado)
     ADMINBODEGA  solo insumos
     CONSULTA     ve lo de planta, no escribe nada */
export const NIVELES_OPERATIVOS = ['SUPERADMIN', 'ADMIN', 'ADMINPLANTA', 'ADMINBODEGA'];

/** Quién trabaja en planta (escritura). */
export const NIVELES_PLANTA = ['SUPERADMIN', 'ADMINPLANTA', 'ADMIN'];
/** Quién VE lo de planta: los de arriba más CONSULTA, que mira sin tocar. */
export const NIVELES_VER_PLANTA = [...NIVELES_PLANTA, 'CONSULTA'];
/** Quién corrige insumos. */
export const NIVELES_BODEGA = ['SUPERADMIN', 'ADMINBODEGA'];
/** Administración del propio sistema: reportes, tablets, usuarios y correo. */
export const NIVELES_SISTEMA = ['SUPERADMIN'];

export function nivelActual(sesion = leerSesion()) {
  return String(sesion?.nivel || '').toUpperCase();
}

/** SUPERADMIN: además de todo lo operativo, gestiona usuarios y borra sesiones. */
export function esSuperadmin(sesion) {
  return nivelActual(sesion) === 'SUPERADMIN';
}

/** ¿Puede modificar datos? Falso para CONSULTA, que es de solo lectura. */
export function puedeEditar(sesion) {
  return NIVELES_OPERATIVOS.includes(nivelActual(sesion));
}

/** ¿Este nivel entra en esa área? `niveles` sale de las constantes de arriba. */
export function tieneAcceso(niveles, sesion) {
  return niveles.includes(nivelActual(sesion));
}

/** Extrae el mensaje de error que devuelve FastAPI en `detail`. */
export function mensajeDeError(error, porDefecto = 'No se pudo completar la operación') {
  return error?.response?.data?.detail || error?.message || porDefecto;
}
