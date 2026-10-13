# Módulo de insumos — SIGEP v1.7.0

**Fecha:** 2026-10-12
**Alcance:** 25 commits · 49 archivos · +5.805 / −213 líneas
**Versión de la app:** 1.6.0 → **1.7.0** (`versionCode` 13 → 14)
**Repositorio:** `lDalexander/SIGEP`, rama `main` (monorepo desde hoy)

---

## 1. Qué se pidió

Mejorar la sección de insumistas, que estaba construida pero casi sin usar. En concreto:

1. Centralizar los dos repositorios en uno solo.
2. Crear la base de datos de insumos de verdad y limpiar el código.
3. Implementar el BOM real (qué insumos puede pedir cada producción).
4. Que el operario elija de una lista y no escriba texto libre, para que el pedido no
   acabe en el insumista equivocado.
5. Que la foto de evidencia sea obligatoria también en el servidor.
6. Que el supervisor pueda confirmar en lugar del operario y verificar las entregas.
7. Que no puedan estar dos insumistas iguales conectados a la vez.
8. Implementar autorización.
9. Cargar la **producción planificada** desde la web, para que el insumista deje el
   material preparado sin esperar a que nadie lo pida.
10. Mejorar la pantalla del **supervisor** en la app: monitor de máquinas e insumistas,
    mensajería con historial y prioridades.
11. Llenar la sección **Insumos** de la web, que era un botón que no llevaba a nada.

---

## 2. Qué había antes

El diagnóstico inicial encontró nueve problemas. Conviene dejarlos escritos porque
explican por qué el módulo estaba hecho y sin usar:

| # | Problema | Dónde |
|---|---|---|
| 1 | **El catálogo de insumos estaba triplicado.** La tabla `insumos` (6 filas de ejemplo), una lista quemada en el modal del operario y **otra distinta** en el formulario del insumista. Ninguna coincidía. Añadir un insumo exigía publicar un APK. | `MainActivity.kt:2443`, `BandejaInsumosActivity.kt` |
| 2 | **El endpoint correcto existía y nadie lo llamaba.** `insumos_permitidos` calculaba el BOM y la app jamás lo invocaba. Igual el historial de entregas del insumista. | `ProduccionApi.kt:101` y `:156` |
| 3 | **La app escuchaba eventos que el backend nunca emitía.** `/entregar` y `/confirmar_recepcion` cerraban su parte en silencio. De ahí salieron los dos sondeos de parche: 15 s en el insumista, 5 s en el operario. | `insumos.py` |
| 4 | **El enrutado Empaque/Granel se adivinaba por palabras** dentro del texto libre del operario (`granel`/`preten`/`polvo`). Un pedido de «Lámina para polvo» se iba a Granel. | `insumos.py:36` |
| 5 | **La foto era obligatoria solo en el cliente**; por API se registraba una entrega sin evidencia. | `insumos.py` |
| 6 | **La entrega proactiva no se ataba a nada** salvo al nombre de una máquina, y nadie acusaba recibo. | — |
| 7 | **`device_id` era decorativo.** La app documentaba que bloqueaba el doble login y que el servidor liberaba a las 9 h; el backend ni miraba el campo. | `ModelosRed.kt:197` |
| 8 | **Sin autorización.** `/api/insumos/pendientes/{rol}` recibía el rol **en la URL**: cualquiera veía y tomaba pedidos de cualquier bodega. `/entregar` no comprobaba nada. | `insumos.py` |
| 9 | **`/static/entregas/` público.** (Ya anotado en `CLAUDE.md`; no se tocó.) | nginx |

---

## 3. Lo que se hizo, fase por fase

### Fase 0 — Un solo repositorio

La app Android vivía en `lDalexander/SIPEG` y el backend en `lDalexander/SIGEP`. Se
fusionaron con `git subtree`, **conservando el historial completo**, y `SIPEG` quedó
archivado en GitHub de solo lectura.

```
SIGEP/
├── android/           app Kotlin de las tablets
├── api_produccion/    backend FastAPI
├── frontend_sigep/    web React
├── plantillas/        CSV de importación del catálogo y el BOM
├── pruebas/           entorno Docker + pruebas de regresión
└── backups/           respaldo diario de BD y fotos
```

