# SIGEP — Centro de Control de Producción (Detcuador S.A.)

Sistema de control de producción de la planta de llenado. Dos consumidores de la misma
API: esta web (supervisión y administración) y una app Android en hasta 21 tablets
industriales con sincronización offline-first.

> **Regla de oro:** el backend (`api_produccion/`) y la base de datos NO se modifican sin
> autorización explícita del responsable. Cualquier cambio ahí puede romper las tablets en
> planta. Si la UI necesita algo que no existe, **hay que parar y preguntar**.

---

## 0. Estado actual

**Última sesión: 2026-07-31.** El frontend está reconstruido y **desplegado en producción**
(nginx, puerto 3000). Dashboard y las cinco pestañas de administración equivalentes a las
capturas de `referencia_ui/`. 65 tests en verde.

### Punto de recuperación

| | |
|---|---|
| Tag estable | `v1.0-frontend-reconstruido` (en GitHub) |
| Build de esa versión | `~/RESPALDO_build_estable_v1.0` |
| Builds rotados | `~/respaldos_build_sigep/` (últimos 10) |
| BD previa al último `ALTER` | `backups/produccion_detg_pre_tipo_operario_*.sql.gz` |

```bash
git checkout v1.0-frontend-reconstruido     # volver al frontend estable
```

### Cambios de backend ya autorizados y aplicados

Tras la reconstrucción se autorizó **una** excepción a la regla de oro, ya en producción:

- **Operarios clasificados por línea** (`operadores.tipo` = `SOLIDO` | `LIQUIDO`), igual que
  las máquinas. Migración en `api_produccion/alter_operadores_tipo.sql`.
- `GET /api/operadores` acepta `?tipo=` **opcional**. Sin el parámetro devuelve todos con el
  formato de siempre, así que **las tablets no necesitaron actualización**.
- La web permite alta por línea, filtro y cambio de línea desde `/admin` → Operarios.

### Pendiente

1. **App Android** — para que el selector «Seleccione Operador» filtre de verdad, la app
   debe pasar `?tipo=` con el tipo de su máquina. Instrucciones completas en
   `api_produccion/CAMBIO_ANDROID_tipo_operario.md`. **No corre prisa**: sin ese cambio
   todo sigue funcionando como antes.
2. **Aún no hay máquinas de línea líquida** dadas de alta; las 6 registradas son `SOLIDO`.
   Hasta que exista una, la clasificación de operarios no tiene efecto práctico.
3. **Logo provisional** — `public/logo192.png` es el de Create React App. El de la cabecera
   es un SVG hecho a partir de las capturas (`components/ui/Logo.js`); si aparece el
   original, se sustituye por un `<img>`.
4. **Botón «Insumos» de la cabecera** — aparece en las capturas pero no hay ninguna captura
   ni especificación de esa vista. Hoy muestra un aviso de «vista sin especificación de
   referencia». Falta decidir qué debe contener, o quitar el botón.
5. **Filtrado multi-selección perdido** — la versión anterior tenía segmentadores por
   máquina/operador/marca/presentación/fragancia. No aparecen en ninguna captura, así que
   se retiraron, pero el backend los sigue soportando en todos los endpoints del dashboard
   (`?maquina=A&maquina=B…`). Recuperables del commit `d5c0839`.

---

## 1. Entorno

| | |
|---|---|
| Servidor | `150.36.200.252` · Ubuntu 24.04 · TZ `America/Guayaquil` |
| Proyecto | `/home/john/Proyectos/App_Llenadora` |
| Backend | `api_produccion/` — FastAPI + SQLAlchemy, gunicorn/uvicorn en `127.0.0.1:8000` |
| Servicio | `sigep.service` (systemd, `Restart=always`, `-w 1`) |
| Frontend | `frontend_sigep/` — Create React App (React 19, JavaScript, sin TypeScript) |
| Web | nginx en el puerto **3000**, root `frontend_sigep/build`, `/api/` → `127.0.0.1:8000/api/` |
| BD | MySQL, esquema `produccion_detg` |
| Repo | GitHub `lDalexander/SIGEP`, rama `main` |
| Referencia visual | `referencia_ui/*.jpeg` — 9 capturas de la versión a reproducir |

