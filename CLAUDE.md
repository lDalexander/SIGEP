# SIGEP — Centro de Control de Producción (Detcuador S.A.)

Sistema de control de producción de la planta de llenado. Dos consumidores de la misma
API: esta web (supervisión y administración) y una app Android en hasta 21 tablets
industriales con sincronización offline-first.

> **Regla de oro:** el backend (`api_produccion/`) y la base de datos NO se modifican sin
> autorización explícita del responsable. Cualquier cambio ahí puede romper las tablets en
> planta. Si la UI necesita algo que no existe, **hay que parar y preguntar**.

---

## 0. Estado actual

**Última sesión: 2026-08-07.** El frontend está reconstruido y **desplegado en producción**
(nginx, puerto 3000). Dashboard y las cinco pestañas de administración equivalentes a las
capturas de `referencia_ui/`. 136 tests en verde.

El **aviso por correo de los reportes de la app** (2026-08-07) está **implementado y
probado, pero SIN DESPLEGAR**: el 8000 sigue con el código anterior. Detalle abajo.

El **estado de la tablet en la pestaña Mensajes** (2026-08-07) **ya está en producción**
(worker `2225444`, frontend `main.898b7185.js`). Las cinco máquinas en turno salían
`OFFLINE` aunque estuvieran produciendo: el chip miraba el heartbeat con el umbral de 60 s
y **los latidos llegan cada 20-25 minutos** (medido: en 6 minutos de muestreo sobre las
cinco, solo dos renovaron; una tablet cumple el umbral **1 minuto de cada 20**). Ahora sale
del WebSocket abierto. Detalle abajo. Respaldo del build anterior:
`~/respaldos_build_sigep/build_2026-08-07_104035`.

La **caducidad de la sesión admin por inactividad** (2026-08-07) **ya está en producción**
(backend con `kill -HUP`, worker nuevo `2222339`, y frontend `main.f9bcf32a.js`). Se
comprobó antes en el dev server contra el 8001 con `ADMIN_INACTIVIDAD_MIN=1`, y después
del despliegue que `/api/maquinas` seguía idéntica byte a byte al snapshot previo, que los
KPIs (1771 pacas · 0 sacos) y las 5 líneas con 5 turnos activos no se movieron, y que el
log del servicio no traía ningún error. Respaldo del build anterior:
`~/respaldos_build_sigep/build_2026-08-07_101252`. Detalle en «Cambios de backend ya
autorizados» más abajo.

**Cerrar turno, eliminar sesión, historial editable de pacas, gestión de usuarios y
niveles de acceso** (2026-08-06) **ya están en producción** (worker `2154040`, frontend
`main.97f48aa0.js`). Se comprobó después que `/api/maquinas` seguía idéntica byte a byte
al snapshot previo y que los KPIs (2720 pacas · 100 sacos) y las 11 líneas con 5 turnos
activos no se movieron. Respaldo del build anterior:
`~/respaldos_build_sigep/build_2026-08-06_164105`.

La **jerarquía de fragancias por máquina + marca** (2026-08-06) **ya está en producción**
(backend con `kill -HUP`, worker nuevo 2140077, y frontend `main.edc4db7b.js`). Se comprobó
después que `/api/maquinas` seguía idéntica byte a byte al snapshot previo, que los KPIs
(2070 pacas · 100 sacos) y las 10 líneas con 5 turnos activos no se movieron, y que una
tablet abrió WebSocket y registró un paro **contra el código nuevo** un minuto después del
HUP. Detalle en «Cambios de backend ya autorizados» más abajo.

**Ojo con la app Android:** se verificó desensamblando `static/sigep_latest.apk` (v1.3.1)
que la app **no lee las fragancias del servidor**. Sus rutas HTTP son `api/operadores`,
`api/maquinas`, `api/iniciar_turno`, `api/registrar_pallet`,
`api/mantenimiento/checklist`… y **ninguna de fragancias**; `"Floral"` y `"Limón"` están
como literales en el dex, junto a los ítems del checklist; y su data class
`MarcaResponse(nombre=…)` **no tiene campo `fragancias`**, así que tampoco las leería
dentro de `/api/maquinas`. Configurar fragancias en la web **no cambia todavía lo que ve
el operario en la tablet**.

Los **segmentadores multi-selección** del dashboard con **menús encadenados** (2026-08-05)
**ya están en producción**. Semántica en §2 y §4. Cómo se desplegó, por si sirve de patrón:

1. Backend primero, con `kill -HUP` al master (el puerto no se cerró, el master conservó el
   PID 1924092 y el worker viejo se retiró). Se aprovechó una ventana con **0 turnos
   activos**, así que ninguna sesión se vio afectada.
2. Se comprobó después que los KPIs y las 8 sesiones del día seguían idénticos byte a byte
   al snapshot previo, y que el log del servicio no traía ningún error.
3. `./deploy.sh` para el frontend (`main.83ba9a91.js`), nunca al revés.
4. Verificado por el proxy de nginx en el 3000: `/`, `/paros`, `/admin` a 200, y los dos
   endpoints nuevos respondiendo segmentados.

Las tablets **quedaron a 0 en línea justo tras el HUP** (corta los WebSockets, es lo
esperado) y las recupera el heartbeat; conviene mirar `/api/tablets/estado` un rato
después de cualquier recarga para confirmar que han vuelto.

La vista `/paros` y la tarjeta de comentarios de turno (2026-08-05) **ya están en
producción**: backend recargado con `kill -HUP` (el puerto no se cerró, el master
conservó el PID) y frontend desplegado con `./deploy.sh`. Se comprobó después que los
4 turnos activos seguían abiertos, las pacas del día intactas y las tablets reportando
heartbeat, sin errores en el log del servicio.

### Punto de recuperación

| | |
|---|---|
| Tag estable | `v1.0-frontend-reconstruido` (en GitHub) |
| Tag previo al filtro de horas | `v1.1-pre-filtro-horas` (en GitHub) |
| Tag previo a la vista de paros | `v1.2-pre-paros` (en GitHub) |
| Tag previo a los segmentadores | `v1.3-pre-segmentadores` (en GitHub) |
| Tag previo a los filtros de estadísticas | `v1.4-pre-filtros-estadisticas` (en GitHub) |
| Tag previo a los menús encadenados | `v1.5-pre-opciones-encadenadas` (en GitHub) |
| Tag previo a la jerarquía de fragancias | `v1.6-pre-fragancias` (en GitHub) |
| Tag previo a la gestión de usuarios | `v1.7-pre-gestion-usuarios` (en GitHub) |
| Tag previo al historial de pacas | `v1.8-pre-historial-pacas` (en GitHub) |
| Tag previo a la caducidad de sesión | `v1.9-pre-caducidad-sesion` (en GitHub) |
| Tag previo al estado real de tablet | `v1.10-pre-estado-tablet` (en GitHub) |
| Tag previo al correo de reportes | `v1.11-pre-correo-reportes` (en GitHub) |
| Build previo al estado de tablet | `~/respaldos_build_sigep/build_2026-08-07_104035` |
| Build previo a la caducidad de sesión | `~/respaldos_build_sigep/build_2026-08-07_101252` |
| Build previo a estos dos cambios | `~/respaldos_build_sigep/build_2026-08-06_164105` |
| Build previo a las fragancias | `~/respaldos_build_sigep/build_2026-08-06_130039` |
| Build de esa versión | `~/respaldos_build_sigep/build_pre-paros_2026-08-05_*` |
| Build previo a los segmentadores | `~/respaldos_build_sigep/build_2026-08-05_165753` |
| Build estable v1.0 | `~/RESPALDO_build_estable_v1.0` |
| Builds rotados | `~/respaldos_build_sigep/` (últimos 10) |
| BD previa al último `ALTER` | `backups/produccion_detg_pre_tipo_operario_*.sql.gz` |

```bash
git checkout v1.0-frontend-reconstruido     # volver al frontend estable
git checkout v1.2-pre-paros                 # volver a justo antes de la vista de paros
git checkout v1.3-pre-segmentadores         # volver a justo antes de los segmentadores
```

La comprobación de respaldo previa a cualquier cambio está automatizada como skill del
proyecto: `.claude/skills/respaldo-antes-de-cambiar/SKILL.md` (qué comprobar, cuándo hace
falta tag, y la prohibición de «limpiar» el árbol con `checkout`/`reset`/`clean`).