**Por qué:** el contrato app↔API está congelado (los DTO de Gson mapean por nombre
literal contra los *schemas* de Pydantic). Con dos repos se podía tocar un lado y no el
otro, y nada lo impedía.

> **Android Studio abre `SIGEP/android`**, no la raíz. Un clon nuevo no trae
> `local.properties` ni `keystore.properties` (están gitignorados): hay que copiarlos o
> el build falla con *«SDK location not found»*.

### Fase 1 — La base de datos

Migración idempotente en `api_produccion/alter_insumos_v17.sql`, **aplicada en
producción a las 16:22** con volcado previo en
`backups/produccion_detg_pre_insumos_v17_20261012_162228.sql.gz`.

**Columnas añadidas** (todas NULL o con `DEFAULT`, ninguna existente cambió de tipo):

- `insumos`: `codigo_erp`, **`bodega`** (EMPAQUE/GRANEL)
- `recetas_productos`: `maquina`, `producto`, `cantidad_sugerida`, `activo`
- `pedidos_bodega`: `request_id`, `insumo_id`, `unidad`, `plan_id`, `confirmado_por_tipo`, `confirmado_por_id`
- `entregas_proactivas`: `request_id`, `insumo_id`, `session_id`, `plan_id`, `estado`, `verificado_por`, `fecha_verificacion`

**Tablas nuevas:** `planes_produccion`, `plan_insumos`, `sesiones_usuario`, `mensajes_bodega`.

**Importador CSV** (`importar_catalogo.py` + `services/importador_insumos.py`):

- La **pasada en seco es el modo por defecto**; `--aplicar` hay que escribirlo.
- Política: **añadir y actualizar; lo ausente queda `activo = 0`**. Nunca borra — los
  pedidos del histórico nombran esos insumos.
- Los dos archivos van en **una sola transacción**: si fallan las recetas, los insumos
  tampoco se guardan.
- La comparación **ignora mayúsculas y acentos** pero guarda el valor canónico de la
  base: un export que diga `LIMON` encuentra la fragancia `LIMÓN`.

### Fase 2 — Backend limpio

| Antes | Ahora |
|---|---|
| El pedido viajaba como texto libre | Viaja por **`insumo_id`**; el servidor escribe `detalle_pedido` desde el catálogo |
| La bodega se adivinaba por palabras | Sale de **`insumos.bodega`** |
| Pedir insumo exigía red | Entra en la **cola offline** con `request_id` |
| El BOM cruzaba 2 dimensiones e ignoraba la fragancia | Cruza las **5** con `NULL` = comodín |
| Foto obligatoria solo en el cliente | **Obligatoria en el servidor** |
| `device_id` decorativo | **Login único**: 409 si ya hay sesión en otra tablet |
| El cliente elegía su rol | **Token de sesión** en `sesiones_usuario` |
| `/entregar` y `/confirmar` no avisaban | Un **helper único** reparte los 4 eventos |

Dos detalles que no son evidentes:

- **El token del insumista vive en la base**, no en memoria como el del admin: un
  `restart sigep` vacía aquel, y a un insumista con la tablet en el bolsillo a media
  jornada eso lo deja tirado.
- **Si el BOM no casa, se devuelve el catálogo completo** en vez de una lista vacía —
  mismo criterio que `/api/fragancias`. Un hueco de configuración no puede dejar al
  operario sin poder pedir nada. Queda en el log como `BOM vacío para la sesión …`, que
  de paso es la lista de combinaciones sin recetar.

### Fase 3 — La app del operario y del insumista

- El modal del operario sale del **BOM del turno**, cacheado en disco para que funcione
  sin red, con la cantidad precargada de la receta.
- Pedir un insumo **entra en la cola offline** (`SOLICITAR_INSUMO`). Era la única
  escritura del operario fuera de ella.
- El formulario del insumista usa el **catálogo real de su bodega**, y recupera su
  **historial de entregas**.
- La app manda el token en **`X-User-Token`** (interceptor de OkHttp).
- Se quitó la lista blanca de `SigepWebSocketManager`, que descartaba 3 de los 5 eventos.

> Queda una **lista mínima de emergencia** por si una tablet nunca pudo descargar el
> catálogo: es peor (el pedido viaja sin `insumo_id`), pero un modal vacío pararía la
> línea.

### Fase 4 — Planificación de producción

Pestaña **Admin → Planificación** y endpoints `/api/planificacion/*`.