Convive un apache2 en el puerto 80. **No reconfigurar nginx, systemd ni apache.**

### Comandos

```bash
# Desarrollo
cd frontend_sigep && npm start          # dev server en el 3001

# Desplegar a producción — usar SIEMPRE el script, nunca `npm run build` a mano
./deploy.sh --revisar                   # comprueba sin tocar nada
./deploy.sh                             # despliega

# Backend
sudo systemctl restart sigep && systemctl status sigep
curl -s http://127.0.0.1:8000/api/dashboard/kpis
```

### Por qué no se ejecuta `npm run build` a mano

`npm run build` **vacía su carpeta de destino nada más empezar**. Así se perdió el
código original: se compiló sobre un `src/` viejo y el resultado sobrescribió el único
build que tenía la versión buena.

`deploy.sh` lo hace imposible:

1. Aborta si hay cambios sin commitear.
2. Aborta si hay commits sin subir a GitHub — el fuente siempre está en el remoto
   **antes** de compilar.
3. Respalda el build vigente en `~/respaldos_build_sigep/build_<fecha>` (rota a 10).
4. Compila con `BUILD_PATH` a `.build-nuevo/`, así que si la compilación falla el build
   en producción sigue intacto y servido.
5. Solo intercambia los directorios cuando el resultado está completo y verificado.
6. Comprueba `/` y `/api` al terminar, e indica cómo revertir si algo va mal.

Para volver a una versión anterior basta con copiar el respaldo sobre `build/`:

```bash
ls ~/respaldos_build_sigep/
rm -rf frontend_sigep/build && cp -a ~/respaldos_build_sigep/build_<fecha> frontend_sigep/build
```

---

## 2. Frontend

### Stack disponible (ya instalado — no hace falta añadir nada)

| Paquete | Versión | Uso |
|---|---|---|
| `react` / `react-dom` | 19.2.5 | — |
| `react-scripts` | 5.0.1 | CRA |
| `tailwindcss` | **3.4.19** | **Sí hay Tailwind y está activo** |
| `axios` | 1.15.0 | Todas las llamadas HTTP |
| `recharts` | 3.8.1 | Gráficas |
| `lucide-react` | 1.8.0 | Iconos |

Los estilos se hacen con **clases de utilidad de Tailwind** (no objetos inline). La
cadena es `src/index.css` (`@tailwind base/components/utilities`) → `postcss.config.js`
(plugins `tailwindcss` + `autoprefixer`) → `tailwind.config.js` (tema extendido).

Notas de configuración:
- `@tailwindcss/postcss@4.2.2` está en `dependencies` pero **no se usa**: `postcss.config.js`
  carga el plugin v3. Es una dependencia huérfana; no romper nada tocándola.
- El `@import` de Google Fonts en `index.css` está **después** de las directivas
  `@tailwind`. Funciona (PostCSS lo reubica) pero lo correcto es ponerlo primero.
- **No hay `react-router`.** La navegación entre Dashboard y Admin se hace por estado
  en `App.js` (`activeView`) + History API.

### Estructura

