/**
 * Proxy del servidor de desarrollo: reenvía /api al backend FastAPI, igual que hace
 * nginx en producción. Así el frontend llama siempre a la ruta relativa `/api` y no
 * hay CORS ni IPs incrustadas en el bundle.
 *
 * Por qué aquí y no con la clave "proxy" de package.json:
 *   Esa clave activa el host check de CRA (protección anti DNS-rebinding), que exige
 *   `allowedHosts` con la URL de LAN. CRA solo la calcula si la IP es privada
 *   (10.x / 172.16-31.x / 192.168.x) — ver react-dev-utils/WebpackDevServerUtils.js.
 *   La IP de este servidor es pública, así que `lanUrlForConfig` queda undefined y el
 *   dev server aborta con «options.allowedHosts[0] should be a non-empty string».
 *   Con setupProxy.js el proxy funciona igual y CRA mantiene su comportamiento por
 *   defecto. Solo afecta a desarrollo: en producción nginx sirve build/.
 */
const { createProxyMiddleware } = require('http-proxy-middleware');

module.exports = function (app) {
  app.use(
    '/api',
    createProxyMiddleware({
      target: 'http://127.0.0.1:8000',
      changeOrigin: true,
      ws: true, // el backend expone WebSockets bajo /api (tablets, insumos)
      logLevel: 'warn',
    })
  );
};
