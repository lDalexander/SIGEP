# SIGEP — Centro de Control de Producción (Detcuador S.A.)

Sistema de control de producción de la planta de llenado. Dos consumidores de la misma
API: esta web (supervisión y administración) y una app Android en hasta 21 tablets
industriales con sincronización offline-first.

> **Regla de oro:** el backend (`api_produccion/`) y la base de datos NO se modifican sin
> autorización explícita del responsable. Cualquier cambio ahí puede romper las tablets en
> planta. Si la UI necesita algo que no existe, **hay que parar y preguntar**.

---

## Monorepo (2026-10-12)

El código de la app Android vivía en un repositorio aparte (`lDalexander/SIPEG`) y se
fusionó aquí con `git subtree`, **conservando todo su historial**. `SIPEG` quedó
archivado en GitHub, de solo lectura, para que no haya dos sitios donde commitear lo
mismo y se desincronicen.

```
SIGEP/
├── android/           app Kotlin de las tablets (operario, insumista, supervisor)
├── api_produccion/    backend FastAPI
├── frontend_sigep/    web React (dashboard + admin)
├── plantillas/        CSV de importación: catálogo de insumos y BOM
├── pruebas/           entorno Docker de pruebas
└── backups/           respaldo diario de BD y fotos
```

- **Android Studio abre `android/`**, no la raíz del repo.
- `android/CLAUDE.md` documenta la app y **no está versionado** (`android/.gitignore`),
  igual que cuando era repo aparte.
- El checkout del servidor (`~/workspace/apps/sigep`) ahora también trae `android/`.
  No afecta al despliegue: ni `deploy.sh`, ni `sigep.service`, ni nginx miran esa carpeta.

---

## 0. Estado actual

- **App Android:** `1.8.0` (`versionCode` 16) en el código desde el 2026-10-16, **aún sin
  instalar en planta**: reúne el chat de bodega, la bandeja del insumista y la entrega sin
  máquina (2026-10-13, tarde), la splash animada en Compose y el menú de perfiles con
  tarjetas. La estable de referencia en planta sigue siendo la `1.7.1` (`versionCode` 15,
  tag `v1.7.1`, verificada el 2026-10-13).
- **Último trabajo (2026-10-13):** chat de bodega por conversaciones (par supervisor ↔
  insumista), torre de control renovada, bandeja del insumista con 4 pestañas y entrega
  proactiva sin máquina (`maquina` NULL). Tags previos: `v1.20-pre-chat-conversaciones`,
  `v1.21-pre-entregas-sin-maquina`.
- **Módulo de insumos v1.7.0** (2026-10-12): el catálogo `insumos` es la única fuente de
  verdad, `insumos.bodega` enruta la alarma, BOM de cinco dimensiones con `NULL` =
  comodín, login único con token en `sesiones_usuario` (`X-User-Token`; sin token se
  mantiene el comportamiento anterior), foto obligatoria en la entrega proactiva y pedido
  de insumo dentro de la cola offline. Migración `api_produccion/alter_insumos_v17.sql`,
  ya aplicada.
- **Frontend web** desplegado en producción (nginx, puerto 3000). Última vuelta:
  2026-08-31, en la pestaña Jerarquía de `/admin`.

**Antes de tocar algo con historia, leer `docs/claude/HISTORIAL.md`.** Ahí está el porqué
de cada decisión, los incidentes reales (p. ej. la contingencia de paros del 2026-08-19)
y las **catorce** excepciones autorizadas a la regla de oro, con su verificación.

Tags y builds de respaldo: `docs/claude/RECUPERACION.md`. La comprobación previa a
cualquier cambio es la skill `.claude/skills/respaldo-antes-de-cambiar/SKILL.md`.

### Documentación detallada (se lee cuando la tarea lo pide)

| Archivo | Contenido |
|---|---|
| `docs/claude/HISTORIAL.md` | Crónica de cada cambio y los cambios de backend autorizados |
| `docs/claude/RECUPERACION.md` | Tabla de tags y builds de respaldo |
| `docs/claude/API.md` | Todos los endpoints (públicos y admin), franja horaria, paros, niveles de acceso, y el mapa dato de la captura → endpoint |
| `docs/claude/FRONTEND.md` | Segmentadores, zona de administración y qué cubre cada test |
| `android/CLAUDE.md` | La app Android (no versionado) |

### Pendiente

