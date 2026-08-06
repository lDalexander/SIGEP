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

/** Handler que el contenedor del admin registra para volver al login ante un 401. */
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
      alCaducar();
    }
    return Promise.reject(error);
  }
);

/** POST /api/admin/auth — valida credenciales y guarda la sesión. */
export async function entrar(nombre, pin) {
  const { data } = await axios.post('/api/admin/auth', { nombre, pin }, { timeout: 8000 });
  const sesion = { token: data.token, username: data.username, nivel: data.nivel_acceso };
  guardarSesion(sesion);
  return sesion;
}

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
   Espejo de `NIVELES_OPERATIVOS` en `routers/admin.py`. Sirve solo para no
   enseñar controles que el servidor va a rechazar: quien decide es el backend,
   que exige el nivel en cada endpoint (403). Ocultar un botón no es un permiso. */
export const NIVELES_OPERATIVOS = ['SUPERADMIN', 'ADMIN', 'ADMINPLANTA', 'ADMINBODEGA'];

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

/** Extrae el mensaje de error que devuelve FastAPI en `detail`. */
export function mensajeDeError(error, porDefecto = 'No se pudo completar la operación') {
  return error?.response?.data?.detail || error?.message || porDefecto;
}