### Cambios de backend ya autorizados y aplicados

Tras la reconstrucción se autorizaron **diez** excepciones a la regla de oro:

- **Aviso por correo de los reportes de la app** (2026-08-07, **implementado, sin
  desplegar**). El botón de «reportar problema» de las tablets escribía en
  `reportes_app` y **no lo leía nadie**: no hay vista en la web ni notificación, así que
  un fallo reportado desde planta podía quedarse ahí semanas.

  - `POST /api/reportes_app` (`routers/operaciones.py`) manda ahora un correo con
    `services/email_service.notificar_reporte_app`. **Sin `ALTER`, sin tablas y sin rutas
    nuevas**; la respuesta a la tablet no cambia.
  - **La infraestructura SMTP ya existía** y funciona desde siempre para los pedidos de
    insumos (`smtp-mail.outlook.com:587`, `no-reply@detcuador.com`). Solo se añadió la
    función del reporte y dos variables al `.env`: `REPORTES_EMAIL_TO` (hoy
    `agarcia@detcuador.com`) y `REPORTES_EMAIL_CC`. **Los reportes NO van a la lista de
    pedidos**: son incidencias técnicas, no operación de bodega. Si no se configuran, se
    cae a los destinatarios de pedidos para no perder el aviso en silencio.
  - **Solo se envía cuando la fila es nueva.** El endpoint es idempotente por
    `request_id` y una tablet sin red reintenta el mismo reporte hasta que entra; enviar
    también en el duplicado llenaría el buzón de copias del mismo incidente.
    `_guardar_feedback` marca esos casos con `duplicado: True`.
  - Va en `BackgroundTasks` y `_enviar` se traga sus propios errores: un SMTP caído o
    lento **no retrasa ni rompe la respuesta a la tablet**.
  - El texto del operario va **escapado** en el HTML del correo (`html.escape`): llega tal
    cual se escribió y un `<` suelto rompería la maquetación.
  - Verificado con `diff` 8000 vs 8001 en **23 endpoints**, idénticos byte a byte, más el
    contrato de `/api/reportes_app` (texto vacío sigue dando 400, cuerpo incompleto 422).
    Correo real enviado y recibido. **Las tablets no necesitan actualización.**


- **Estado real de la tablet en `/admin/sesiones_activas`** (2026-08-07, **en producción**).
  Un solo endpoint, admin-only, y **sin `ALTER`, sin tablas y sin rutas nuevas**.

  - **El problema no era el chip, era lo que medía.** `tablet_online` exigía un heartbeat
    de menos de 60 s (`UMBRAL_OFFLINE_SEGUNDOS`), y las tablets laten cada 20-25 min:
    medido el 2026-08-07 sobre las cinco máquinas en turno, los últimos latidos eran de
    hace 7, 8, 20, 36 min y 2 h 45, y en 6 minutos de muestreo cada 30 s **solo dos
    renovaron**. Con ese umbral una tablet sale `ONLINE` **1 minuto de cada 20**, así que
    el chip decía `OFFLINE` casi siempre y hacía dudar de la lista entera. Los nombres de
    máquina casaban bien; no era un problema de emparejamiento.
  - **`tablet_online` pasa a ser «tiene el WebSocket abierto»** (`tablet_manager.connections`,
    en `routers/tablets.py`), que es la respuesta exacta a lo que el chip quiere decir:
    si hay WS, el mensaje sale ya.
  - **`segundos_desde_contacto` es una clave nueva** y responde a otra pregunta distinta:
    ¿queda alguien ahí? Una tablet puede producir con el WS caído (79 cierres de WS en la
    jornada del 2026-08-07), y esa es la que importa antes de cerrar un turno a mano.
  - **Ojo si el servicio deja de ser `-w 1`**: el registro de WebSockets vive en la memoria
    de cada worker, así que una conexión atendida por otro proceso se leería como «en cola».
    El mensaje se entregaría igual —los no leídos viajan en el heartbeat—, pero el rótulo
    se quedaría corto.
  - Verificado con `diff` 8000 vs 8001 en **23 endpoints**, idénticos byte a byte, y la
    función nueva probada contra la BD real (simulando el WS de la tablet de Máquina 7,
    solo esa fila pasa a conectada). **Las tablets no necesitan actualización.**


- **Caducidad de la sesión admin por inactividad** (2026-08-07, **en producción**). Un
  token admin no caducaba nunca: solo lo mataba «Salir» o un reinicio del
  servicio, así que un navegador olvidado en `/admin` podía cerrar turnos o borrar sesiones
  días después. **Sin `ALTER`, sin tablas y sin rutas nuevas**: solo cambia el ciclo de vida
  de un `dict` que ya vivía en memoria.

  - **Se mide inactividad, no antigüedad.** Cada petición autenticada renueva el reloj en
    `require_admin`, así que una edición larga no se corta a medias. **No hay tope
    absoluto**, por decisión del responsable.
  - **15 minutos**, en `INACTIVIDAD_MAX`. Se puede ajustar sin tocar código con la variable
    de entorno `ADMIN_INACTIVIDAD_MIN`; un valor ilegible o ≤ 0 cae al defecto de 15, nunca
    a «sin caducidad» — una errata en el `.env` no debe reabrir el agujero.
  - **El 401 dice por qué**: `detail` es «Sesión cerrada por inactividad…» cuando el token
    existía y llevaba parado demasiado, y el «Sesión admin requerida o expirada» de siempre
    cuando no existe (reinicio del servicio, token inventado). La web enseña ese texto en
    el login, así que el usuario distingue los dos casos.
  - **Única respuesta que cambia:** `POST /api/admin/auth` añade la clave
    `inactividad_segundos`. Es admin-only y solo la consume esta web; **la app Android usa
    `/api/admin/login`, que no se tocó**.
  - **Ojo con el refresco de la pestaña Mensajes**: hace polling cada 15 s, y ese polling
    cuenta como uso, así que por el camino del backend el token no caducaría mientras esa
    pestaña esté abierta. Lo cubre el temporizador del navegador (§2), que sí sabe si hay
    alguien delante.
  - Verificado con `diff` 8000 vs 8001 en **23 endpoints**, todos idénticos byte a byte
    (`/api/maquinas`, `/api/operadores` con y sin `tipo`, `/api/fragancias`,
    `/api/usuarios`, `/api/admin/supervisores`, los del dashboard con rango, franja,
    segmentadores y `agrupar=dia`, `opciones_filtros` encadenado, `comentarios_turno`,
    `mantenimiento/checklist`, `tablets/estado`, `insumos/dashboard`). **Las tablets no
    necesitan actualización.**


- **Cerrar turno, eliminar sesión, historial de pacas, usuarios y niveles de acceso**
  (2026-08-06, **en producción**). **Sin `ALTER` y sin tablas nuevas**: todo usa
  columnas que ya existían.

  - `POST /admin/sesiones/{id}/cerrar` — la salida para los turnos que quedan
    abiertos y bloquean a la máquina («Esta máquina ya tiene un turno activo»).
    Hace lo mismo que el `finalizar_turno` de la tablet —cierra el paro abierto y
    los pedidos de insumo vivos, avisa al insumista por WS, fija `fin_turno` y
    `duracion_minutos`— y escribe `observaciones = "CERRADO MANUALMENTE POR: X"`,
    el mismo campo donde el GC pone `CERRADO AUTOMATICAMENTE POR EL SISTEMA`.
  - `DELETE /admin/sesiones/{id}` — **solo SUPERADMIN**, borra en cascada las seis
    tablas que cuelgan de `session_id`. En cascada a propósito: los pallets se
    cuentan por `pallets.fecha_hora` sin pasar por la sesión, así que dejarlos
    seguiría sumándolos a los KPIs de un turno que ya no existe.
  - `GET/POST/PUT /admin/usuarios` y `GET /admin/niveles` — **solo SUPERADMIN**.
    Nunca devuelven la contraseña. «Eliminar» es `PUT {activo:false}`.
  - `PUT /admin/pallets/{id}` acepta también `fecha_hora`, y `DELETE /admin/pallets/{id}`
    (solo SUPERADMIN) es nuevo: son el desplegable del historial de pacas de cada
    sesión. Cambiar la hora **mueve la producción de hora y de día** en KPIs, gráfico
    y Excel, que cuentan por `pallets.fecha_hora`; es lo que permite recolocar los
    pallets que una tablet sincroniza tarde tras quedarse sin red.
  - **Los niveles ahora se aplican de verdad.** `nivel_acceso` existía pero no
    controlaba nada: bastaba un token válido para todo. Se añadió `require_nivel`
    en `routers/admin.py` y se exige en los **22 endpoints de escritura** que ya
    existían. Detalle en §3.
  - **Contraseñas hasheadas con PBKDF2-SHA256** (`services/seguridad.py`, sin
    dependencias nuevas). Migración progresiva: `verificar()` acepta el texto plano
    heredado y el login lo reescribe hasheado en ese momento. Toca **los dos**
    logins —`/api/admin/auth` (web) y `/api/admin/login` (app Android)— y para los
    clientes es invisible. **Ojo con el orden de despliegue por esto**: un login
    contra el código nuevo hashea la contraseña en la BD, y el código viejo, que
    compara con `==`, dejaría de aceptarla. El backend va primero, siempre.
  - Verificado con `diff` 8000 vs 8001 en **18 endpoints**, todos idénticos byte a
    byte, incluidos `/api/admin/supervisores` y `/api/usuarios`, que usa la app.
    **Las tablets no necesitan actualización.**