```
frontend_sigep/
├── .env.development             # HOST/PORT del dev server (ver más abajo)
├── package.json                 # "proxy": "http://127.0.0.1:8000"
├── tailwind.config.js           # tokens del sistema de diseño (colores sig-*)
└── src/
    ├── index.js
    ├── index.css                # rejilla de fondo, fuentes, .sig-label / .sig-card / .sig-input
    ├── App.js                   # estado, polling, layout de dos columnas, navegación
    ├── setupProxy.js            # reenvío de /api al backend en desarrollo
    ├── lib/
    │   ├── format.js            # es-EC, duraciones, turno, antigüedad, etiqueta de tablet
    │   └── useApi.js            # GET con polling; conserva el último dato bueno
    ├── components/
    │   ├── Header.js            # cabecera: reloj, EN VIVO, Insumos, Admin
    │   ├── BarraTitulo.js       # sobre-título, H1, turno actual
    │   ├── FiltroFecha.js       # rango desde/hasta + Cargar + 3 descargas
    │   ├── KPICards.js          # las 3 tarjetas KPI
    │   ├── ProductionChart.js   # producción por hora (área)
    │   ├── OperationsTable.js   # estado operativo · líneas
    │   ├── EstadisticasProduccion.js  # ranking por agrupación y período
    │   ├── TerminalLog.js       # actividad en vivo
    │   ├── ChecklistMantenimiento.js  # tarjetas con anillo de progreso
    │   ├── TabletsSyncPanel.js  # chips de las 21 tablets
    │   ├── TopProductionChart.js # top marcas
    │   ├── SolicitudesInsumos.js # pedidos de insumo del rango
    │   ├── DetalleChecklist.js  # tabla a ancho completo, una columna por ítem
    │   ├── Footer.js
    │   └── admin/               # vista /admin
    │       ├── AdminApp.js      # login, cabecera propia y las 5 pestañas
    │       ├── AdminLogin.js
    │       ├── Ayuda.js, FiltroRango.js
    │       └── TabOperarios / TabProduccion / TabChecklists / TabJerarquia / TabMensajes
    └── components/ui/           # componentes base del sistema de diseño
        ├── Label, Badge, Button, Card, StatCard (+ Cifra)
        ├── ProgressBar, Ring, Tabs, Dot, Logo
        └── Estado (+ Esqueleto)  # carga / error / vacío
```

Todo componente se importa desde `./ui` (barrel en `ui/index.js`), y todo número pasa
por `lib/format.js`. Colores nuevos van a `tailwind.config.js`, nunca sueltos en el JSX.

### Componente → endpoint

| Componente | Endpoint |
|---|---|
| `KPICards` | `/dashboard/kpis` + derivación de `/dashboard/estado_operativo` |
| `ProductionChart` | `/dashboard/produccion_hora` |
| `OperationsTable` | `/dashboard/estado_operativo` |
| `EstadisticasProduccion` | `/dashboard/estadisticas` (fetch propio) |
| `TerminalLog` | `/dashboard/logs` |
| `ChecklistMantenimiento` | `/mantenimiento/checklist?limit=8` (fetch propio) |
| `TabletsSyncPanel` | `/tablets/estado`, `/tablets/sincronizar/{id}` (fetch propio) |
| `TopProductionChart` | `/dashboard/top_produccion` |
| `SolicitudesInsumos` | `/insumos/dashboard?desde&hasta` (fetch propio) |
| `DetalleChecklist` | `/mantenimiento/checklist?desde&hasta` (fetch propio) |
| `FiltroFecha` | `/reportes/excel`, `/reportes/formularios_excel`, `/reportes/insumos_excel` |

`App.js` refresca los cinco endpoints del dashboard cada 15 s con `Promise.allSettled`:
si uno falla, los demás se actualizan igual y el que falló conserva su último dato. Las
tarjetas marcadas como «fetch propio» usan el hook `lib/useApi.js`, con el mismo criterio.

Dos matices heredados de la API, documentados en el código:

- **`DetalleChecklist`** usa el mismo endpoint, criterio y orden que
  `/reportes/formularios_excel`, pero el Excel separa ENTRADA y SALIDA en hojas distintas
  (columnas de ítems homogéneas por hoja) mientras la tabla las mezcla con una columna
  `MOMENTO`. Las columnas son por tanto la unión de los ítems de ambos momentos; un ítem
  que un momento no usa aparece como «–».