1. **Los cinco cambios de Android ya están hechos en el APK 1.5.1** — los cuatro
   `CAMBIO_ANDROID_*.md` están implementados, comprobado desensamblando
   `static/SIGEP-1.5.1.apk` el 2026-08-21: `getOperadoresPorTipo` (el `?tipo=` del selector
   de operario), `refrescarFraganciasDesdeServidor` + `cache_fragancias` +
   `fraganciasPorMaquinaMarca` (las fragancias salen del servidor y ya no de la lista fija
   del dex), y las tres del 2026-08-19 (`api/paro/estado`,
   `api/paro/{paro_id}/foto` con el part `foto` y encolado offline, `api/velocidad`).
   **Lo que queda es de distribución, no de código:** el OTA sigue deshabilitado a
   propósito (`version_code: 1`) y no consta que las 21 tablets tengan la 1.5.1 — el
   2026-08-21 solo una máquina había mandado velocidad y ninguna, foto. Antes de actualizar
   una tablet, mirar que esté a **0 pendientes**; y si hay que desinstalar por la firma, el
   turno abierto se cierra desde `/admin` (ver «Distribución del APK»).
2. **Validación de fragancia en `iniciar_turno`** — ahora sí es viable (la app la lee del
   servidor), pero solo cuando **todas** las tablets tengan la 1.5.1: activarla con una
   sola tablet vieja en planta rechazaría turnos legítimos. Necesita autorización aparte.
3. **`POST /api/paro/iniciar` tiene el desajuste simétrico y NO se ha tocado** (falta
   autorización). Si ya existe un paro abierto responde **409** y la app, que solo entra en
   modo paro en el callback de éxito, se queda creyendo que produce mientras la web la
   muestra parada. Es alcanzable justo con la contingencia nueva: si el operario reinicia
   la app y pulsa «registrar paro» sobre un paro reabierto desde `/admin`, verá un error.
   El arreglo sería el mismo criterio que en `finalizar`: devolver 200 con el paro que ya
   está abierto, sin sobrescribir su motivo.
4. **Paros sin cerrar** — el garbage collector de `tasks.py` cierra los turnos colgados a
   las 13 h pero **no cierra los paros abiertos de esa sesión**, así que quedan con
   `fin_paro` NULL para siempre (hay 1 de 78 así: el paro 105). La vista de paros los
   distingue con el estado «SIN CIERRE» en vez de contarlos como paros en curso, pero el
   arreglo de fondo está en el backend y **no se ha tocado** (haría falta autorización).
5. **Máquinas de línea líquida** — ya hay dos dadas de alta (`Máquina 3` y `Maquina 12`),
   sin producción registrada todavía. Total: 8 máquinas activas, una de ellas `PRUEBA`.
6. **Botón «Insumos» de la cabecera** — aparece en las capturas pero no hay ninguna captura
   ni especificación de esa vista. Hoy muestra un aviso de «vista sin especificación de
   referencia». Falta decidir qué debe contener, o quitar el botón.
7. **El total de estadísticas no cuadra con el KPI de producción**, y es **de siempre**, no
   de la segmentación: el rango de fechas de `estadisticas` filtra por `inicio_turno` de la
   sesión mientras `kpis` cuenta por `pallets.fecha_hora`. Sin ningún filtro, el 2026-08-05
   daban 1801 y 2767 pacas respectivamente. Con los segmentadores puestos la diferencia se
   ve más y se lee como si el filtro estuviera mal. Unificar el criterio **cambiaría** la
   respuesta del endpoint (las tablets no lo usan, pero rompería la comparación byte a
   byte), así que necesita autorización aparte. Sin hacer.

---

## 1. Entorno

| | |
|---|---|
| Servidor | `150.36.200.252` (directa) · `100.81.223.5` (por VPN, host `detcuador`) · Ubuntu 24.04 · TZ `America/Guayaquil`. **Las dos IPs son válidas y apuntan a la misma máquina**; cuál responde depende de si se está dentro de la VPN. |
| Proyecto | `/home/john/workspace/apps/sigep` (antes `/home/john/Proyectos/App_Llenadora`) |
| Backend | `api_produccion/` — FastAPI + SQLAlchemy, gunicorn/uvicorn en `127.0.0.1:8000` |
| Servicio | `sigep.service` (systemd, `Restart=always`, `-w 1`) |
| Frontend | `frontend_sigep/` — Create React App (React 19, JavaScript, sin TypeScript) |
| Web | nginx en el puerto **3000**, root `frontend_sigep/build`, `/api/` → `127.0.0.1:8000/api/` |
| BD | MySQL, esquema `produccion_detg` |
| Repo | GitHub `lDalexander/SIGEP`, rama `main`. **Monorepo** desde 2026-10-12: incluye `android/`, antes repo aparte `lDalexander/SIPEG` (archivado). |
| Referencia visual | `referencia_ui/*.jpeg` — 9 capturas de la versión a reproducir |

