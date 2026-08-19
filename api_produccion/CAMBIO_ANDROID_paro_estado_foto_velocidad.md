# Cambios pendientes en la app Android — estado del paro, foto del paro y velocidad

**Fecha:** 2026-08-19
**Estado del backend:** los tres endpoints son **rutas nuevas**; ninguna respuesta que la
app ya consume cambia de forma. La app actual sigue funcionando igual sin tocar nada.

Son tres cosas independientes. La primera arregla un fallo que **paró producción de
verdad**; las otras dos son funcionalidad nueva pedida por planta.

---

## 1. El paro fantasma (lo urgente)

### Qué pasó

El 2026-08-19, en la Máquina 7:

```
15:01:28  la tablet abre un paro (MANTENIMIENTO · «desembobinador»), sesión 531 → paro 237
15:19:43  un supervisor cierra ese mismo paro desde la web (/admin → Paros)
          ...la tablet no se entera de nada
          el botón sigue en verde, y al pulsar «reanudar» el servidor responde
          404 «No hay paro activo» → la app NO apaga su flag → bloqueo permanente
16:06:30  el operario reporta el problema desde la app
```

**Resultado: 1 h 34 min sin poder registrar un solo pallet.**

### Por qué la app se queda atascada

`paroActivo` vive en memoria de `MainActivity`, y solo se apaga dentro del callback de
éxito de `/api/paro/finalizar` (`llamarApiFinalizarParo$aplicarReanudacion`). Con
cualquier respuesta que no sea 2xx, el flag **se queda encendido y no hay forma de
bajarlo**: ni el heartbeat ni ninguna respuesta del servidor lo corrigen.

Se comprobó además que **no se persiste**: las claves de preferencias de la app son
`sesion_activa`, `turno_maquina`, `turno_operador` y `turno_pendiente_crear`, ninguna de
paro. Por eso hoy la única salida en planta es **cerrar la app y volver a abrirla** — y
eso es lo que hay que dejar de necesitar.

### Lo que ya se hizo en el servidor (desplegado el 2026-08-19)

`POST /api/paro/finalizar` **ya no responde 404** cuando la sesión tuvo paros y ninguno
está abierto. Devuelve **200** con los datos reales del último paro:

```json
{"paro_id": 237, "mensaje": "El paro ya estaba cerrado",
 "duracion_segundos": 1095.66, "ya_cerrado": true}
```

`ya_cerrado` **no aparece** cuando el cierre lo hace esta llamada (el camino normal), así
que se puede leer como opcional con `false` por defecto. Con esto **la app ya se desatasca
sola** al pulsar «reanudar», sin ningún cambio en la app.

### Lo que falta en la app

Que el botón no dependa de lo que la app recuerda, sino de lo que el servidor sabe.

**Endpoint nuevo:** `GET /api/paro/estado?sesion_id=531`

```json
{
  "sesion_id": 531,
  "turno_abierto": true,
  "en_paro": false,
  "paro_abierto": null,
  "ultimo_paro": {
    "paro_id": 237, "sesion_id": 531,
    "motivo": "[Mantenimiento] - desembobinador",
    "inicio_paro": "2026-08-19 15:01:28",
    "fin_paro": "2026-08-19 15:19:44",
    "duracion_segundos": 1095.66,
    "abierto": false, "tiene_foto": false
  }
}
```

- Es **de solo lectura**: no crea, no cierra y no modifica nada.
- `paro_abierto: null` **no es un error**: significa que la máquina no está en paro. Esa
  es la respuesta.
- `en_paro` es el booleano listo para pintar el botón, y `paro_abierto.paro_id` es el id
  con el que trabajar.
- `ultimo_paro` va aparte para que la app **reconozca el paro que tenía cacheado** y vea
  que ya se cerró, y cuándo.
- 404 solo si la `sesion_id` no existe (sesión borrada desde `/admin`): ahí lo correcto es
  mandar al operario a iniciar turno, no reintentar.

**Qué implementar:**

1. **Guardar `paro_id` al abrir el paro.** `POST /api/paro/iniciar` ya devuelve
   `{"paro_id": N, "mensaje": "Paro registrado"}` — hoy ese id **se descarta**.
   Persistirlo en preferencias (p. ej. `paro_activo_id`) junto al flag, de modo que
   sobreviva a que la app se cierre.
2. **Consultar `/api/paro/estado` y creer al servidor** en estos tres momentos:
   - al **arrancar** la app o restaurar la sesión (`sesion_activa`), para que el botón
     nazca correcto en vez de nacer de lo que había en memoria;
   - **antes de reanudar**, para no mandar un `finalizar` a un paro que ya no existe;
   - al **volver de segundo plano**, que es cuando más rato ha podido pasar.
   Con `en_paro: false` → apagar el flag y enseñar «REGISTRAR PARO». Con `en_paro: true` →
   encenderlo y guardar el `paro_id` que venga, aunque el local fuera otro.
3. **Sin red, no cambiar nada.** Si la consulta falla, **conservar el estado local**: es
   mejor un botón posiblemente desfasado que uno que se apaga porque se cayó el wifi. La
   corrección llega en la siguiente consulta con red.
4. **Salida manual, por si todo falla.** Una pulsación larga sobre el botón de paro que
   ofrezca «forzar reanudación» y apague el flag en local. Es lo que hoy se consigue
   matando la app, pero sin perder la sesión.

**Lo que NO hay que hacer:** apagar el flag cuando la respuesta sea un error de red. El
bloqueo de la Máquina 7 fue malo, pero un paro que se cancela solo porque hubo un timeout
es peor: perdería el registro del tiempo parado.