- **`SolicitudesInsumos`** no puede recortar a 24h exactas: `/insumos/dashboard` filtra
  por día natural y cada pedido solo trae `hora_solicitud` (`HH:MM:SS`), sin fecha. Se
  muestra el rango consultado; el rótulo «últimas 24h» solo aparece cuando ese rango es hoy.

### Zona de administración

`lib/adminApi.js` es el único cliente autenticado: una instancia de axios con
`baseURL: /api/admin` que añade la cabecera `X-Admin-Token` en cada petición. La sesión
se guarda en `localStorage`, y **cualquier 401 se interpreta como sesión caducada** y
devuelve al login — los tokens viven en la memoria del proceso del backend, así que un
`systemctl restart sigep` los invalida todos.

Los componentes que leen datos del admin usan `useApi` pasándole ese cliente:
`useApi('/operadores', { cliente: admin })`.

**«Eliminar» nunca llama a `DELETE`.** Tanto en Operarios como en las combinaciones de
Jerarquía hace `PUT {activo: false}` tras confirmación, porque los endpoints de borrado
del backend son físicos y dejarían huérfano el histórico. Hay un test que lo verifica.

### Estados y errores

Tres reglas que se aplican en todo el frontend:

1. **Nunca se inventa un dato.** Sin valor se muestra `—`; sin filas, el estado vacío
   de la tarjeta. `lib/format.js` descarta `null`/`undefined` **antes** de convertir a
   número, porque `Number(null)` es `0` y pintaría un cero falso (una tablet sin
   heartbeat saldría como «0s», es decir como recién conectada).
2. **Un endpoint caído no arrastra a los demás.** `App.js` guarda los errores por
   endpoint, así que solo la tarjeta afectada avisa; las otras conservan sus datos. El
   indicador `EN VIVO` de la cabecera es el único que refleja el estado global.
3. **Ante un fallo se conserva el último dato bueno** en lugar de vaciar la tarjeta: en
   un centro de control una cifra de hace 15 segundos vale más que un hueco.

### Tests

```bash
cd frontend_sigep && CI=true npx react-scripts test --watchAll=false
npx eslint --ext .js src/
```

- `src/lib/format.test.js` — formato es-EC, duraciones, turnos, antigüedad.
- `src/App.test.js` — montaje del dashboard, tarjetas, tabla de detalle, y los dos
  casos de fallo (endpoint aislado y API entera caída).
- `src/components/admin/AdminApp.test.js` — las cinco pestañas, incluido que
  «Eliminar» haga `PUT {activo:false}` y nunca `DELETE`.

### Dev server

`.env.development` fija `HOST` a la IP del servidor. Es necesario: la clave `proxy`
activa el host check de CRA (protección anti DNS-rebinding) y con el `HOST` por defecto
(`0.0.0.0`) CRA no puede resolver la URL de LAN, deja `allowedHosts` vacío y aborta con
`options.allowedHosts[0] should be a non-empty string`.

### Convenciones del código existente

- Fetch con `axios.get`, `timeout: 8000`, envueltos en `useCallback`.
- Ante error de red: **conservar el dato viejo visible**, marcar bandera de error, no
  poner el estado a `null`.
- Polling con `setInterval` guardado en un `useRef`, limpiado en el `return` del efecto.
- Logs de consola con prefijo `[SIGEP]`.
- Números con `Number(v).toLocaleString('es-EC')` → `1.873`.
- Multi-selección serializada como claves repetidas (`maquina=A&maquina=B`), que FastAPI
  interpreta como `List[str]`.

---

## 3. API — endpoints

Base: `/api`. En producción se consume por el proxy de nginx (ruta relativa `/api`);
así se evita CORS. El `App.js` heredado apunta a `http://150.36.200.252:8000/api`.

### Públicos (sin autenticación)