Convive un apache2 en el puerto 80. **No reconfigurar nginx, systemd ni apache.**

### Distribución del APK (OTA)

El APK vive en `api_produccion/static/sigep_latest.apk` y hay un sistema OTA ya montado:
`GET /api/config/version` devuelve `{version_code, version_name, url_descarga,
obligatorio}`, y la app **tiene el permiso `REQUEST_INSTALL_PACKAGES`**, así que puede
instalar lo que descargue.

**Está deshabilitado a propósito** (`main.py`): anuncia `version_code: 1` y
`obligatorio: False` para que ninguna tablet crea que hay actualización «mientras se
prepara el rollout remoto». Para activarlo hay que poner ahí el `version_code` **real y
mayor** que el instalado en las tablets.

Tres cosas que se comprobaron el 2026-08-20 y que no son evidentes:

- **La URL solo sirve por el 8000.** `http://…:3000/static/sigep_latest.apk` devuelve
  **200 con el `index.html` de la SPA** (700 bytes, `text/html`), porque nginx hace
  fallback a la SPA; el 8000 devuelve los 7,4 MB con
  `application/vnd.android.package-archive`. Una tablet apuntada al 3000 se descargaría un
  HTML y la instalación fallaría con un error que no dice nada.
- **`/static/` no pide autenticación**, así que el APK es descargable por cualquiera que
  tenga la URL. Igual que las fotos de paros y de entregas.
- **La firma manda, y el OTA no la esquiva.** El APK en producción está firmado con el
  keystore de release (`DETCUADOR · Sistemas · SIGEP · Guayaquil`), **no** con la clave
  debug. Un APK firmado con otra clave —por ejemplo una build debug de Android Studio— no
  puede actualizar sobre el instalado: hay que **desinstalar**, y eso borra los
  `SharedPreferences` (`sesion_activa`, `turno_maquina`, `turno_operador`,
  `turno_pendiente_crear`) y la cola local. La app arranca sin turno mientras **el turno
  sigue abierto en el servidor**, así que el operario recibe «Esta máquina ya tiene un turno
  activo» y hay que cerrarlo desde `/admin` → Producción → «CERRAR TURNO».

Antes de actualizar tablets, mirar que estén a **0 pendientes**
(`GET /api/tablets/estado`): lo que no se ha enviado se pierde si hay que desinstalar.

### Comandos

```bash
# Desarrollo
cd frontend_sigep && npm start          # dev server en el 3001, /api -> 8000
API_TARGET=http://127.0.0.1:8001 npm start   # ...contra una instancia paralela

# Desplegar a producción — usar SIEMPRE el script, nunca `npm run build` a mano
./deploy.sh --revisar                   # comprueba sin tocar nada
./deploy.sh                             # despliega

# Backend
sudo systemctl restart sigep && systemctl status sigep
curl -s http://127.0.0.1:8000/api/dashboard/kpis
```

### Cómo tocar el backend sin arriesgar la planta

Procedimiento seguido en los cambios de 2026-08-03; conviene repetirlo:

**1. Probar el código nuevo en paralelo, sin tocar el servicio.** `sigep.service` corre
en el 8000; se levanta una instancia con el código nuevo en otro puerto y se comparan
las respuestas byte a byte contra el servicio vivo, que aún tiene el código anterior:

```bash
cd api_produccion
(set -a; . ./.env; set +a; ./venv/bin/uvicorn main:app --host 127.0.0.1 --port 8001)
# en otra terminal, por cada endpoint tocado y SIN los parámetros nuevos:
diff <(curl -s "http://127.0.0.1:8000/api/dashboard/kpis") \
     <(curl -s "http://127.0.0.1:8001/api/dashboard/kpis")
```

Si el `diff` está vacío en todos los endpoints, **las tablets no necesitan
actualización**: es la prueba de que el parámetro nuevo es de verdad opcional. Es como
se validaron `?agrupar=`, `?tipo=` y `hora_desde`/`hora_hasta`.

**2. Recargar sin cerrar el puerto.** `gunicorn` corre como `john` (`User=john` en el
unit), así que no hace falta `sudo`: un `SIGHUP` al **master** arranca workers nuevos con
el código nuevo y retira los viejos sin cerrar el socket del 8000.

```bash
kill -HUP $(systemctl show -p MainPID --value sigep)
```