- **Jerarquía de fragancias por máquina + marca** (2026-08-06, **en producción**).
  La fragancia era universal: la app
  ofrecía la misma lista fija (Floral / Limón) en cualquier máquina y marca. Con la línea
  líquida en producción cada máquina y marca hace fragancias distintas, así que pasa a
  formar parte de la jerarquía.

  - **BD:** una tabla nueva, `maquina_marca_fragancias` (`maquina_id`, `marca`,
    `fragancia`, `activo`, UNIQUE de las tres primeras), en
    `api_produccion/alter_fragancias_jerarquia.sql`. La granularidad es
    **(máquina, marca)**, sin presentación: ULTREX 1 KG y ULTREX 3 KG de la misma máquina
    llevan las mismas fragancias, así que colgarlo de `maquina_productos` habría
    multiplicado las filas sin ganar precisión. **`maquina_productos` no se tocó.**
  - **`fragancias` ya existía en MySQL** —creada a mano como `marcas` y
    `presentaciones`, con Limón y Floral dentro— y **nadie la leía**. Se empieza a usar
    como catálogo maestro; su esquema y sus filas están intactos.
  - **Cuidado:** `main.py` llama a `Base.metadata.create_all()` al arrancar, así que la
    tabla nueva **se crea sola** en cuanto un proceso importa el modelo. `create_all`
    crea lo que falta y nunca altera lo que existe, así que es inocuo, pero significa que
    el `CREATE TABLE` del `.sql` puede llegar tarde; por eso el script lleva además un
    `ALTER … MODIFY activo tinyint NOT NULL DEFAULT 1` idempotente (SQLAlchemy resuelve
    el default en Python, no en MySQL, y una fila insertada a mano sin ese campo quedaría
    en NULL, o sea leída como dada de baja).
  - **API:** solo **rutas nuevas** — `GET /api/fragancias`, `POST /api/admin/fragancias`,
    `GET`/`POST /api/admin/maquina_fragancias`, `PUT /api/admin/maquina_fragancias/{id}`.
    Lo único que cambia en una respuesta existente es la clave `fragancias` añadida a
    `GET /api/admin/catalogos`, que exige token admin y solo consume esta web.
  - Verificado con `diff` 8000 vs 8001 en **23 endpoints**: 22 idénticos byte a byte
    (`/api/maquinas`, `/api/operadores` con y sin `tipo`, los cinco del dashboard con
    rango, franja y segmentadores, `opciones_filtros`, `comentarios_turno`,
    `mantenimiento/checklist`, `tablets/estado`, `insumos/dashboard`). El único distinto,
    `/dashboard/paros`, difería en las décimas de segundo del cronómetro de un paro
    abierto, no por el código. **Las tablets no necesitan actualización.**
  - Semántica y contrato para Android en `api_produccion/CAMBIO_ANDROID_fragancias.md`.

- **Segmentación del dashboard** (2026-08-05, **en producción**).
  Dos endpoints tocados, en los dos añadiendo **solo parámetros opcionales**:

  - `GET /dashboard/estadisticas` acepta `maquina`/`operador`/`marca`/`presentacion`/
    `fragancia` **repetibles**, los mismos que ya tenían los otros cuatro endpoints con
    rango; se aplican con `_aplicar_filtros()`, que ya existía. Son dos líneas: la firma y
    la llamada. Motivo: era el único endpoint con rango que no podía segmentarse, y su
    tarjeta contradecía al resto del dashboard en cuanto se ponía un filtro.
  - `GET /dashboard/opciones_filtros` acepta esos cinco más `hora_desde`/`hora_hasta`,
    para **encadenar** los menús: con `maquina=Máquina 7B` la lista de `operador` pasa a
    ser solo la de quienes trabajaron ahí. Motivo: ofrecía la plantilla entera y el
    catálogo entero, así que la mayoría de combinaciones daban cero. **La regla que no es
    evidente** está en el docstring: a cada dimensión se le aplican los filtros de *las
    otras*, nunca el suyo; si se aplicara el propio, al marcar «Máquina 7B» la lista de
    máquinas se reduciría a `["Máquina 7B"]` y la multi-selección quedaría inservible en
    cuanto se marca el primer valor.

  Sin los parámetros nuevos las respuestas son idénticas byte a byte, verificado con
  `diff` 8000 vs 8001 en **47 casos** entre las dos tandas: las 4 `dim` con y sin rango,
  los 4 presets de `rango`, la franja de noche, el `dim` inválido que sigue dando 400, las
  6 formas de llamar a `opciones_filtros` (sin nada, rango de un día, de varios, solo
  `desde`, solo `hasta`, rango vacío en el futuro) y el resto de endpoints del dashboard,
  mantenimiento, tablets, operadores, máquinas e insumos. **Las tablets no necesitan
  actualización.**

- **Lectura de paros y de comentarios de turno** (2026-08-05, **en producción**). Dos
  **rutas nuevas** en `routers/dashboard.py`:
  `GET /dashboard/paros` y `GET /dashboard/comentarios_turno`. Al ser rutas que no
  existían, ninguna respuesta anterior cambia; se verificó igualmente en el 8001 que 15
  endpoints (los cinco del dashboard con y sin rango, `agrupar=dia`, franja horaria,
  `/operadores`, `/maquinas`, `/tablets/estado`, `/mantenimiento/checklist`) responden
  idéntico byte a byte, así que **las tablets no necesitan actualización**. Motivo: los
  paros los escriben las tablets desde siempre (`POST /api/paro/iniciar|finalizar`) pero
  no había forma de leerlos salvo abriendo la hoja «Paros» del Excel de producción; los
  comentarios de turno se guardaban sin que nada los leyera. Semántica en §3.

- **Franja horaria en el dashboard** (2026-08-03). Los cinco endpoints con rango del
  dashboard (`kpis`, `produccion_hora`, `estado_operativo`, `top_produccion`,
  `estadisticas`) aceptan `hora_desde` / `hora_hasta` **opcionales**, formato `HH:MM`.
  Sin ellos la respuesta es idéntica byte a byte a la anterior (verificado con `diff`
  contra una instancia paralela del código viejo, 8 casos incluido `agrupar=dia`), así
  que **las tablets no necesitan actualización**. Motivo: los turnos de la planta no
  coinciden con el día natural — el de noche cruza medianoche — y el rango de fechas
  solo no permitía aislarlos. Detalles de semántica en §3.
- **Agrupación diaria del gráfico de producción** (2026-08-03).
  `GET /api/dashboard/produccion_hora` acepta `?agrupar=hora|dia` **opcional**. Sin el
  parámetro la respuesta es idéntica byte a byte a la anterior (verificado con `diff`),
  así que **las tablets no necesitan actualización**. Motivo: el endpoint agrupa por
  `extract("hour", …)`, de modo que en un rango de varios días sumaba la misma hora de
  todos los días en un punto — no era una línea de tiempo, y se leía mal.
  La web elige la agrupación sola según el rango (ver §2).
- **Operarios clasificados por línea** (`operadores.tipo` = `SOLIDO` | `LIQUIDO`), igual que
  las máquinas. Migración en `api_produccion/alter_operadores_tipo.sql`.