| Método | Ruta | Parámetros | Respuesta |
|---|---|---|---|
| GET | `/dashboard/kpis` | `desde`,`hasta`,`maquina[]`,`operador[]`,`marca[]`,`presentacion[]`,`fragancia[]` | `{pallets_hoy, pacas_hoy, sacos_hoy, turnos_activos, eficiencia}` |
| GET | `/dashboard/logs` | — | `[{hora:"HH:MM:SS", mensaje, tipo:"pallet"}]` · 15 más recientes, desc |
| GET | `/dashboard/produccion_hora` | mismos filtros | `[{hora:"HH:00", pallets, detalle:[{maquina,operario,producto,pacas}]}]` |
| GET | `/dashboard/estado_operativo` | mismos filtros | `[{sesion_id, maquina, operador, producto, inicio_turno, tiempo_transcurrido, total_pacas, estado}]` |
| GET | `/dashboard/top_produccion` | mismos filtros | `[{name, value}]` desc |
| GET | `/dashboard/opciones_filtros` | `desde`,`hasta` | `{maquina[], operador[], marca[], presentacion[], fragancia[]}` |
| GET | `/dashboard/estadisticas` | `dim`, `rango`, `desde`, `hasta` | `{dim, rango, total_pacas, total_sesiones, items:[{etiqueta,pacas,sesiones,pct}]}` |
| GET | `/mantenimiento/checklist` | `limit` (def. 30) **o** `desde`,`hasta` | `[{id,maquina,operador,momento,codigo_turno,fecha_turno,fecha,hora,supervisor,comentarios,items:[{etiqueta,marcado}],total_items,items_ok,creado_en}]` |
| GET | `/tablets/estado` | — | `[{device_id,nombre,maquina,pendientes,ultimo_heartbeat,ultima_sincronizacion,en_linea,segundos_desde_heartbeat}]` |
| POST | `/tablets/sincronizar/{device_id}` | — | `{device_id, enviada, motivo}` |
| POST | `/tablets/sincronizar_todas` | — | `{total, enviadas}` |
| GET | `/insumos/dashboard` | `desde`,`hasta` | `{rango, kpis{total_pedidos,tiempo_resp_prom_seg,con_discrepancia,entregas_proactivas}, pedidos[], entregas[]}` |
| GET | `/operadores` | `tipo` (opcional) | `[{id, nombre}]` activos. `?tipo=SOLIDO\|LIQUIDO` filtra por línea; **sin el parámetro devuelve todos**, que es lo que hace la app Android actual. Un tipo desconocido se ignora en vez de vaciar el selector |
| GET | `/maquinas` | — | `[{id,nombre,tipo,marcas:[{nombre,presentaciones[]}]}]` — jerarquía completa |
| GET | `/reportes/excel` | `desde`,`hasta` | .xlsx producción (404 si el rango está vacío) |
| GET | `/reportes/formularios_excel` | `desde`,`hasta` | .xlsx checklists (404 si vacío) |
| GET | `/reportes/insumos_excel` | `desde`,`hasta` | .xlsx insumos (404 si vacío) |

`dim` ∈ `maquina` · `operario` · `marca_presentacion` · `marca_presentacion_fragancia`
`rango` ∈ `hoy` · `semana` (7d) · `mes` (30d) · `todo`. Si se envían `desde`/`hasta`,
mandan sobre `rango`.

Sin `desde`/`hasta`, todos los endpoints con rango equivalen **al día de hoy**.

### Administración (requieren cabecera `X-Admin-Token`)

Login: `POST /api/admin/auth {nombre, pin}` → `{token, username, nivel_acceso}`.
El token se guarda **en memoria del proceso**: al reiniciar `sigep.service` caducan
todas las sesiones admin y hay que volver a entrar. `POST /api/admin/logout` lo revoca.
Sin token o con token inválido: **401**.

