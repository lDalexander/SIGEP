import { urlEstatica, urlFoto, PUERTO_API } from './fotos';

/* Lo que se protege aquí es el fallo silencioso: la API devuelve `/static/paros/…` y esa
   ruta, servida por el 3000, no da un error de red — devuelve **200 con el index.html de
   la SPA**, porque nginx hace `try_files $uri $uri/ /index.html` y solo proxea `/api/`.
   Un `<img>` apuntado ahí pinta el icono de imagen rota sin que nada lo delate, que es
   exactamente el mismo desajuste ya documentado con el APK (`CLAUDE.md` §1).
   En jsdom `window.location` es `http://localhost/`. */

test('una ruta de /static se resuelve contra el puerto del backend', () => {
  expect(urlEstatica('/static/paros/130_447_20260807115300.jpg'))
    .toBe('http://localhost:8000/static/paros/130_447_20260807115300.jpg');
  expect(PUERTO_API).toBe('8000');
});

test('urlFoto es la misma función: un solo sitio donde se resuelve la URL', () => {
  expect(urlFoto).toBe(urlEstatica);
});

test('sin foto devuelve null, nunca una cadena vacía', () => {
  /* Un `<img src="">` el navegador lo resuelve como «vuelve a pedir esta página»: una
     petición inútil y un icono roto. Con `null` quien la use decide con un `if`. */
  expect(urlEstatica(null)).toBeNull();
  expect(urlEstatica(undefined)).toBeNull();
  expect(urlEstatica('')).toBeNull();
  expect(urlEstatica('   ')).toBeNull();
  expect(urlEstatica(0)).toBeNull();
});

test('una URL ya absoluta se respeta tal cual', () => {
  // Por si el backend cambiara de criterio y empezara a mandarlas completas.
  expect(urlEstatica('http://150.36.200.252:8000/static/paros/x.jpg'))
    .toBe('http://150.36.200.252:8000/static/paros/x.jpg');
  expect(urlEstatica('https://cdn.example.com/x.jpg')).toBe('https://cdn.example.com/x.jpg');
  expect(urlEstatica('//otro/x.jpg')).toBe('//otro/x.jpg');
});

test('lo que no es una ruta absoluta del servidor se devuelve intacto', () => {
  /* No se sabe contra qué resolverlo y adivinar daría una URL peor que la original. */
  expect(urlEstatica('paros/x.jpg')).toBe('paros/x.jpg');
});

test('si la web ya se sirve desde el 8000, la ruta relativa se deja como está', () => {
  /* Pasa al abrir el backend a pelo. Añadirle el puerto que ya tiene solo estorbaría. */
  const original = window.location;
  delete window.location;
  window.location = { protocol: 'http:', hostname: '150.36.200.252', port: '8000' };
  try {
    expect(urlEstatica('/static/paros/x.jpg')).toBe('/static/paros/x.jpg');
  } finally {
    window.location = original;
  }
});

test('se respeta el protocolo y el host desde el que se está viendo la web', () => {
  /* La planta entra por IP, pero si algún día se pone un nombre delante la foto tiene
     que seguir saliendo del mismo servidor y no de un host escrito a mano. */
  const original = window.location;
  delete window.location;
  window.location = { protocol: 'http:', hostname: 'sigep.detcuador.com', port: '3000' };
  try {
    expect(urlEstatica('/static/paros/x.jpg'))
      .toBe('http://sigep.detcuador.com:8000/static/paros/x.jpg');
  } finally {
    window.location = original;
  }
});