- `GET /api/operadores` acepta `?tipo=` **opcional**. Sin el parámetro devuelve todos con el
  formato de siempre, así que **las tablets no necesitaron actualización**.
- La web permite alta por línea, filtro y cambio de línea desde `/admin` → Operarios.

### Pendiente

1. **App Android** — dos cambios, ninguno urgente (sin ellos todo sigue igual):
   - el selector «Seleccione Operador» solo filtra por línea si la app pasa `?tipo=` con
     el tipo de su máquina → `api_produccion/CAMBIO_ANDROID_tipo_operario.md`;
   - el selector de fragancia sigue con su lista fija hasta que la app llame a
     `GET /api/fragancias?maquina=&marca=` → `api_produccion/CAMBIO_ANDROID_fragancias.md`.
     **Mientras no lo haga, configurar fragancias en la web no cambia lo que ve el
     operario en la tablet.**
2. **Validación de fragancia en `iniciar_turno`** — hoy valida
   `(máquina, marca, presentación)` pero **no** la fragancia, a propósito: activarla antes
   de que todas las tablets lean `/api/fragancias` rechazaría turnos legítimos. Cuando la
   app esté actualizada se puede añadir, con autorización aparte.
3. **Paros sin cerrar** — el garbage collector de `tasks.py` cierra los turnos colgados a
   las 13 h pero **no cierra los paros abiertos de esa sesión**, así que quedan con
   `fin_paro` NULL para siempre (hay 1 de 78 así: el paro 105). La vista de paros los
   distingue con el estado «SIN CIERRE» en vez de contarlos como paros en curso, pero el
   arreglo de fondo está en el backend y **no se ha tocado** (haría falta autorización).
4. **Máquinas de línea líquida** — ya hay dos dadas de alta (`Máquina 3` y `Maquina 12`),
   sin producción registrada todavía. Total: 8 máquinas activas, una de ellas `PRUEBA`.
5. **Logo provisional** — `public/logo192.png` es el de Create React App. El de la cabecera
   es un SVG hecho a partir de las capturas (`components/ui/Logo.js`); si aparece el
   original, se sustituye por un `<img>`.
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
    │   ├── format.js            # es-EC, duraciones, turno, antigüedad, etiqueta de tablet
    │   ├── filtros.js           # dimensiones segmentables, params y serialización repetida
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
    │       └── TabUsuarios.js   # administradores y niveles — solo la ve un SUPERADMIN
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

### Segmentadores multi-selección

Barra de cinco desplegables (`Máquina`, `Operario`, `Marca`, `Presentación`, `Fragancia`)
justo debajo del título del dashboard. Los cinco parámetros existían ya en cuatro de los
cinco endpoints con rango; el quinto (`estadisticas`) se añadió el 2026-08-05 (§0).

- **Se aplican al instante**, sin pasar por «Cargar»: acotan lo que ya se está viendo, no
  piden un período distinto (mismo criterio que «Todo el día» de la franja).
- **Dentro de una dimensión, OR** (el `IN (...)` del backend); **entre dimensiones, AND**.
  Verificado contra el 8000: `Máquina 7` = 600 pacas, `Máquina 9` = 865, las dos juntas
  1465 = 600 + 865.
- **Ninguna dimensión seleccionada = todas**, y entonces su parámetro **no viaja**: sin
  segmentar la petición es idéntica a la de antes de existir el filtro. El botón «Todos»
  de cada desplegable vacía la selección en vez de marcar los valores uno a uno.
- **Serialización con claves repetidas** (`maquina=A&maquina=B`) vía
  `paramsSerializer` de `lib/filtros.js`. **Es imprescindible**: axios serializa los
  arrays como `maquina[]=A`, y con esa forma el backend recibe un parámetro que no
  conoce, lo ignora y devuelve el total sin filtrar — comprobado contra el 8000, que
  con `maquina[]=Máquina 7` responde las 2832 pacas del día entero. La web mostraría
  datos sin segmentar como si estuvieran segmentados.
- **Los menús están encadenados y responden al rango, a la franja y entre sí.** Se piden
  con todo el filtro global, así que solo ofrecen lo que existe de verdad: cambiar la fecha
  cambia las listas, y marcar `Máquina 7B` deja en Operario solo a quienes trabajaron ahí.
  A cada dimensión el backend le aplica los filtros de *las otras* y no el suyo, por lo que
  la lista de máquinas sigue completa y se pueden seguir sumando máquinas a la selección.
- Los filtros **no se resetean** al cambiar de rango, y un valor seleccionado que deja de
  aparecer en las opciones **se sigue mostrando**, al final de la lista, marcado y
  atenuado, con un `title` que explica que no aporta nada. Pasa al cambiar de rango y al
  combinar dimensiones (si filtras por un operario, las máquinas donde no trabajó se
  quedan sin datos aunque sigan marcadas). Se sigue aplicando: si desapareciera del menú
  quedaría filtrando sin que nada lo delate.
- **Alcance**: segmentan los **cinco** endpoints con rango — `kpis`, `produccion_hora`,
  `estado_operativo`, `top_produccion` y `estadisticas`. **No** logs, checklists, insumos,
  comentarios ni los Excel, cuyos endpoints no aceptan los parámetros. La barra lo dice
  explícitamente cuando hay algo seleccionado.
- **La agrupación de «Estadísticas de producción» se deduce de lo segmentado**
  (`dimAutomatica` en `lib/filtros.js`), y la tarjeta ya **no tiene tabs propios**: tener
  dos controles llamados «Máquina» y «Operario» a dos dedos de distancia, uno para filtrar
  y otro para agrupar, era la parte confusa. La regla responde a lo que el filtro deja
  abierto, y su orden es la precedencia:

  | Segmentado | Agrupa por | Porque falta saber |
  |---|---|---|
  | operario (con o sin máquina) | `marca_presentacion` | **qué** producían |
  | máquina | `operario` | **quién** produjo en ella |
  | marca / presentación / fragancia, o nada | `maquina` | **dónde** se produjo |
- La vista `/paros` **no** los lleva: su endpoint solo acepta `maquina` y `operador`, y la
  barra no se muestra allí, así que no hay filtro visible que no se esté aplicando.

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

**La sesión se cierra sola a los 15 minutos sin actividad**, sin aviso previo (decisión del
responsable, 2026-08-07). Quien corta de verdad es el backend; el navegador lo hace también
por su cuenta con `lib/useInactividad.js` por un motivo concreto: **todas las pestañas menos
Mensajes cargan una sola vez**, así que sin peticiones el panel se quedaría abierto y con
aspecto de operativo aunque el token ya estuviera muerto — y con Mensajes abierta pasa lo
contrario, su refresco de 15 s renovaría el token en el servidor para siempre. Detalles:

- Cuentan como actividad `mousedown`, `keydown`, `touchstart` y `wheel`. **No `mousemove`**:
  un ratón rozado por la vibración de la planta no debe renovar una sesión.
- Se mide con dos lecturas del **mismo** reloj (`Date.now()`), nunca contra la hora del
  servidor: un equipo con la hora mal puesta no adelanta ni retrasa el cierre. Si el equipo
  se suspende, al despertar la sesión ya está cerrada, que es lo correcto.
- Al vencer llama a `salir()`, o sea `POST /admin/logout`: **revoca el token en el
  servidor**. Limpiar solo el `localStorage` dejaría el token vivo.
- El límite lo manda el backend en el login (`inactividad_segundos`) y la web resta 20 s,
  para cerrar ella antes de que el 401 la pille a media petición. Los 15 minutos no están
  escritos dos veces.
- El login explica por qué se volvió ahí (`aviso`), distinguiendo la inactividad del
  reinicio del servicio, que llega como un 401 con otro `detail`.

**«Eliminar» nunca llama a `DELETE`.** Tanto en Operarios como en las combinaciones y las
fragancias de Jerarquía hace `PUT {activo: false}` tras confirmación, porque los endpoints
de borrado del backend son físicos y dejarían huérfano el histórico. Hay tests que lo
verifican.