| Método | Ruta | Notas |
|---|---|---|
| GET | `/admin/operadores` | `[{id,nombre,tipo,activo}]`, activos primero, luego alfabético. Acepta `?tipo=` |
| POST | `/admin/operadores` | `{nombre, tipo?}` — `tipo` ∈ `SOLIDO` (default) \| `LIQUIDO`. Si existe inactivo lo **reactiva**. 409 si ya está activo |
| PUT | `/admin/operadores/{id}` | `{nombre?, tipo?, activo?}` → desactivar = `{activo:false}` |
| DELETE | `/admin/operadores/{id}` | ⚠️ **borrado físico** (`db.delete`) |
| GET | `/admin/sesiones` | `desde`,`hasta` → `[{id,maquina,operador,marca,presentacion,fragancia,inicio,fin,estado,total_pacas,n_registros}]` |
| PUT | `/admin/sesiones/{id}` | `{maquina?,operador?,marca?,presentacion?,fragancia?}` |
| GET | `/admin/sesiones/{id}/pallets` | `[{id,cantidad_pacas,fecha_hora}]` |
| PUT | `/admin/pallets/{id}` | `{cantidad_pacas}` |
| GET | `/admin/checklists` | `desde`,`hasta`. Como el público **pero los items traen `id`** (necesario para editar) |
| PUT | `/admin/checklists/{id}` | `{supervisor?, comentarios?, items?:[{id,marcado}]}` |
| GET | `/admin/catalogos` | `{maquinas:[{id,nombre,tipo}], marcas:[str], presentaciones:[str]}` — solo activos |
| GET | `/admin/maquina_productos` | `[{maquina_id,maquina,tipo,activa,productos:[{id,marca,presentacion,activo}]}]` — incluye inactivos |
| POST | `/admin/maquina_productos` | `{maquina_id,marca,presentacion}`. Reactiva si existía inactiva |
| PUT | `/admin/maquina_productos/{id}` | `{marca?,presentacion?,activo?}` → desactivar |
| DELETE | `/admin/maquina_productos/{id}` | ⚠️ **borrado físico** |
| POST | `/admin/maquinas` | `{nombre, tipo?}` — `tipo` ∈ `SOLIDO`\|`LIQUIDO` (acepta `Sólido`/`Líquido`) |
| PUT | `/admin/maquinas/{id}` | `{nombre?, tipo?, activa?}` → alternar tipo y desactivar |
| POST | `/admin/marcas` | `{nombre}` |
| POST | `/admin/presentaciones` | `{nombre}` |
| GET | `/admin/sesiones_activas` | `[{sesion_id,maquina,operador,producto,inicio,tablet_online}]` |
| POST | `/admin/mensajes` | `{sesion_id, texto}` — individual, máx. 500 caracteres |
| POST | `/admin/mensajes/masivo` | `{texto, sesion_ids?}` — **lista vacía/ausente ⇒ TODAS las activas** |
| PUT/DELETE | `/admin/pedidos/{id}`, `/admin/entregas/{id}` | corrección de cantidades de insumos |

Los mensajes se persisten y se empujan por WebSocket a las tablets de esa máquina; el
heartbeat los recupera como respaldo. **No cambiar el transporte ni el formato.**

### Reglas de negocio del backend

- **Sacos vs pacas:** las presentaciones que contienen `15KG` o `25KG` (normalizando
  mayúsculas y espacios) se cuentan como **sacos**, no como pacas.
- **Turno** (`services/turnos.py`, zona Guayaquil): `DIA` de 07:00 a 18:59; `NOCHE` de
  19:00 a 06:59, y la madrugada (≤06:59) pertenece a la `fecha_turno` del día anterior.
  El servidor recalcula siempre el turno con su reloj; nunca confía en la tablet.
- **Tipo de línea** (`TIPOS_LINEA` en `admin.py`): `SOLIDO` y `LIQUIDO`, en mayúsculas y
  sin tilde. Lo comparten `maquinas.tipo` y `operadores.tipo`. `_norm_tipo()` tolera
  `Sólido`/`líquido` y rechaza cualquier otra cosa con 400. Un operario pertenece a **una
  sola** línea, y su nombre es único en toda la tabla (no puede haber un «JUAN PÉREZ» en
  sólido y otro en líquido).
