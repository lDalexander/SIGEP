# SIGEP — API: endpoints

> Movido desde `CLAUDE.md` el 2026-10-14 para aligerar el contexto que se carga en cada
> sesión. Contenido literal, sin reescribir.

## 3. API — endpoints

Base: `/api`. En producción se consume por el proxy de nginx (ruta relativa `/api`);
así se evita CORS. El `App.js` heredado apunta a `http://150.36.200.252:8000/api`.

### Públicos (sin autenticación)

| Método | Ruta | Parámetros | Respuesta |
|---|---|---|---|
| GET | `/dashboard/kpis` | `desde`,`hasta`,`hora_desde`,`hora_hasta`,`maquina[]`,`operador[]`,`marca[]`,`presentacion[]`,`fragancia[]` | `{pallets_hoy, pacas_hoy, sacos_hoy, turnos_activos, eficiencia}` |
| GET | `/dashboard/logs` | — | `[{hora:"HH:MM:SS", mensaje, tipo:"pallet"}]` · 15 más recientes, desc |
| GET | `/dashboard/produccion_hora` | mismos filtros + `agrupar` (opcional) | `[{hora, pallets, detalle:[{maquina,operario,producto,pacas}]}]`. Sin `agrupar` o con `hora`: un punto por hora del reloj, `hora:"HH:00"` — con rango de varios días **suma esa hora de todos los días**. Con `agrupar=dia`: un punto por fecha, `hora:"YYYY-MM-DD"`. Valor desconocido → se trata como `hora`, igual que `?tipo=` |
| GET | `/dashboard/estado_operativo` | mismos filtros | `[{sesion_id, maquina, operador, producto, inicio_turno, tiempo_transcurrido, total_pacas, estado, velocidad_ultima, velocidad_promedio, velocidad_lecturas, velocidad_hora, velocidad_min, velocidad_max}]`. Las cuatro de velocidad se añadieron el 2026-08-19 y **viajan siempre**, con `null` cuando la sesión no tiene lecturas: un `0` por defecto sería indistinguible de una máquina detenida. `velocidad_min`/`velocidad_max` (2026-08-21) son el rango estándar de esa (máquina, presentación) y viajan igual, en `null` cuando nadie lo configuró: entonces la web enseña la lectura **sin colorearla**, porque no hay contra qué compararla |
| GET | `/dashboard/top_produccion` | mismos filtros | `[{name, value}]` desc |
| GET | `/dashboard/opciones_filtros` | `desde`,`hasta`,`hora_desde`,`hora_hasta`,`maquina[]`,`operador[]`,`marca[]`,`presentacion[]`,`fragancia[]` | `{maquina[], operador[], marca[], presentacion[], fragancia[]}`. Sin `desde`/`hasta` es el catálogo histórico completo. Los filtros **encadenan** los menús, y a cada dimensión se le aplican los de *las otras*, **nunca el suyo** (si no, marcar un valor vaciaría su propia lista). La franja recorta por `inicio_turno` |
| GET | `/dashboard/estadisticas` | `dim`, `rango`, `desde`, `hasta`, `hora_desde`, `hora_hasta`, `maquina[]`, `operador[]`, `marca[]`, `presentacion[]`, `fragancia[]` | `{dim, rango, total_pacas, total_sesiones, items:[{etiqueta,pacas,sesiones,pct}]}`. Los cinco filtros se añadieron el 2026-08-05 (§0); un `dim` desconocido sigue dando **400** |
| GET | `/dashboard/paros` | `desde`,`hasta`,`hora_desde`,`hora_hasta`,`maquina[]`,`operador[]` | `{kpis, maquinas[], paros[], por_categoria[]}` — ver «Paros» más abajo |
| GET | `/paro/estado` | `sesion_id` (obligatorio) | `{sesion_id, turno_abierto, en_paro, paro_abierto, ultimo_paro}`. **Solo lectura.** Existe para que la tablet deje de fiarse de su estado en memoria (§0): `paro_abierto: null` significa «no está en paro», no es un error, y `ultimo_paro` va aparte para reconocer un paro cacheado que ya se cerró. **404** solo si la sesión no existe |
| POST | `/paro/{paro_id}/foto` | multipart, campo `foto` | `{paro_id, foto_url, mensaje}`. Evidencia **opcional**. JPG/PNG, máx. 5 MB (otro tipo o más tamaño → **400**); volver a llamar **sustituye** la anterior y borra el archivo viejo. **Ruta aparte de `/paro/iniciar` a propósito**: pasar `iniciar` a multipart rompería las tablets de hoy |
| POST | `/velocidad` | `{sesion_id, velocidad, request_id?}` | `{velocidad_id, mensaje, velocidad, duplicado}`. Velocidad en **unidades/min** (el `detail` del 400 y los logs siguen diciendo «fundas/min»: es respuesta que consume la tablet y no se tocó), una fila por lectura. Idempotente por `request_id`. Fuera de 0.1–1000 → **400**; turno finalizado → **400**; sesión inexistente → **404** |
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