**La pestaña Jerarquía administra dos jerarquías con granularidad distinta**, y no es un
descuido: `maquina_productos` va por (máquina, marca, presentación) y
`maquina_marca_fragancias` por (máquina, marca) — la fragancia no depende del gramaje. En
la tarjeta de cada máquina, el bloque «Fragancias por marca» solo ofrece las marcas que
esa máquina produce, las activas se muestran como chips ámbar con «×» y las quitadas
siguen visibles tachadas para poder reactivarlas. Una marca **sin ninguna fragancia
activa** se rotula «sin configurar · se ofrecen todas», porque el endpoint cae al catálogo
completo en ese caso y el hueco se leería, si no, como «esta marca no lleva fragancia».

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
- `src/lib/filtros.test.js` — que las cinco claves siguen siendo las que acepta el backend,
  que una dimensión vacía no viaja, la tabla de agrupación automática (incluido que nunca
  devuelve un `dim` que el endpoint rechace con 400), y sobre todo **la serialización sin
  corchetes**: es el punto donde un fallo silencioso mostraría datos sin filtrar como
  filtrados.
- `src/App.test.js` — montaje del dashboard, tarjetas, tabla de detalle, los dos
  casos de fallo (endpoint aislado y API entera caída), la agrupación hora/día, y la
  franja horaria: que sin ella **no** viajen `hora_desde`/`hora_hasta`, que `19:00→07:00`
  llegue a los cinco endpoints, que «Todo el día» la quite sin pulsar «Cargar», y que
  Estadísticas mande `desde`/`hasta` en vez de `rango`. De la vista de paros: los tres
  estados del semáforo, el desplegable con el motivo original, que un paro **sin cerrar
  no se presenta como en curso** y avisa de que la duración es estimada, el orden del
  ranking por tiempo, que la franja viaja a `/dashboard/paros` y que `/paros` es una URL
  propia con botón «atrás». De los segmentadores: que sin selección **ningún** parámetro
  viaja, dos operarios a la vez como lista, la combinación de tres dimensiones, que se
  aplican sin pulsar «Cargar», «Todos» y «Limpiar», los chips de quitar, que Estadísticas
  recibe los filtros y que no le queda ningún control propio, la tabla entera de
  agrupación automática, que un valor fuera del rango nuevo sigue visible y quitable, y
  el encadenamiento: que las opciones se piden con el rango, la franja y los demás
  filtros, que marcar una máquina acota la lista de operarios, y que **no** acota la de
  máquinas. `beforeEach` **resetea la ruta**: jsdom la conserva entre tests del mismo
  archivo y la vista se decide por el pathname.
- `src/components/admin/AdminApp.test.js` — las pestañas, incluido que
  «Eliminar» haga `PUT {activo:false}` y nunca `DELETE`. De las fragancias de Jerarquía:
  que van por marca y no por presentación, que el alta manda `{maquina_id, marca,
  fragancia}`, que el desplegable no repite una ya activa (sería un 409), que quitar es
  `PUT {activo:false}` y nunca `DELETE`, que una quitada sigue reactivable, que una marca
  sin ninguna avisa de que se ofrecen todas, y el alta en el catálogo maestro.
  De Producción y Usuarios (2026-08-06): que «CERRAR TURNO» solo sale en las sesiones
  activas y avisa cuando la tablet sigue conectada, que eliminar una sesión con
  producción **exige teclear su número** y no borra nada si se teclea mal, que un
  `ADMINPLANTA` cierra turnos pero no ve «Eliminar», que un `CONSULTA` no ve ninguna
  acción de escritura, que la pestaña Usuarios solo existe para un `SUPERADMIN`, que
  una contraseña corta ni sale de la web, y que dar de baja a un usuario es
  `PUT {activo:false}` y nunca `DELETE`. `mockNivel` simula el nivel de la sesión (el
  prefijo `mock` es obligatorio: `jest.mock` se iza sobre los `let`).
  Del historial de pacas: que los registros **no se piden hasta abrir** el
  desplegable, que la hora llega como `YYYY-MM-DD HH:MM:SS` y el input la muestra
  como `T` sin segundos, que **solo viaja el campo que se tocó** —reenviar la hora
  intacta pondría los segundos a cero—, que borrar un registro avisa de la salida no
  destructiva, y que un `ADMINPLANTA` edita pero no borra y un `CONSULTA` ve los
  campos deshabilitados.
  Del estado de la tablet (2026-08-07): que el chip de Mensajes dice **cuándo verá el
  mensaje** —«AL INSTANTE» con el WebSocket abierto, «EN COLA · contacto hace 21m» si
  no— y que el aviso de cerrar turno salta también **sin conexión pero con contacto
  reciente** (es el caso peligroso: alguien produciendo con el WS caído), no salta con
  dos horas de silencio, y no confunde «nunca reportó» con «contacto hace 0s».
  De la caducidad por inactividad (2026-08-07): que a los 15 minutos sin tocar nada se
  vuelve al login **y se llama a `salir()`** —revocar el token en el servidor es la mitad
  del cambio; limpiar el estado local solo, no sirve—, que cualquier tecla reinicia la
  cuenta y que la sesión sigue viva 14 minutos después, y que un 401 con el `detail` de
  inactividad lo explica en el login. Usan temporizadores falsos (`jest.useFakeTimers`),
  que en Jest moderno simulan también `Date.now()`, que es lo que lee el hook.

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

## 3. API — endpoints

Base: `/api`. En producción se consume por el proxy de nginx (ruta relativa `/api`);
así se evita CORS. El `App.js` heredado apunta a `http://150.36.200.252:8000/api`.

### Públicos (sin autenticación)

| Método | Ruta | Parámetros | Respuesta |
|---|---|---|---|
| GET | `/dashboard/kpis` | `desde`,`hasta`,`hora_desde`,`hora_hasta`,`maquina[]`,`operador[]`,`marca[]`,`presentacion[]`,`fragancia[]` | `{pallets_hoy, pacas_hoy, sacos_hoy, turnos_activos, eficiencia}` |
| GET | `/dashboard/logs` | — | `[{hora:"HH:MM:SS", mensaje, tipo:"pallet"}]` · 15 más recientes, desc |
| GET | `/dashboard/produccion_hora` | mismos filtros + `agrupar` (opcional) | `[{hora, pallets, detalle:[{maquina,operario,producto,pacas}]}]`. Sin `agrupar` o con `hora`: un punto por hora del reloj, `hora:"HH:00"` — con rango de varios días **suma esa hora de todos los días**. Con `agrupar=dia`: un punto por fecha, `hora:"YYYY-MM-DD"`. Valor desconocido → se trata como `hora`, igual que `?tipo=` |
| GET | `/dashboard/estado_operativo` | mismos filtros | `[{sesion_id, maquina, operador, producto, inicio_turno, tiempo_transcurrido, total_pacas, estado}]` |
| GET | `/dashboard/top_produccion` | mismos filtros | `[{name, value}]` desc |
| GET | `/dashboard/opciones_filtros` | `desde`,`hasta`,`hora_desde`,`hora_hasta`,`maquina[]`,`operador[]`,`marca[]`,`presentacion[]`,`fragancia[]` | `{maquina[], operador[], marca[], presentacion[], fragancia[]}`. Sin `desde`/`hasta` es el catálogo histórico completo. Los filtros **encadenan** los menús, y a cada dimensión se le aplican los de *las otras*, **nunca el suyo** (si no, marcar un valor vaciaría su propia lista). La franja recorta por `inicio_turno` |
| GET | `/dashboard/estadisticas` | `dim`, `rango`, `desde`, `hasta`, `hora_desde`, `hora_hasta`, `maquina[]`, `operador[]`, `marca[]`, `presentacion[]`, `fragancia[]` | `{dim, rango, total_pacas, total_sesiones, items:[{etiqueta,pacas,sesiones,pct}]}`. Los cinco filtros se añadieron el 2026-08-05 (§0); un `dim` desconocido sigue dando **400** |
| GET | `/dashboard/paros` | `desde`,`hasta`,`hora_desde`,`hora_hasta`,`maquina[]`,`operador[]` | `{kpis, maquinas[], paros[], por_categoria[]}` — ver «Paros» más abajo |
| POST | `/reportes_app` | `{texto, maquina?, operador?, session_id?, request_id?}` | `{id, mensaje, duplicado?}`. Lo escribe el botón «reportar problema» de la tablet. Idempotente por `request_id`. Desde el 2026-08-07 **envía un correo** (solo si la fila es nueva) a `REPORTES_EMAIL_TO`. Texto vacío → **400** |
| GET | `/dashboard/comentarios_turno` | `desde`,`hasta` **o** `limit` (def. 30, máx. 200) | `[{id,sesion_id,maquina,operador,texto,creado_en,fecha,hora}]` desc. Sin `desde`/`hasta` devuelve los últimos `limit` sin importar la fecha |
| GET | `/mantenimiento/checklist` | `limit` (def. 30) **o** `desde`,`hasta` | `[{id,maquina,operador,momento,codigo_turno,fecha_turno,fecha,hora,supervisor,comentarios,items:[{etiqueta,marcado}],total_items,items_ok,creado_en}]` |
| GET | `/tablets/estado` | — | `[{device_id,nombre,maquina,pendientes,ultimo_heartbeat,ultima_sincronizacion,en_linea,segundos_desde_heartbeat}]` |
| POST | `/tablets/sincronizar/{device_id}` | — | `{device_id, enviada, motivo}` |
| POST | `/tablets/sincronizar_todas` | — | `{total, enviadas}` |
| GET | `/insumos/dashboard` | `desde`,`hasta` | `{rango, kpis{total_pedidos,tiempo_resp_prom_seg,con_discrepancia,entregas_proactivas}, pedidos[], entregas[]}` |
| GET | `/operadores` | `tipo` (opcional) | `[{id, nombre}]` activos. `?tipo=SOLIDO\|LIQUIDO` filtra por línea; **sin el parámetro devuelve todos**, que es lo que hace la app Android actual. Un tipo desconocido se ignora en vez de vaciar el selector |
| GET | `/maquinas` | — | `[{id,nombre,tipo,marcas:[{nombre,presentaciones[]}]}]` — jerarquía completa. **No trae fragancias**: van aparte, en `/fragancias` |
| GET | `/fragancias` | `maquina`, `marca` (opcionales) | `["Floral","Limón"]` — las de esa máquina+marca. **Nunca devuelve lista vacía**: sin parámetros, sin configuración para esa combinación o con una máquina desconocida cae al catálogo activo completo, para que una configuración a medias no deje al operario sin poder iniciar turno (mismo criterio que `/maquinas` sin jerarquía) |
| GET | `/reportes/excel` | `desde`,`hasta` | .xlsx producción (404 si el rango está vacío) |
| GET | `/reportes/formularios_excel` | `desde`,`hasta` | .xlsx checklists (404 si vacío) |
| GET | `/reportes/insumos_excel` | `desde`,`hasta` | .xlsx insumos (404 si vacío) |

