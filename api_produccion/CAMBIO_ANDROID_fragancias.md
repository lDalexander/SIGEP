# Cambio pendiente en la app Android — fragancias por máquina y marca

**Fecha:** 2026-08-06
**Estado del backend:** ya desplegado y compatible. **No hay urgencia**: la app actual
sigue funcionando exactamente igual sin ningún cambio.

## Qué cambió en el servidor

La fragancia era **universal**: la app ofrecía la misma lista fija (Floral / Limón) en
cualquier máquina y con cualquier marca. Con la línea líquida entrando en producción eso
deja de ser cierto — cada máquina y cada marca hace fragancias distintas — así que la
fragancia pasa a ser parte de la jerarquía, y se administra desde la web
(`/admin` → Jerarquía → «Fragancias por marca»).

```sql
CREATE TABLE maquina_marca_fragancias (          -- tabla NUEVA
  id, maquina_id, marca, fragancia, activo,
  UNIQUE (maquina_id, marca, fragancia));
```

La granularidad es **(máquina, marca)**, sin la presentación: ULTREX 1 KG y ULTREX 3 KG
en la misma máquina llevan las mismas fragancias.

**`maquina_productos` no se tocó**, y por tanto **`GET /api/maquinas` responde byte a
byte igual que antes** (verificado con `diff` contra el servicio vivo). Lo mismo el resto
de endpoints que usa la app: 22 de 23 comparados salieron idénticos, y el único distinto
—`/dashboard/paros`— difería en las décimas de segundo del cronómetro de un paro abierto,
no por el código.

## Contrato del endpoint nuevo

`GET /api/fragancias` — **ruta nueva**, no había nada equivalente antes.

| Petición | Devuelve |
|---|---|
| `GET /api/fragancias` | el catálogo activo completo |
| `GET /api/fragancias?maquina=Máquina%207&marca=ULTREX` | las fragancias de esa combinación |

Respuesta: una lista de cadenas, lo que el spinner necesita directamente.

```json
["Floral", "Limón"]
```

**Nunca devuelve una lista vacía.** Si esa máquina+marca no tiene fragancias
configuradas, o la máquina no existe, devuelve el catálogo completo. El criterio es el
mismo que en `GET /api/maquinas` cuando una máquina no tiene jerarquía: una configuración
incompleta en la web **no puede** dejar al operario sin poder elegir fragancia y por tanto
sin poder iniciar el turno.

## Qué hay que hacer en la app

Sustituir la lista fija de fragancias por esta llamada, una vez que el operario ya eligió
máquina y marca:

```
GET /api/fragancias?maquina={MAQUINA}&marca={MARCA}
```

`maquina` es el `nombre` tal como lo devuelve `GET /api/maquinas` (con tilde: `Máquina 7`)
y `marca` el nombre de la marca de esa misma respuesta. Hay que **url-encodearlos**.

El flujo del inicio de turno queda: máquina → marca (de `/api/maquinas`) → **fragancia
(de `/api/fragancias`)** → presentación (de `/api/maquinas`).

## Precauciones

- **La caché offline.** La clave de caché debe incluir máquina **y** marca. Con una sola
  lista global cacheada, una tablet mostraría en TORBELLINO las fragancias que cargó para
  ULTREX.
- **Si la petición falla, no bloquear el turno.** Al no haber red, usar lo cacheado para
  esa máquina+marca y, si no hay nada, la lista fija de siempre. La fragancia no es un
  dato que valga interrumpir producción.
- **`iniciar_turno` NO valida la fragancia**, a propósito y por ahora. Valida
  `(máquina, marca, presentación)` contra `maquina_productos`, como siempre. Añadir la
  validación antes de que las tablets lean el endpoint nuevo rechazaría turnos legítimos:
  la app manda fragancias que quizá no estén configuradas todavía. Cuando la app ya lea
  `/api/fragancias` en todas las tablets, se puede activar la validación — es un cambio
  aparte y necesita su propia autorización.
- **El catálogo se amplía desde la web** (`/admin` → Jerarquía → «Agregar fragancia»), así
  que la app no debe llevar la lista de nombres hardcodeada más allá del fallback.