**`POST /api/paro/finalizar` tolera que el paro ya esté cerrado** (2026-08-19). Con paro
abierto se comporta exactamente igual que siempre. Si la sesión tuvo paros y **ninguno
está abierto**, en vez de 404 devuelve **200** con el último paro (`paro_id`,
`duracion_segundos` **registrada**, no recalculada) y `ya_cerrado: true`; esa clave **no
aparece** en la respuesta del camino normal. Si la sesión **no tuvo ningún paro**, sigue
dando **404**. El motivo está en §0: el estado de paro de la tablet es local y solo se
apaga con una respuesta buena, así que el 404 la dejaba bloqueada sin poder producir.

Respuesta: `{kpis, maquinas, paros, por_categoria}`.

- **`paros[]`** — del más reciente al más antiguo:
  `{id, sesion_id, maquina, operador, producto, categoria, comentario, motivo, inicio,
  fin, fin_estimado, estado, en_curso, duracion_segundos, duracion_estimada,
  inicio_turno, fin_turno, foto_url}`. `foto_url` (2026-08-19) es la ruta servida de la
  evidencia fotográfica, o `null`: la foto es **opcional** y lo normal es que no esté.
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

**Reparto por áreas** (2026-08-07): antes había un solo nivel «operativo» y quien
escribía, escribía en todo. Ahora cada nivel trabaja en la suya, y **`require_operativo`
ya no existe**: si vuelve a hacer falta uno transversal, es señal de que el endpoint está
en el área equivocada.

| Nivel | Área | Ve en `/admin` |
|---|---|---|
| `SUPERADMIN` | todo | las 11 pestañas |
| `ADMINPLANTA` · `ADMIN` | planta | Operarios, Producción, Paros, Checklists, Jerarquía, Mensajes |
| `ADMINBODEGA` | insumos | solo Insumos |
| `CONSULTA` | lectura de planta | las 6 de planta, sin ningún control de escritura |

`ADMIN` es el nivel heredado y se comporta como `ADMINPLANTA`. **Reportes, Tablets,
Usuarios y Correo son solo de `SUPERADMIN`**: no son operación diaria sino administración
del propio sistema. Los borrados físicos y el `DELETE` de sesión siguen siendo suyos también.