- **Idempotencia:** el checklist se deduplica por `request_id` (UNIQUE). Contrato con la
  app offline: 2xx y 409 = éxito; otro 4xx = fatal sin reintento; 5xx = reintenta.
- `kpis.eficiencia` está **hardcodeado a `"94.8%"`** en el backend. No es un dato real y
  la UI de referencia no lo muestra: **no usarlo**.

---

## 4. Mapa: dato de la captura → endpoint

### Dashboard (`/`)

| Elemento | Origen |
|---|---|
| Reloj `12:21:31`, fecha `23 · Jul` | Cliente (`toLocaleTimeString('es-EC')`) |
| `● EN VIVO` | Cliente: éxito del último polling |
| `Turno actual DÍA · 07:00–19:00` | Cliente, replicando la regla de `services/turnos.py` |
| Botones `↓ Producción` / `↓ Formularios` / `↓ Insumos` | `/reportes/excel`, `/reportes/formularios_excel`, `/reportes/insumos_excel` con `desde`/`hasta` |
| KPI `PRODUCCIÓN DE HOY` → `1.873` / `0` | `kpis.pacas_hoy` / `kpis.sacos_hoy` |
| KPI `TURNOS ACTIVOS` → `5` | `kpis.turnos_activos` |
| KPI `LÍNEAS CON TURNO HOY` → `7`, `5 activas · 2 finalizada(s)` | **Derivado** de `estado_operativo`: `length`, y conteo por `estado` |
| `Producción por hora · pacas` | `produccion_hora[].hora` / `.pallets` (`.detalle` para el tooltip) |
| `Estado operativo · líneas` | `estado_operativo[]` completo |
| `Estadísticas de producción` | `estadisticas?dim=…&rango=…` |
| `Actividad en vivo` | `logs[]` |
| `Checklist de mantenimiento · 8 recientes` | `mantenimiento/checklist?limit=8` |
| `Tablets · sincronización · 0/21` | `tablets/estado[]`; `en_linea` para el punto, `segundos_desde_heartbeat` para `31m`/`21d` |
| `Top marcas · hoy` | `top_produccion[].name` / `.value` |
| `Solicitudes de insumos · últimas 24h` | `insumos/dashboard.pedidos[]` |
| `Detalle de checklist de mantenimiento` | `mantenimiento/checklist?desde=&hasta=` — mismo criterio y orden que el Excel de formularios |
| Footer `Actualizado 12:22:11` | Cliente: hora del último refresco |

### Administración (`/admin`)

| Pestaña | Endpoints |
|---|---|
| Cabecera / `Salir` | `POST /admin/auth`, `POST /admin/logout` |
| Operarios | `GET`/`POST` `/admin/operadores`, `PUT /admin/operadores/{id}` |
| Producción | `GET /admin/sesiones`, `PUT /admin/sesiones/{id}`; selects desde `/admin/catalogos` |
| Checklists | `GET /admin/checklists`, `PUT /admin/checklists/{id}` |
| Jerarquía | `GET /admin/maquina_productos`, `GET /admin/catalogos`, `POST`/`PUT`/`DELETE /admin/maquina_productos`, `POST`/`PUT /admin/maquinas`, `POST /admin/marcas`, `POST /admin/presentaciones` |
| Mensajes | `GET /admin/sesiones_activas`, `POST /admin/mensajes/masivo` |

### Sin endpoint propio (resuelto de otra forma)

- **Catálogo de fragancias** — no existe tabla maestra (sí hay `marcas` y
  `presentaciones`). El select de FRAGANCIA se puebla con
  `dashboard/opciones_filtros.fragancia` (valores históricos distintos).
- **"Últimas 24h" de insumos** — `insumos/dashboard` filtra por día natural, no por
  ventana móvil. Se pide `desde`=ayer, `hasta`=hoy y se filtra en cliente por
  `>= ahora - 24h`.
- **Líneas activas / finalizadas** — derivado en cliente de `estado_operativo`.

---

## 5. Sistema de diseño