Un `systemctl restart` también vale pero cierra el puerto 1–2 s y pide `sudo`. Con
cualquiera de los dos **caducan las sesiones admin** (los tokens viven en memoria del
proceso) y se cortan los WebSockets abiertos; el heartbeat de las tablets los recupera.
Con turnos activos, preguntar antes de recargar.

**3. Orden de despliegue: backend primero, frontend después.** Al revés habría un rato
con una web que anuncia un filtro contra una API que aún ignora el parámetro — no falla,
pero muestra datos sin filtrar como si estuvieran filtrados.

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
- **No hay `react-router`.** La navegación entre Dashboard, Paros y Admin se hace por
  estado en `App.js` (`vista`) + History API. La traducción ruta → vista está en
  `vistaDeRuta()`, y la usan tanto el arranque como el botón «atrás». Rutas: `/`,
  `/paros`, `/insumos`, `/admin`.

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
    │   ├── format.js            # es-EC, duraciones, turno, antigüedad, tablet, velocidad y su rango
    │   ├── filtros.js           # dimensiones segmentables, params y serialización repetida
    │   ├── fotos.js             # URL de /static resuelta al 8000 (el 3000 devuelve la SPA)
    │   └── useApi.js            # GET con polling; conserva el último dato bueno
    ├── components/
    │   ├── Header.js            # cabecera: reloj, EN VIVO, Dashboard, Paros, Insumos, Admin
    │   │                        # (la marca también vuelve al dashboard)
    │   ├── BarraTitulo.js       # sobre-título, H1, turno actual
    │   ├── FiltroFecha.js       # fechas + franja horaria + Cargar + 3 descargas
    │   ├── Segmentadores.js     # 5 desplegables multi-selección bajo el título
    │   ├── KPICards.js          # las 3 tarjetas KPI
    │   ├── ProductionChart.js   # producción por hora (área)
    │   ├── OperationsTable.js   # estado operativo · líneas
    │   ├── EstadisticasProduccion.js  # ranking por agrupación (usa el rango global)
    │   ├── TerminalLog.js       # actividad en vivo
    │   ├── ChecklistMantenimiento.js  # tarjetas con anillo de progreso
    │   ├── TopProductionChart.js # top marcas
    │   ├── SolicitudesInsumos.js # pedidos de insumo del rango
    │   ├── ComentariosTurno.js  # comentarios libres de los operarios (8 recientes)
    │   ├── DetalleChecklist.js  # tabla a ancho completo, una columna por ítem
    │   ├── Footer.js
    │   ├── paros/               # vista /paros
    │   │   ├── ParosView.js     # un solo fetch alimenta la vista entera
    │   │   ├── ParosKPIs.js     # paradas ahora · paros del rango · tiempo · promedio
    │   │   ├── EstadoMaquinas.js # semáforo PARO / PRODUCIENDO / SIN TURNO
    │   │   ├── TablaParos.js    # una fila por paro, desplegable con el detalle
    │   │   ├── ParosPorCategoria.js # ranking por tiempo parado
    │   │   └── Cronometro.js    # duración que sigue corriendo en los paros abiertos
    │   └── admin/               # vista /admin
    │       ├── AdminApp.js      # login, cabecera propia y las 5 pestañas
    │       ├── AdminLogin.js
    │       ├── Ayuda.js, FiltroRango.js
    │       ├── TabOperarios / TabProduccion / TabChecklists / TabJerarquia / TabMensajes
    │       ├── TabParos.js      # corregir, cerrar, reabrir y borrar paros
    │       ├── TabInsumos.js    # cantidades de pedidos y entregas
    │       ├── TabFeedback.js   # reportes de la app (se atienden) y comentarios
    │       ├── TabTablets.js    # registro de dispositivos
    │       ├── TabUsuarios.js   # administradores y niveles — solo la ve un SUPERADMIN
    │       └── TabCorreo.js     # servidor SMTP, listas de correo y reporte semanal
    └── components/ui/           # componentes base del sistema de diseño
        ├── Label, Badge, Button, Card, StatCard (+ Cifra)
        ├── ProgressBar, Ring, Tabs, Dot, Logo
        ├── FotoEvidencia         # miniatura + visor de la foto del paro
        └── Estado (+ Esqueleto)  # carga / error / vacío