`dim` ∈ `maquina` · `operario` · `marca_presentacion` · `marca_presentacion_fragancia`
`rango` ∈ `hoy` · `semana` (7d) · `mes` (30d) · `todo`. Si se envían `desde`/`hasta`,
mandan sobre `rango`.

Sin `desde`/`hasta`, todos los endpoints con rango equivalen **al día de hoy**.

### Franja horaria (`hora_desde` / `hora_hasta`)

Opcionales en los cinco endpoints con rango. Formato `HH:MM` (también valen `HH` y
`HH:MM:SS`). **Inicio inclusivo, fin exclusivo**, igual que el fin de `_rango()`.

- **Cruzan medianoche a propósito:** si `hora_desde` > `hora_hasta`, `19:00→07:00`
  significa 19:00–23:59 **más** 00:00–06:59. Es la única forma de aislar el turno de
  noche de la planta en una sola consulta.
- Uno solo de los dos límites vale: `hora_desde=10:00` es «de las 10 en adelante».
- Un valor no parseable, o los dos límites iguales, **se ignoran** (no dan 400), igual
  que `?agrupar=` y `?tipo=`. Se comprobó que las franjas `06:00–17:30` y `19:00–07:00`
  particionan exactamente el total del día (29 406 + 19 850 = 49 256).
- **Qué columna filtra, por endpoint** (no es uniforme, y es deliberado):
  - Producción (`produccion_hora`, `top_produccion`, y las pacas/sacos de `kpis`) →
    `pallets.fecha_hora`, la hora en que se registró el pallet.
  - Turnos (`estado_operativo`, y `turnos_activos` de `kpis`) →
    `sesiones.inicio_turno`, la hora en que arrancó el turno. La fila representa una
    sesión entera, así que `total_pacas` sigue siendo el total de la sesión aunque
    parte quede fuera de la franja.
  - `estadisticas` es **mixto**: el rango de **fechas** filtra por `inicio_turno`
    (comportamiento histórico del endpoint) y la **franja horaria** por
    `pallets.fecha_hora`, que es lo que responde a «quién produjo más en el turno de
    noche». Consecuencia: con franja activa, quien no produjo nada dentro de ella
    desaparece del ranking en vez de salir con 0 pacas — el `outerjoin` pasa a
    comportarse como `join`.
- **Los endpoints de `/reportes/` NO la aceptan.** Los Excel salen con el día completo;
  la UI lo advierte en el propio filtro para que no se dé por hecho lo contrario.
- En `/dashboard/paros` filtra por `paros_maquina.inicio_paro`: un paro que empieza
  dentro de la franja cuenta entero aunque termine fuera (mismo criterio que
  `estado_operativo` con `inicio_turno`).

### Paros (`GET /dashboard/paros`)

Lo escriben las tablets con `POST /api/paro/iniciar` (`{sesion_id, motivo}`) y
`POST /api/paro/finalizar`; **este endpoint solo lee** la tabla `paros_maquina`.
El rango filtra por `inicio_paro`.

Respuesta: `{kpis, maquinas, paros, por_categoria}`.

- **`paros[]`** — del más reciente al más antiguo:
  `{id, sesion_id, maquina, operador, producto, categoria, comentario, motivo, inicio,
  fin, fin_estimado, estado, en_curso, duracion_segundos, duracion_estimada,
  inicio_turno, fin_turno}`.
- **`maquinas[]`** — semáforo, con **criterio mixto deliberado**: `estado`, `operador`,
  `inicio_turno` y `paro_actual` son **de ahora** (la máquina está parada o no en este
  instante, sea cual sea el rango), mientras `paros` y `segundos` son **del rango**.
  Incluye las máquinas activas del catálogo más cualquiera que aparezca en los paros
  del rango aunque esté dada de baja.
- **`kpis`** — `{total_paros, en_curso, sin_cierre, segundos_total, segundos_promedio,
  maquinas_paradas, maquinas_produciendo}`. `segundos_promedio` es `null` si ningún
  paro del rango tiene duración conocida (no `0`, que sería un dato falso).
- **`por_categoria[]`** — `{categoria, paros, segundos}` ordenado por tiempo parado.

Dos reglas que **no son evidentes** y que se decidieron leyendo los datos reales:

1. **El `motivo` trae la categoría dentro.** La tablet lo manda como
   `"[Categoría] - comentario libre"` (`"[Mantenimiento] - cambio de teflón tubo
   formador"`), así que `_desglosar_motivo()` lo parte en `categoria` (en mayúsculas) y
   `comentario`. Cuando no hay corchetes —`"ALMUERZO"`, que la app envía tal cual— el
   motivo entero **es** la categoría y `comentario` va `null`, no una cadena vacía ni
   texto inventado. El `motivo` original se devuelve siempre íntegro.
2. **`fin_paro IS NULL` no significa «parada ahora».** El `estado` se calcula cruzando
   el paro con su sesión:
   - `CERRADO` — tiene `fin_paro`; la duración es la registrada.
   - `EN CURSO` — sin `fin_paro` y **con el turno abierto**: la máquina está parada
     ahora y la duración corre contra el reloj.
   - `SIN CIERRE` — sin `fin_paro` pero **con el turno ya cerrado**. Pasa de verdad: el
     garbage collector de `tasks.py` cierra los turnos colgados a las 13 h sin cerrar
     sus paros. La duración se **acota al fin del turno** y se marca con
     `duracion_estimada: true` y `fin_estimado`, en vez de dejarla crecer sin fin (si no,
     el paro 105 aparecería como un paro «en curso» de días). La UI lo dice explícitamente
     en el desplegable de esa fila.

### Administración (requieren cabecera `X-Admin-Token`)

Login: `POST /api/admin/auth {nombre, pin}` →
`{token, username, nivel_acceso, inactividad_segundos}`.
El token se guarda **en memoria del proceso**: al reiniciar `sigep.service` caducan
todas las sesiones admin y hay que volver a entrar. `POST /api/admin/logout` lo revoca.
Sin token o con token inválido: **401**.