---

## 2. Registro fotográfico del paro (opcional)

Pedido por planta: poder adjuntar una foto a cada paro. **No es obligatoria** — un paro sin
foto es normal y el servidor no la exige en ningún sitio.

**La app ya sabe hacer esto.** Tiene «TOMAR FOTO», `fotoUri`, `fotoPart` y «Evidencia
fotográfica» funcionando para las entregas de insumo, y el servidor usa aquí **el mismo
patrón, los mismos límites y el mismo tipo de respuesta**. Es reutilizar esa pantalla, no
construir una.

**Endpoint nuevo:** `POST /api/paro/{paro_id}/foto` — `multipart/form-data`, campo `foto`.

| | |
|---|---|
| Tipos | JPG o PNG (`image/jpeg`, `image/png`) — otro da **400** |
| Tamaño | máximo **5 MB** — más da **400** |
| Respuesta | `{"paro_id": 237, "foto_url": "/static/paros/237_531_20260819150200.jpg", "mensaje": "Foto guardada"}` |
| Repetir | **se puede**: vuelve a llamar y sustituye la anterior (el operario que sale desenfocado necesita repetirla) |
| 404 | ese `paro_id` no existe |

**Va en una ruta aparte y no dentro de `/paro/iniciar` a propósito:** `iniciar` recibe
JSON, y pasarlo a multipart rompería a todas las tablets que hay hoy en planta. Así que el
orden es: primero `POST /api/paro/iniciar` (que ya devuelve el `paro_id`), y después la
foto contra ese id.

**Qué implementar:**

1. Botón de cámara **opcional** en la pantalla de paro. Que se pueda registrar el paro sin
   foto sin ninguna fricción: el dato que no se puede perder es el tiempo parado.
2. **Comprimir antes de subir.** Una foto de tablet moderna pasa de 5 MB de sobra y el
   servidor la rechazaría con 400. Redimensionar el lado largo a ~1600 px y JPEG con
   calidad ~80 deja archivos de 200-400 KB, más que suficiente para ver una pieza rota.
3. **Encolar la foto aparte del paro.** Si no hay red, el paro se registra igual y la foto
   se sube cuando vuelva: **nunca al revés**. Un paro que espera a que suba una foto es un
   paro que puede perderse.
4. La foto se puede subir **al abrir el paro o al cerrarlo** — el endpoint es el mismo, va
   por `paro_id`. Lo natural para un mantenimiento es «antes» al abrir y sustituirla al
   final si hace falta.

Se ve en la web en `/paros` y en `/admin` → Paros, y desde ahí se puede quitar si sale
mal. **Aviso:** `/static/` se sirve **sin autenticación** (igual que las fotos de
entregas), así que quien tenga la URL ve la imagen. Nada de fotos con información
sensible.

---

## 3. Velocidad de máquina (fundas/min)

Pedido por planta: un botón **debajo de «REGISTRAR PALLET»**, «VELOCIDAD MAQ», donde el
operario teclea a qué velocidad está trabajando la máquina. Es la base para poder medir
eficiencia contra las pacas registradas.

**Endpoint nuevo:** `POST /api/velocidad` — JSON.

```json
{"sesion_id": 531, "velocidad": 45.5, "request_id": "<uuid>"}
```

```json
{"velocidad_id": 12, "mensaje": "Velocidad registrada", "velocidad": 45.5, "duplicado": false}
```

| Caso | Respuesta |
|---|---|
| Fuera de 0.1–1000 fundas/min | **400** con el rango en el mensaje |
| Sesión inexistente | **404** |
| Turno ya finalizado | **400** |
| `request_id` repetido | **200** con `duplicado: true` — cuenta como éxito, no reintentar |

**Qué implementar:**

1. Botón **«VELOCIDAD MAQ»** debajo de «REGISTRAR PALLET», con un campo numérico. Unidad
   **fundas/min**: rotularlo en la pantalla, porque es lo único que evita que unos
   registren por minuto y otros por hora y el histórico quede inservible.
2. Decimales permitidos (`45.5` es válido). Teclado numérico con punto.
3. **`request_id` UUID, igual que en los pallets**, generado al encolar. Es imprescindible:
   sin él, una tablet sin red reintenta y la misma lectura entra varias veces, sesgando el
   promedio del turno. Ojo con el `""` por defecto de Gson — el servidor ya lo normaliza,
   pero lo correcto es mandar el UUID o no mandar la clave.
4. **Offline-first como todo lo demás**: encolar y reintentar. Un 400 **no** se reintenta
   (es una errata del operario o un turno cerrado); un 5xx sí.
5. **Es una lectura puntual, no un ajuste**: se pueden registrar varias en un turno y todas
   se guardan con su hora. No hay nada que «editar» desde la tablet.

Se ve en la web en el estado operativo del dashboard (última velocidad y promedio del
turno) y en `/admin` → Producción (promedio y número de lecturas).

---

## Resumen para quien lo implemente

| Endpoint | Método | Para qué |
|---|---|---|
| `/api/paro/estado?sesion_id=` | GET | saber si hay paro abierto de verdad (arreglo del bloqueo) |
| `/api/paro/{paro_id}/foto` | POST multipart | evidencia fotográfica opcional |
| `/api/velocidad` | POST JSON | velocidad en fundas/min, idempotente por `request_id` |

Ninguno de los tres es obligatorio para que la app siga funcionando como hoy. El orden por
impacto es: **1 (evita paradas de producción) → 3 (dato que hoy no existe) → 2 (mejora el
registro)**.