- `planes_produccion.maquina` es **NULL-able a propósito**: hay producción planificada
  que todavía no tiene máquina, y el material se deja para que luego cualquiera lo tome.
  Es el caso del que nace toda la función.
- Al crear un plan, el backend **explota el BOM** y genera su lista de insumos, editable.
- **La cobertura se calcula, no se guarda.** Cuentan las entregas atadas al plan y los
  pedidos por lo *recibido*. Lo solicitado no cuenta: pedir no es tener. Cada insumo
  **topa en su requerido**: traer el triple de cinta no compensa que falten las fundas.

### Fase 5 — El supervisor

Su pantalla era una lista de solo lectura. Ahora rota entre **cinco vistas**:

| Vista | Qué permite |
|---|---|
| **Planta** | Tocar una máquina → detalle del turno (producto, pacas, velocidad, paros, comentarios, pedidos) y **confirmar la recepción en lugar del operario** |
| **Plan** | Cobertura de cada plan del día; tocar un insumo lo marca **Normal / Alta / Urgente** |
| **Insumistas** | Quién está de turno, con cuánto lleva sin dar señales; tocar abre su **chat** |
| **Entregas** | Lo que falta por verificar, con las entregas **sin foto en rojo** |
| **Bodega** | El historial de 24 h que ya existía |

El supervisor entraba por `/api/admin/login`, que **no emitía token**: podía ver pero no
ejecutar nada. Ahora sí lo emite.

> Confirmar en lugar del operario **queda registrado como tal** (`confirmado_por_tipo =
> SUPERVISOR`). No es lo mismo: nadie en la máquina dijo haber recibido nada.

### Fase 6 — La vista `/insumos` de la web

Era un recuadro que decía *«Vista sin especificación de referencia»*. Ahora responde de
arriba abajo: **¿está cubierto lo que vamos a producir? → ¿hay alguien en bodega? → ¿qué
se pidió y cuánto tardó? → ¿qué se dejó preparado y con qué evidencia?**

Los tres tramos de tiempo van por separado porque dicen **dónde** se fue: tardar en
aceptar no es lo mismo que tardar en llegar.

No lleva el chat: la página es pública y los mensajes exigen identificarse.

---

## 4. Bugs encontrados por el camino

Cinco, cuatro de ellos introducidos durante este trabajo y cazados por las pruebas:

1. **`coroutine 'broadcast_to_tipo' was never awaited`.** Un envoltorio `def` síncrono
   devolvía la corrutina; `BackgroundTasks` la descartaba. La petición respondía 200 y
   **el aviso al insumista nunca salía**. Fallo totalmente silencioso. Por eso existe
   `pruebas/prueba_ws_pedidos.py`, que abre el WebSocket como la tablet y comprueba que
   los cuatro eventos llegan de verdad.
2. **«actividad hace −1 min»** en el diálogo del login único. MySQL redondea los
   milisegundos de `DATETIME`, el instante guardado quedaba una fracción en el futuro y
   la división entera lo volvía −1.
3. **El chat se leía descolocado.** Mismo origen: tres mensajes en el mismo segundo
   salían en orden arbitrario. Desempatado por `id`, que es lo único ordenado de verdad.
4. **Los desplegables de Planificación salían vacíos.** El `<Select>` del sistema de
   diseño recibe `opciones` y **no pinta sus hijos**. Compila perfectamente y falla solo
   en pantalla. Llegó a producción y se corrigió el mismo día.
5. **El logout borraba la identidad de la tablet.** `prefs.edit().clear()` se llevaba por
   delante el `device_id` y el nombre y la máquina configurados en Ajustes. Bug anterior
   a este trabajo, pero el login único lo volvía relevante.

---

## 5. Estado del despliegue

| Componente | Estado |
|---|---|
| Base de datos | ✅ migrada (16:22) |
| Backend | ✅ desplegado (última recarga 20:4x) |
| Web — pestaña Planificación | ✅ en vivo |
| Web — vista `/insumos` | ✅ en vivo (`main.6532d566.js`) |
| App 1.7.0 | ✅ compilada y firmada · ⏳ **sin instalar en las tablets** |

Respaldos: volcado previo de la BD en `backups/`, y el `build/` anterior de la web en
`~/workspace/archive/builds/respaldos_build_sigep/`.