Tema oscuro industrial, «centro de control».

```
fondo             #0A100E     casi negro con tinte verde
rejilla de fondo  rgba(255,255,255,0.025), celda ~56px, en TODAS las vistas
tarjetas          #101815
inputs            #16201C
bordes            rgba(255,255,255,0.07) 1px
texto             #E7EFEB
texto atenuado    #7C8C86
texto tenue       #5A6A64
acento ámbar      #F5A623     botones primarios, barras, checks, pestaña activa
verde OK          #22C55E     puntos activos, badges ENTRADA/ACTIVO, «X» del checklist
radio             12px
```

**La clave del aspecto son las etiquetas.** Toda etiqueta, metadato, badge, cabecera de
tabla, texto de ayuda o contador va en **monoespaciada, MAYÚSCULAS, ~11px,
`letter-spacing: 0.08em`, color atenuado**: `PACAS`, `MÁQUINA`, `OPERADOR`,
`SUPERADMIN`, `9 checklists`, `últimas 24h`. Títulos, nombres de máquina/operario y
números grandes en sans-serif bold.

- **KPI:** línea de acento de 2–3px en el borde superior (verde, verde, ámbar).
- **Badges:** pill mono mayúsculas 10–11px, fondo tintado al 12%.
  `ACTIVO`/`ENTRADA` verde · `SALIDA` ámbar · `FINALIZADO`/`OFFLINE` gris.
- **Botones:** primario ámbar con texto oscuro bold; secundario transparente con borde.
  `Eliminar` usa el estilo secundario, **sin rojo**.
- **Números:** `es-EC` siempre (`1.873`). Porcentajes con un decimal (`28.2%`).
  Duraciones `4h 48m`, `1h 02m`.
- **Layout:** máx. ~1400px centrado, dos columnas (izq. ~62% / der. ~38%), una sola
  bajo 1100px. **Sin barra lateral.**

---

## 6. Decisiones acordadas

1. **Estilos con Tailwind.** Se reescribe `tailwind.config.js` con la paleta de §5 y los
   componentes existentes se refactorizan sobre esos tokens. No se migra a CSS plano.
2. **«Eliminar» hace `PUT {activo:false}`, nunca `DELETE`.** Los endpoints
   `DELETE /admin/operadores/{id}` y `DELETE /admin/maquina_productos/{id}` borran
   físicamente y dejarían histórico huérfano, así que no se usan desde la UI. «Desactivar»
   y «Eliminar» tienen el mismo efecto a propósito; «Eliminar» pide confirmación.
3. **Login admin: pantalla centrada** con logo, `SIGEP · Administración`, campos Usuario y
   Contraseña, botón ámbar `Entrar` y el error del 401 en línea. No hay captura de
   referencia de esta pantalla.

## 7. Reglas de trabajo

1. **No** tocar la base de datos (ni esquema, ni datos, ni migraciones).
2. **No** modificar `api_produccion/`. Si la UI necesita un endpoint inexistente,
   detenerse y preguntar.
3. **No** ejecutar `npm run build` sin autorización explícita.
4. Commit al terminar cada etapa.
5. **No** reconfigurar nginx, systemd ni apache.
6. No instalar dependencias nuevas sin preguntar.
7. Cero datos falsos o hardcodeados: los ítems del checklist y los catálogos se leen
   siempre de la API.

### Deuda técnica conocida

- `api_produccion/` tiene ~25 archivos `.bak-*` y 3 directorios `.bak_*` versionados.
- 34 MB de APKs en `api_produccion/static/` dentro del repo git.
- Contraseñas de administrador en **texto plano** en la tabla `administradores`
  (`auth.py` compara con `==`; hay un `NOTA DE SEGURIDAD` en el código).
- Tokens de sesión admin en memoria: se pierden al reiniciar el servicio.
- El `src/` del frontend no estaba versionado al día: solo 5 de los 10 componentes
  existían en git antes del checkpoint.
