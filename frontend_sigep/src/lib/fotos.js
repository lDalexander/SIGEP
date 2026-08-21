/**
 * URLs de los archivos que sirve el backend desde `/static/`.
 *
 * El problema que resuelve este módulo no es cosmético. La API devuelve las fotos como
 * rutas relativas (`/static/paros/130_447_20260807115300.jpg`, `foto_url` de
 * `/dashboard/paros` y de `/admin/paros`), pero **esa ruta no se puede usar tal cual en
 * la web**: nginx sirve el 3000 con `try_files $uri $uri/ /index.html` y solo proxea
 * `/api/`, así que `/static/paros/*.jpg` no encuentra archivo y cae al `index.html` de
 * la SPA. Comprobado el 2026-08-21: el 3000 responde **200 con 700 bytes de text/html**
 * a cualquier ruta de `/static/paros/`, mientras el 8000 devuelve la imagen. Un `<img>`
 * apuntado ahí no da error de red — recibe un HTML con código 200 y pinta el icono de
 * imagen rota, y el enlace «abrir a tamaño completo» abre la web otra vez.
 *
 * Es el mismo desajuste ya documentado para el APK (`CLAUDE.md` §1, «Distribución del
 * APK»): `http://…:3000/static/sigep_latest.apk` devuelve la SPA.
 *
 * Se resuelve **en el cliente y contra el 8000**, que escucha en `0.0.0.0` y es el mismo
 * origen del que las tablets descargan el APK, porque las dos alternativas están
 * prohibidas por las reglas del proyecto sin autorización expresa: añadir un
 * `location /static/` a nginx (regla 5) o servir las fotos bajo `/api/` (regla 2).
 *
 * `/static/` **no pide autenticación** —igual que las fotos de entregas y el APK—, así
 * que no hace falta token ni cabecera para que la imagen cargue.
 */

/* Puerto del backend. Está escrito también en `package.json` («proxy»),
   `setupProxy.js` y el unit de systemd; aquí hace falta porque el navegador tiene que
   pedir la imagen al backend directamente y no por el proxy, que solo cubre `/api/`. */
export const PUERTO_API = '8000';

/** ¿La ruta ya es una URL absoluta? Entonces no hay nada que resolver. */
const ES_ABSOLUTA = /^(https?:)?\/\//i;

/**
 * Convierte la ruta que devuelve la API en una URL que el navegador pueda cargar.
 *
 * @param   {string|null|undefined} ruta  p. ej. `/static/paros/130_447_…jpg`
 * @returns {string|null}  URL absoluta al backend, o `null` si no hay foto.
 *
 * Devuelve `null` —y no una cadena vacía— cuando no hay ruta, para que quien la use
 * pueda decidir con un `if` y no pinte un `<img src="">`, que el navegador resuelve
 * como «recarga esta página» y dispara una petición inútil.
 */
export function urlEstatica(ruta) {
  if (typeof ruta !== 'string') return null;
  const limpia = ruta.trim();
  if (!limpia) return null;

  // Ya absoluta (por si el backend cambiara de criterio): se respeta tal cual.
  if (ES_ABSOLUTA.test(limpia)) return limpia;

  // Cualquier cosa que no sea una ruta absoluta del servidor se devuelve intacta: no
  // se sabe contra qué resolverla y adivinar daría una URL peor que la original.
  if (!limpia.startsWith('/')) return limpia;

  const { protocol, hostname, port } = window.location;

  /* Si la web ya se está sirviendo desde el propio backend (algo que pasa al abrir el
     8000 a pelo), la ruta relativa es correcta y añadir el puerto solo estorbaría. */
  if (port === PUERTO_API) return limpia;

  return `${protocol}//${hostname}:${PUERTO_API}${limpia}`;
}

/** Alias con el nombre del caso de uso; hoy toda foto viene de `/static/`. */
export const urlFoto = urlEstatica;