**Nada se perdió en ningún despliegue.** Las pacas pasaron de 4.130 a 4.136 a lo largo
de la jornada: la planta siguió produciendo durante los reinicios y las tablets
reconectaron solas.

---

## 6. Qué queda pendiente

### Lo que bloquea que la planta lo use

1. **Instalar el APK 1.7.0 en las tablets** (sideload, una por una). Hasta entonces
   corre la 1.6.0; el backend es retrocompatible, así que la planta funciona, pero nadie
   ve lo de las fases 3 a 5.
2. **Cargar el catálogo del ERP** en `plantillas/insumos.csv` y `plantillas/recetas.csv`.
   Mientras estén vacíos, el BOM no casa y el operario ve el catálogo completo.
   - La columna **`bodega`** es la única que no sale del ERP: hay que ponerla a mano y
     es la que decide a qué insumista le suena la alarma.
   - Flujo: pasada en seco → ensayo con `--aplicar` contra `prueba_insumos_v17` →
     producción.

### Deuda consciente, a cerrar cuando todas las tablets sean 1.7.0

3. **La autorización no se exige.** Sin `X-User-Token` los endpoints caen al
   comportamiento anterior, porque las tablets 1.6.0 no lo mandan. El log anota
   `servida SIN token`: **cuando deje de aparecer, se puede volver obligatorio** y
   entonces el agujero queda cerrado del todo.
4. **La sesión caduca a las 9 h sin señales**, y las señales son las peticiones con
   token. Una tablet 1.6.0 no refresca nunca, así que su sesión se cierra sola aunque el
   insumista siga trabajando. Se corrige solo al instalar la 1.7.0.

### Oportunidades, no urgencias

5. **El BOM no escala por cantidad planificada.** `cantidad_sugerida` dice «cuánto se
   suele pedir de una vez», no «cuánto lleva cada unidad producida». Si se quiere que un
   plan de 5.000 unidades calcule solo cuántos millares de funda hacen falta, hace falta
   una columna de consumo por unidad en las recetas.
6. **El OTA sigue deshabilitado** (`/api/config/version` anuncia un `version_code` bajo).
   Con la 1.7.0 publicada se podría activar para no tener que ir tablet por tablet.
7. **`/static/` se sirve sin autenticación**: las fotos de evidencia son accesibles con
   la URL. Ya estaba así antes.
8. **Probar el flujo completo en planta** con una tablet real contra producción.

---

## 7. Operación

### Servidor

```bash
# Dos IPs, la misma máquina: directa y por VPN
ssh john@150.36.200.252      #  ó  ssh john@100.81.223.5
cd ~/workspace/apps/sigep

./reiniciar_servicios.sh            # sigep + nginx
./reiniciar_servicios.sh --status   # solo salud
```

### Cargar el catálogo

```bash
cd ~/workspace/apps/sigep/api_produccion

# 1) en seco — valida y NO escribe
venv/bin/python importar_catalogo.py --insumos ../plantillas/insumos.csv \
                                     --recetas ../plantillas/recetas.csv

# 2) ensayo contra la copia
venv/bin/python importar_catalogo.py --insumos ... --recetas ... \
    --bd 'mysql+mysqlconnector://admin:3012@localhost/prueba_insumos_v17' --aplicar

# 3) producción
venv/bin/python importar_catalogo.py --insumos ... --recetas ... --aplicar
```

### Desplegar la web

```bash
cd ~/workspace/apps/sigep/frontend_sigep
cp -r build ~/workspace/archive/builds/respaldos_build_sigep/build_$(date +%Y%m%d_%H%M%S)
CI=false npx --no-install react-scripts build

# Validar SIN desplegar (no toca lo que sirve nginx):
BUILD_PATH=/tmp/prueba CI=false npx --no-install react-scripts build
```

### Compilar la app

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
cd C:\Users\JohnAlexanderGarcia\AndroidStudioProjects\SIGEP\android
.\gradlew.bat clean assembleRelease     # SIN --offline: el lint necesita red
```

Llave **`llave_detcuador.jks`**, alias `sigep_key`. **Tiene que ser esa**: firmar con
otra obliga a desinstalar en cada tablet, y desinstalar **borra la cola offline**.

### Banco de pruebas

Base `prueba_insumos_v17` (espejo de producción) y una segunda API en el 8099:

```bash
DATABASE_URL='mysql+mysqlconnector://admin:3012@localhost/prueba_insumos_v17' \
  venv/bin/python -m uvicorn main:app --port 8099