**Cuidado con `ADMINBODEGA`**: su pestaña lee del endpoint **público**
`/api/insumos/dashboard`, así que por la API de admin solo puede llamar a `PUT /pedidos` y
`PUT /entregas` (4 endpoints contando login y logout). Si algún día se protege ese
endpoint público, bodega se queda sin poder leer nada.

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
| GET | `/admin/sesiones` | `desde`,`hasta` → `[{id,maquina,operador,marca,presentacion,fragancia,inicio,fin,estado,total_pacas,n_registros,velocidad_promedio,velocidad_lecturas,velocidad_min,velocidad_max}]`. Las dos primeras de velocidad son del 2026-08-19; `velocidad_promedio` es `null` sin lecturas, y el recuento va al lado porque una media de una sola lectura no describe un turno. `velocidad_min`/`velocidad_max` (2026-08-21) son el estándar de esa (máquina, presentación), y aquí se comparan contra el **promedio**: el turno ya terminó y lo que se juzga es cómo fue entero, no un instante |
| DELETE | `/admin/paros/{id}/foto` | Quita la evidencia fotográfica **dejando el paro intacto**, y borra el archivo de `static/paros/` — dejarlo huérfano lo mantendría accesible por URL, y `/static` no pide autenticación. **400** si ese paro no tiene foto |
| PUT | `/admin/sesiones/{id}` | `{maquina?,operador?,marca?,presentacion?,fragancia?}` |
| POST | `/admin/sesiones/{id}/cerrar` | Cierra un turno abierto: paro abierto + pedidos vivos + `fin_turno` + `duracion_minutos`, y deja `observaciones = "CERRADO MANUALMENTE POR: X"`. 400 si ya estaba cerrado. **No avisa a la tablet**: si sigue trabajando, seguirá mandando pacas a una sesión cerrada y su finalizar dará 400 |
| DELETE | `/admin/sesiones/{id}` | ⚠️ **borrado físico en cascada**, solo `SUPERADMIN`: sesión + pallets + paros + pedidos + comentarios + reportes + mensajes. Devuelve el recuento de lo borrado, que es la única traza que queda |
| GET | `/admin/usuarios` | solo `SUPERADMIN`. `[{id,username,nivel_acceso,activo,password_migrada,es_tu_usuario}]` — **nunca** la contraseña |
| POST | `/admin/usuarios` | `{username,password,nivel_acceso}`, mínimo 6 caracteres. Reactiva si existía inactivo |
| PUT | `/admin/usuarios/{id}` | `{password?,nivel_acceso?,activo?}`. No puedes desactivarte ni degradarte a ti mismo, ni dejar el sistema sin ningún `SUPERADMIN` activo |
| GET | `/admin/niveles` | los niveles con su descripción, para el selector |
| GET | `/admin/correo` | solo `SUPERADMIN`. Configuración vigente + `destinos` con el `origen_to`/`origen_cc` de cada lista (`bd`\|`env`\|`heredado`). **Nunca la contraseña**: solo `password_definida` y `password_en_bd` |
| PUT | `/admin/correo` | solo `SUPERADMIN`. Toca únicamente los campos presentes. Lista `[]` = «vuelve a la del `.env`». `smtp_pass` vacío = **no la toques**; el literal `"-"` la borra para volver a la del `.env` |
| POST | `/admin/correo/prueba` | `{tipo, destinatario?}`. Envía en **primer plano** (aquí interesa la respuesta del SMTP, no una promesa). **502** si el envío falla |
| POST | `/admin/correo/semanal_ahora` | Manda el reporte de la última semana cerrada. **No mueve la marca** del programador |
| GET | `/admin/correo/semanal_vista_previa` | Los mismos números sin enviar nada |
| GET | `/admin/sesiones/{id}/pallets` | `[{id,cantidad_pacas,fecha_hora}]` — alimenta el desplegable del historial |
| PUT | `/admin/pallets/{id}` | `{cantidad_pacas?, fecha_hora?}` — los dos opcionales desde el 2026-08-06 (antes `cantidad_pacas` era obligatorio, y ese cuerpo sigue valiendo). La hora acepta `AAAA-MM-DD HH:MM[:SS]` y el ISO con `T`; un valor no parseable da **400**. **Cambiar la hora mueve la producción de hora y de día** en KPIs, gráfico y Excel, que cuentan por `pallets.fecha_hora` |
| DELETE | `/admin/pallets/{id}` | ⚠️ **borrado físico**, solo `SUPERADMIN`. Para anular sin destruir, `PUT {cantidad_pacas: 0}`, que sí puede un operativo |
| GET | `/admin/checklists` | `desde`,`hasta`. Como el público **pero los items traen `id`** (necesario para editar) |
| PUT | `/admin/checklists/{id}` | `{supervisor?, comentarios?, items?:[{id,marcado}]}` |
| GET | `/admin/catalogos` | `{maquinas:[{id,nombre,tipo}], marcas:[str], presentaciones:[str], fragancias:[str]}` — solo activos. `fragancias` se añadió el 2026-08-06: es admin-only, ninguna tablet lo lee |
| GET | `/admin/maquina_fragancias` | `[{maquina_id,maquina,tipo,activa,marcas:[{marca,produce,fragancias:[{id,fragancia,activo}]}]}]` — incluye inactivas. Las marcas son la unión de las que produce y las que ya tienen fragancias, para que una combinación dada de baja no esconda sus fragancias |
| GET | `/admin/velocidad_estandar` | `[{maquina_id,maquina,tipo,activa,presentaciones:[{presentacion,produce,estandar:{id,vel_min,vel_max,activo}\|null}]}]`. Las presentaciones son la unión de las que produce y las que ya tienen estándar, igual que las marcas de `maquina_fragancias`. `estandar: null` es «sin configurar», que **no** es un rango en cero |
| POST | `/admin/velocidad_estandar` | `{maquina_id,presentacion,vel_min,vel_max}` en **unidades/min**. Reactiva si existía dada de baja, **con los valores nuevos** (quien reconfigura está diciendo cuál quiere); 409 si ya está activa. La presentación debe ser de esa máquina si tiene jerarquía (422). Rango 0.1–1000 y `vel_min ≤ vel_max`, si no **400** |
| PUT | `/admin/velocidad_estandar/{id}` | `{vel_min?, vel_max?, activo?}` → quitar = `{activo:false}`. **El par se valida entero** aunque solo venga un extremo: subir el mínimo por encima del máximo guardado dejaría una fila que ninguna lectura puede cumplir |
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
| `Estado operativo · líneas` | `estado_operativo[]` completo. Columna **Unid./min** (2026-08-19; se llamaba «Fundas/min» hasta el 2026-08-21): la **última** lectura como cifra, y el promedio, el nº de lecturas y la hora en el `title`. Sin lecturas, «—» y un título que lo dice — nunca un cero. Debajo, `meta 32 – 38` si esa (máquina, presentación) tiene estándar, y la cifra en verde si está dentro y en ámbar si se sale por cualquiera de los dos lados; el `title` dice cuál. **Sin estándar, sin color y sin meta** |
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
| `Estado de máquinas` | `paros.maquinas[]`. Una tarjeta por máquina; la que está en paro se resalta en ámbar y muestra categoría, comentario, un **cronómetro** y la **foto del paro en curso** si la hay. `paro_actual` no trae `foto_url`: se empareja su `id` con `paros[]` en `ParosView`, así que consultando una fecha pasada no sale miniatura (el paro en curso no está en la lista del rango) |
| `Paros del rango` | `paros.paros[]`. Fila desplegable: producto, horas exactas, turno, sesión, comentario del operario, el motivo tal como lo mandó la tablet y la **evidencia fotográfica** si la hay. La fila cerrada marca con un icono de cámara los paros que traen foto; la miniatura abre un **visor sobre la propia página** (Escape o clic fuera para cerrar), con el enlace a otra pestaña dentro. La URL la resuelve `lib/fotos.js` contra el **8000**: la ruta relativa que manda la API no carga por el 3000 |
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
| Jerarquía | `GET /admin/maquina_productos`, `GET /admin/maquina_fragancias`, `GET /admin/velocidad_estandar`, `GET /admin/catalogos`, `POST`/`PUT`/`DELETE /admin/maquina_productos`, `POST`/`PUT /admin/maquina_fragancias`, `POST`/`PUT /admin/velocidad_estandar`, `POST`/`PUT /admin/maquinas`, `POST /admin/marcas`, `POST /admin/presentaciones`, `POST /admin/fragancias` |
| Mensajes | `GET /admin/sesiones_activas`, `POST /admin/mensajes/masivo` |
| Paros | `GET`/`PUT /admin/paros`, `POST /admin/paros/{id}/cerrar`, `DELETE /admin/paros/{id}`, `DELETE /admin/paros/{id}/foto` |
| Insumos (solo `ADMINBODEGA` y `SUPERADMIN`) | lectura del público `/api/insumos/dashboard`; `PUT`/`DELETE` de `/admin/pedidos/{id}` y `/admin/entregas/{id}` |
| Reportes (solo `SUPERADMIN`) | `GET`/`PUT`/`DELETE` de `/admin/reportes_app` y `/admin/comentarios` |
| Tablets (solo `SUPERADMIN`) | `GET`/`PUT`/`DELETE /admin/tablets` |
| Usuarios (solo `SUPERADMIN`) | `GET/POST /admin/usuarios`, `PUT /admin/usuarios/{id}`, `GET /admin/niveles` |
| Correo (solo `SUPERADMIN`) | `GET`/`PUT /admin/correo`, `POST /admin/correo/prueba`, `POST /admin/correo/semanal_ahora`, `GET /admin/correo/semanal_vista_previa` |

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