**Caducidad por inactividad** (2026-08-07): un token muere tras `INACTIVIDAD_MAX`
(**15 min**, ajustable con la variable de entorno `ADMIN_INACTIVIDAD_MIN`; un valor
ilegible o ≤ 0 cae al defecto, nunca a «sin caducidad») sin ninguna petición. Cada
petición autenticada renueva el reloj en `require_admin`, así que se mide **inactividad,
no antigüedad**, y no hay tope absoluto. El 401 de un token caducado trae
`detail: "Sesión cerrada por inactividad…"`, distinto del «Sesión admin requerida o
expirada» de un token inexistente: la web usa esa diferencia para explicarlo en el login.
`inactividad_segundos` en el login existe para que el frontend no repita el número.

**Contraseñas** (2026-08-06): `administradores.password` guarda un hash PBKDF2-SHA256
(`services/seguridad.py`, librería estándar, sin dependencias). Las que quedan en texto
plano se aceptan igual y **se migran solas** en el primer login de esa persona, así que
no hizo falta ningún `UPDATE`. Aplica a los dos logins: el de la web y el
`POST /api/admin/login` que usa la app Android.

**Niveles de acceso** (2026-08-06): antes `nivel_acceso` no controlaba nada. Ahora
`require_nivel` los exige **en el backend**, endpoint por endpoint; la web solo oculta
los controles, que no es lo mismo.

| Nivel | Puede |
|---|---|
| `SUPERADMIN` | todo, incluidos usuarios, `DELETE` de sesión y los borrados físicos |
| `ADMINPLANTA` · `ADMINBODEGA` · `ADMIN` | operación diaria: corregir, cerrar turnos, catálogos, mensajes |
| `CONSULTA` | **solo lectura**: los `GET` responden, cualquier escritura da **403** |

Los `GET` siguen pidiendo solo token válido. Un nivel insuficiente da **403**, no 401:
la sesión es buena, lo que falta es permiso. `GET /api/admin/supervisores` (el selector
de supervisor de las tablets) filtra por los niveles operativos, así que un usuario
`CONSULTA` no aparece ahí.

| Método | Ruta | Notas |
|---|---|---|
| GET | `/admin/operadores` | `[{id,nombre,tipo,activo}]`, activos primero, luego alfabético. Acepta `?tipo=` |
| POST | `/admin/operadores` | `{nombre, tipo?}` — `tipo` ∈ `SOLIDO` (default) \| `LIQUIDO`. Si existe inactivo lo **reactiva**. 409 si ya está activo |
| PUT | `/admin/operadores/{id}` | `{nombre?, tipo?, activo?}` → desactivar = `{activo:false}` |
| DELETE | `/admin/operadores/{id}` | ⚠️ **borrado físico** (`db.delete`) |
| GET | `/admin/sesiones` | `desde`,`hasta` → `[{id,maquina,operador,marca,presentacion,fragancia,inicio,fin,estado,total_pacas,n_registros}]` |
| PUT | `/admin/sesiones/{id}` | `{maquina?,operador?,marca?,presentacion?,fragancia?}` |
| POST | `/admin/sesiones/{id}/cerrar` | Cierra un turno abierto: paro abierto + pedidos vivos + `fin_turno` + `duracion_minutos`, y deja `observaciones = "CERRADO MANUALMENTE POR: X"`. 400 si ya estaba cerrado. **No avisa a la tablet**: si sigue trabajando, seguirá mandando pacas a una sesión cerrada y su finalizar dará 400 |
| DELETE | `/admin/sesiones/{id}` | ⚠️ **borrado físico en cascada**, solo `SUPERADMIN`: sesión + pallets + paros + pedidos + comentarios + reportes + mensajes. Devuelve el recuento de lo borrado, que es la única traza que queda |
| GET | `/admin/usuarios` | solo `SUPERADMIN`. `[{id,username,nivel_acceso,activo,password_migrada,es_tu_usuario}]` — **nunca** la contraseña |
| POST | `/admin/usuarios` | `{username,password,nivel_acceso}`, mínimo 6 caracteres. Reactiva si existía inactivo |
| PUT | `/admin/usuarios/{id}` | `{password?,nivel_acceso?,activo?}`. No puedes desactivarte ni degradarte a ti mismo, ni dejar el sistema sin ningún `SUPERADMIN` activo |
| GET | `/admin/niveles` | los niveles con su descripción, para el selector |
| GET | `/admin/sesiones/{id}/pallets` | `[{id,cantidad_pacas,fecha_hora}]` — alimenta el desplegable del historial |
| PUT | `/admin/pallets/{id}` | `{cantidad_pacas?, fecha_hora?}` — los dos opcionales desde el 2026-08-06 (antes `cantidad_pacas` era obligatorio, y ese cuerpo sigue valiendo). La hora acepta `AAAA-MM-DD HH:MM[:SS]` y el ISO con `T`; un valor no parseable da **400**. **Cambiar la hora mueve la producción de hora y de día** en KPIs, gráfico y Excel, que cuentan por `pallets.fecha_hora` |
| DELETE | `/admin/pallets/{id}` | ⚠️ **borrado físico**, solo `SUPERADMIN`. Para anular sin destruir, `PUT {cantidad_pacas: 0}`, que sí puede un operativo |
| GET | `/admin/checklists` | `desde`,`hasta`. Como el público **pero los items traen `id`** (necesario para editar) |
| PUT | `/admin/checklists/{id}` | `{supervisor?, comentarios?, items?:[{id,marcado}]}` |
| GET | `/admin/catalogos` | `{maquinas:[{id,nombre,tipo}], marcas:[str], presentaciones:[str], fragancias:[str]}` — solo activos. `fragancias` se añadió el 2026-08-06: es admin-only, ninguna tablet lo lee |
| GET | `/admin/maquina_fragancias` | `[{maquina_id,maquina,tipo,activa,marcas:[{marca,produce,fragancias:[{id,fragancia,activo}]}]}]` — incluye inactivas. Las marcas son la unión de las que produce y las que ya tienen fragancias, para que una combinación dada de baja no esconda sus fragancias |
| POST | `/admin/maquina_fragancias` | `{maquina_id,marca,fragancia}`. Reactiva si existía inactiva, 409 si ya está activa. La fragancia debe estar en el catálogo (422 si no) y la marca debe ser de esa máquina si tiene jerarquía (422 si no) |
| PUT | `/admin/maquina_fragancias/{id}` | `{fragancia?, activo?}` → quitar = `{activo:false}` |
| POST | `/admin/fragancias` | `{nombre}` — catálogo maestro, crea o reactiva |
| GET | `/admin/maquina_productos` | `[{maquina_id,maquina,tipo,activa,productos:[{id,marca,presentacion,activo}]}]` — incluye inactivos |
| POST | `/admin/maquina_productos` | `{maquina_id,marca,presentacion}`. Reactiva si existía inactiva |
| PUT | `/admin/maquina_productos/{id}` | `{marca?,presentacion?,activo?}` → desactivar |
| DELETE | `/admin/maquina_productos/{id}` | ⚠️ **borrado físico** |
| POST | `/admin/maquinas` | `{nombre, tipo?}` — `tipo` ∈ `SOLIDO`\|`LIQUIDO` (acepta `Sólido`/`Líquido`) |
| PUT | `/admin/maquinas/{id}` | `{nombre?, tipo?, activa?}` → alternar tipo y desactivar |
| POST | `/admin/marcas` | `{nombre}` |
| POST | `/admin/presentaciones` | `{nombre}` |
| GET | `/admin/sesiones_activas` | `[{sesion_id,maquina,operador,producto,inicio,tablet_online,segundos_desde_contacto}]`. **`tablet_online` es «tiene el WebSocket abierto ahora»**, no el heartbeat: los latidos llegan cada 20-25 min y contra el umbral de 60 s de `/api/tablets/estado` una tablet en producción salía OFFLINE 19 de cada 20 minutos. `segundos_desde_contacto` (nuevo, 2026-08-07) es el último latido de la tablet más reciente de esa máquina, o `null` si nunca reportó |
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
| Botones `↓ Producción` / `↓ Formularios` / `↓ Insumos` | `/reportes/excel`, `/reportes/formularios_excel`, `/reportes/insumos_excel` con `desde`/`hasta`. **No** se les manda la franja horaria: no la aceptan |
| Fila `FRANJA HORARIA hh:mm → hh:mm` | Cliente: `hora_desde`/`hora_hasta` para los cinco endpoints con rango. Vacía = «día completo». Se aplica con el mismo botón «Cargar»; el botón «Todo el día» la quita al instante. Se permite `19:00 → 07:00` (cruza medianoche) |
| Fila `SEGMENTAR POR Máquina · Operario · Marca · Presentación · Fragancia` | Cliente: multi-selección combinable, aplicada al instante, contra `/dashboard/opciones_filtros`. Sin selección = todas y el parámetro no viaja. Detalle y alcance en §2 |
| KPI `PRODUCCIÓN DE HOY` → `1.873` / `0` | `kpis.pacas_hoy` / `kpis.sacos_hoy` |
| KPI `TURNOS ACTIVOS` → `5` | `kpis.turnos_activos` |
| KPI `LÍNEAS CON TURNO HOY` → `7`, `5 activas · 2 finalizada(s)` | **Derivado** de `estado_operativo`: `length`, y conteo por `estado` |
| `Producción por hora / por día · pacas` | `produccion_hora[].hora` / `.pallets` (`.detalle` para el tooltip). Toggle `HORA \| DÍA` en la cabecera de la tarjeta: **automático** — con rango de un solo día va por hora y «DÍA» sale deshabilitado; en cuanto el rango abarca varios días pasa a por día. La elección manual dura hasta el siguiente «Cargar» |
| `Estado operativo · líneas` | `estado_operativo[]` completo |
| `Estadísticas de producción` | `estadisticas?dim=…&desde=&hasta=` + segmentadores. **Sin ningún control propio**: ni los presets `Hoy/7d/30d/Todo` de la versión vieja, ni los tabs de agrupación (`Máquina`, `Operario`, `Marca+Pres.`, `Marca+Pres.+Frag.`). Rango, franja y filtros son los de la cabecera, y la agrupación se deduce de lo segmentado (tabla en §2) |
| `Actividad en vivo` | `logs[]` |
| `Checklist de mantenimiento · 8 recientes` | `mantenimiento/checklist?limit=8` |
| `Top marcas · hoy` | `top_produccion[].name` / `.value` |
| `Solicitudes de insumos · últimas 24h` | `insumos/dashboard.pedidos[]` |
| `Comentarios de turno · 8 recientes` | `dashboard/comentarios_turno?limit=8`. Va en la **columna izquierda, debajo de Estadísticas**. Como la tarjeta de checklists, **no** va atada al rango: son esporádicos (uno por turno como mucho) y con el rango puesto en hoy quedaría vacía casi siempre |
| `Detalle de checklist de mantenimiento` | `mantenimiento/checklist?desde=&hasta=` — mismo criterio y orden que el Excel de formularios |
| Footer `Actualizado 12:22:11` | Cliente: hora del último refresco |