fuser -k 8099/tcp     # para matarla. NUNCA pkill -f: el patrón casa con el propio ssh
```

> En esa copia, el admin `Planta` tiene la contraseña `clavePrueba1` para poder probar
> endpoints con token. **Solo en la copia**; producción no se tocó.

---

## 8. Decisiones que conviene no deshacer

- **`NULL` = comodín** en el BOM y en `maquina_productos`. Sin eso, 7 productos × 11
  marcas × 36 presentaciones × 9 fragancias son decenas de miles de filas a mano.
- **Nunca dejar al operario sin poder operar.** Si el BOM no casa, catálogo completo. Si
  no hay catálogo descargado, lista mínima. Una pantalla vacía para la línea.
- **Toda escritura nueva pasa por la cola offline**, con `request_id`. La app nunca
  depende de tener red.
- **El sondeo no se elimina, se releva.** El push es el camino rápido; el sondeo queda de
  red de seguridad para cuando el WebSocket está caído, que es cuando hace falta.
- **Los dos canales WebSocket hablan distinto** (`evento`+`solicitud_id` el de insumos,
  `tipo` el de tablets) y **no se unifican**: son contratos ya desplegados y cambiarlos
  dejaría sordas a las tablets sin actualizar.
- **El chat no usa la alarma industrial.** Esa es para un pedido que para la línea; si un
  mensaje sonara igual, en dos días nadie distinguiría las dos cosas.
- **Nada se borra.** Los insumos se dan de baja con `activo=0`, las entregas rechazadas
  se marcan, los mensajes no se eliminan. El histórico nombra esas filas.
- **Antes de reiniciar producción**, comprobar `sesiones_trabajo WHERE fin_turno IS NULL`
  y las pacas de los últimos 15 minutos. El reinicio es seguro, pero la comprobación va
  antes, no después.

---

## 9. Los 25 commits

```
337b237  chore: la app Android entra al monorepo y las plantillas suben a la raiz
1e07e85  feat(bd): esquema del modulo de insumos v1.7.0 (sin aplicar)
80c3423  chore(bd): la migracion de insumos v1.7.0 queda aplicada en produccion
4cf5e76  feat(insumos): modelos v1.7.0 e importador de catalogo y BOM desde CSV
34944d0  docs: como se cargan los CSV y que hace el importador con lo que ya esta
e255ea9  feat(auth): login unico por usuario y token de sesion en la base
b7c0282  fix(auth): el dialogo decia 'actividad hace -1 min' y nombraba la tablet por UUID
329650b  feat(insumos): el pedido viaja por insumo_id, el BOM cruza las 5 dimensiones…
c7865e0  feat(insumos): un solo helper reparte los avisos del ciclo de vida del pedido
c0f86f2  fix(insumos): el aviso al insumista se perdia, la corrutina nunca se esperaba
c4beb63  feat(insumos): el token manda sobre lo que el cliente dice ser
9b9cee3  feat(insumos): catalogo por bodega para el formulario del insumista
ae6b9a2  feat(app): la app manda el token de sesion y deja de filtrar eventos del WS
7b4ebe2  feat(app): el operario pide del BOM y el pedido entra en la cola offline
5c91727  feat(insumista): catalogo real en la entrega e historial de lo entregado
7e30605  chore: version 1.7.0 (versionCode 14) y documentacion del modulo de insumos
633d44b  feat(planificacion): planes de produccion del dia y cobertura de sus insumos
94b0619  feat(planificacion): la entrega proactiva puede apuntar a un plan
7424e4b  feat(web): pestana Planificacion con la cobertura de insumos de cada plan
097e583  feat(supervisor): torre de control, chat de bodega y cierre de pedidos…
e6770b0  fix(chat): la conversacion se leia descolocada dentro del mismo segundo
557b98c  feat(app): la torre de control del supervisor deja de ser solo lectura
cf2bb56  feat(insumista): chat con supervision y entrega contra un plan del dia
a69b83d  feat(web): la vista /insumos deja de estar vacia y arregla los desplegables…
1aec82f  feat: el supervisor ve el plan y prioriza desde la tablet, y el chat vuelve a avisar
```