```

Todo componente se importa desde `./ui` (barrel en `ui/index.js`), y todo número pasa
por `lib/format.js`. Colores nuevos van a `tailwind.config.js`, nunca sueltos en el JSX.

### Componente → endpoint

| Componente | Endpoint |
|---|---|
| `Segmentadores` | `/dashboard/opciones_filtros` con el rango, la franja y los demás filtros — menús encadenados (fetch propio, refresco cada 60 s) |
| `KPICards` | `/dashboard/kpis` + derivación de `/dashboard/estado_operativo` |
| `ProductionChart` | `/dashboard/produccion_hora` (+`agrupar=dia` cuando el rango pasa de un día) |
| `OperationsTable` | `/dashboard/estado_operativo` |
| `EstadisticasProduccion` | `/dashboard/estadisticas` (fetch propio, con el rango, la franja y los segmentadores globales; `dim` deducido de lo segmentado) |
| `TerminalLog` | `/dashboard/logs` |
| `ChecklistMantenimiento` | `/mantenimiento/checklist?limit=8` (fetch propio) |
| `TopProductionChart` | `/dashboard/top_produccion` |
| `SolicitudesInsumos` | `/insumos/dashboard?desde&hasta` (fetch propio) |
| `ComentariosTurno` | `/dashboard/comentarios_turno?limit=8` (fetch propio) |
| `DetalleChecklist` | `/mantenimiento/checklist?desde&hasta` (fetch propio) |
| `ParosView` (`/paros`) | `/dashboard/paros?desde&hasta&hora_desde&hora_hasta` (fetch propio; alimenta las cuatro tarjetas de la vista) |
| `FiltroFecha` | `/reportes/excel`, `/reportes/formularios_excel`, `/reportes/insumos_excel` |

`App.js` refresca los cinco endpoints del dashboard cada 15 s con `Promise.allSettled`:
si uno falla, los demás se actualizan igual y el que falló conserva su último dato. Las
tarjetas marcadas como «fetch propio» usan el hook `lib/useApi.js`, con el mismo criterio.

### Segmentadores, zona de administración y tests

El detalle está en `docs/claude/FRONTEND.md`. Lo imprescindible:

- Multi-selección serializada con **claves repetidas** (`maquina=A&maquina=B`) mediante
  `paramsSerializer` de `lib/filtros.js`; axios por defecto manda `maquina[]=A` y el
  backend devolvería los datos sin filtrar sin dar error.
- **«Eliminar» en `/admin` nunca llama a `DELETE`**: hace `PUT {activo:false}` (§6).
- La sesión admin caduca a los 15 min de inactividad (`lib/useInactividad.js`).

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

Qué cubre cada test: `docs/claude/FRONTEND.md`.

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
  interpreta como `List[str]`. Se consigue pasando `paramsSerializer` con
  `serializarParams` de `lib/filtros.js`; el serializador por defecto de axios pondría
  `maquina[]=A` y el filtro se perdería en silencio.

---

## 3. API

Base `/api`. Referencia completa de endpoints, franja horaria, paros, autenticación admin,
niveles de acceso y el mapa dato de la captura → endpoint: **`docs/claude/API.md`**.

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

En `docs/claude/API.md`, al final.

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

0. **Antes de tocar cualquier archivo**, comprobar que el estado anterior está en git y en
   GitHub, y respaldarlo en ese momento si no lo está. Procedimiento y criterio de tags en
   `.claude/skills/respaldo-antes-de-cambiar/SKILL.md`.
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
- **El estado de paro de la tablet y el de la BD pueden separarse, y la tablet no tiene
  forma de resincronizarse.** Su flag vive en memoria de `MainActivity` y ninguna respuesta
  del servidor se lo corrige: no hay endpoint que diga «esta sesión tiene un paro abierto»,
  ni el heartbeat lo trae. El 2026-08-19 se tapó el lado que bloqueaba producción
  (`/paro/finalizar` ya no da 404), pero el desajuste sigue existiendo y la salida en
  planta sigue siendo reiniciar la app. El arreglo de fondo es de la app.
- El garbage collector de turnos (`tasks.py`) cierra las sesiones colgadas a las 13 h
  pero **no cierra los paros abiertos de esas sesiones**, que quedan con `fin_paro` NULL
  a perpetuidad. La vista de paros lo compensa en lectura («SIN CIERRE»), pero el
  registro sigue incompleto.
- La instancia paralela de pruebas (`uvicorn` en el 8001) **también arranca el garbage
  collector y el programador del reporte semanal**, y escribe en la misma BD. Lo del
  recolector es inocuo —hace lo que el servicio vivo ya hace cada hora—, pero lo del
  reporte no: levantada un **viernes alrededor de las 12:00**, será ella quien mande el
  correo oficial y marque la semana como enviada.
- El `src/` del frontend no estaba versionado al día: solo 5 de los 10 componentes
  existían en git antes del checkpoint.