### Monitoreo de paros (`/paros`)

Subpágina nueva (2026-08-05), sin captura de referencia: se diseñó sobre el mismo
sistema visual del dashboard. Se entra por el botón `Paros` de la cabecera y tiene URL
propia. **Comparte el rango y la franja de la cabecera con el dashboard** para que las
dos vistas no puedan contradecirse; el botón «Cargar» es el mismo.

| Elemento | Origen |
|---|---|
| KPI `MÁQUINAS PARADAS AHORA` | `paros.kpis.maquinas_paradas` / `maquinas_produciendo`. Es **en vivo**, no del rango |
| KPI `PAROS REGISTRADOS` | `kpis.total_paros`, con `en_curso` y `sin_cierre` en el pie |
| KPI `TIEMPO TOTAL PARADO` / `DURACIÓN PROMEDIO` | `kpis.segundos_total` / `segundos_promedio` |
| `Estado de máquinas` | `paros.maquinas[]`. Una tarjeta por máquina; la que está en paro se resalta en ámbar y muestra categoría, comentario y un **cronómetro** |
| `Paros del rango` | `paros.paros[]`. Fila desplegable: producto, horas exactas, turno, sesión, comentario del operario y el motivo tal como lo mandó la tablet |
| `Paros por categoría` | `paros.por_categoria[]`. Ordenado por **tiempo parado**, no por número de paros: dos horas de mantenimiento pesan más que cinco atascos de un minuto |
| Franja horaria | Se aplica a los paros por su **hora de inicio**; el filtro lo advierte. No hay descargas en esta vista (la hoja «Paros» va dentro del Excel de producción, que se descarga desde el dashboard) |

El cronómetro de los paros abiertos cuenta desde `duracion_segundos` **más lo
transcurrido desde la respuesta**, no restando `inicio` al reloj del navegador: un
equipo con la hora mal puesta inventaría paros de horas o duraciones negativas.

### Administración (`/admin`)

| Pestaña | Endpoints |
|---|---|
| Cabecera / `Salir` | `POST /admin/auth`, `POST /admin/logout` |
| Operarios | `GET`/`POST` `/admin/operadores`, `PUT /admin/operadores/{id}` |
| Producción | `GET /admin/sesiones`, `PUT /admin/sesiones/{id}`, `POST /admin/sesiones/{id}/cerrar`, `DELETE /admin/sesiones/{id}`, `GET /admin/sesiones_activas` (para saber si la tablet sigue conectada); el desplegable de pacas usa `GET /admin/sesiones/{id}/pallets`, `PUT`/`DELETE /admin/pallets/{id}`; selects desde `/admin/catalogos` |
| Checklists | `GET /admin/checklists`, `PUT /admin/checklists/{id}` |
| Jerarquía | `GET /admin/maquina_productos`, `GET /admin/maquina_fragancias`, `GET /admin/catalogos`, `POST`/`PUT`/`DELETE /admin/maquina_productos`, `POST`/`PUT /admin/maquina_fragancias`, `POST`/`PUT /admin/maquinas`, `POST /admin/marcas`, `POST /admin/presentaciones`, `POST /admin/fragancias` |
| Mensajes | `GET /admin/sesiones_activas`, `POST /admin/mensajes/masivo` |
| Usuarios (solo `SUPERADMIN`) | `GET/POST /admin/usuarios`, `PUT /admin/usuarios/{id}`, `GET /admin/niveles` |

### Retirado del dashboard

- **`Tablets · sincronización`** — panel de chips con las 21 tablets (`/tablets/estado`,
  `/tablets/sincronizar/{id}`). **Retirado el 2026-08-05 a petición del responsable**:
  no se entendía y no servía para operar. Los endpoints `/tablets/*` **siguen en la API**
  y las tablets los usan; solo desapareció la tarjeta. El componente
  `components/TabletsSyncPanel.js` es recuperable del commit `ef07cb3` (o del tag
  `v1.2-pre-paros`), y `lib/format.js` conserva `etiquetaTablet()` y `antiguedad()`, con
  sus tests, por si vuelve.

### Sin endpoint propio (resuelto de otra forma)

- **Catálogo de fragancias** — la tabla `fragancias` **sí existe** (se empezó a usar el
  2026-08-06; antes estaba en MySQL sin que nada la leyera), pero el segmentador del
  dashboard y el editor de sesiones de `/admin` → Producción siguen poblándose con
  `dashboard/opciones_filtros.fragancia`, es decir con los **valores históricos**: una
  sesión vieja puede llevar una fragancia que ya no esté en el catálogo, y el select no
  debe perderla al editarla. El catálogo maestro solo manda en la pestaña Jerarquía.
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
- El garbage collector de turnos (`tasks.py`) cierra las sesiones colgadas a las 13 h
  pero **no cierra los paros abiertos de esas sesiones**, que quedan con `fin_paro` NULL
  a perpetuidad. La vista de paros lo compensa en lectura («SIN CIERRE»), pero el
  registro sigue incompleto.
- La instancia paralela de pruebas (`uvicorn` en el 8001) **también arranca el garbage
  collector** y escribe en la misma BD. No es peligroso —hace exactamente lo que el
  servicio vivo ya hace cada hora— pero conviene saberlo antes de levantarla.
- El `src/` del frontend no estaba versionado al día: solo 5 de los 10 componentes
  existían en git antes del checkpoint.
