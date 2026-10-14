# SIGEP — Frontend en detalle

> Movido desde `CLAUDE.md` el 2026-10-14 para aligerar el contexto que se carga en cada
> sesión. Contenido literal, sin reescribir.

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

**La tarjeta de cada máquina es producto → marca → presentación, con la fragancia en su
marca** (2026-08-31). La cabecera de **producto** solo aparece cuando la máquina tiene más
de uno —con uno solo no separa nada y sería ruido en cada SÓLIDO—, pero por **marca** se
agrupa siempre, porque es de quien cuelgan las fragancias. La fila enseña solo el gramaje;
su nombre accesible sí trae la combinación entera. El grupo del comodín va el último y se
rotula «Sin producto · vale para cualquiera», porque un producto vacío es una decisión
(vale para todos) y no un hueco.

Tres detalles que no son evidentes:

- **Una marca en varios productos comparte fragancias, y la pantalla lo dice.** El dato va
  por (máquina, marca), así que quitar una bajo SUAVIZANTE la quita también en DETERGENTE
  LIQUIDO. Es la limitación de la tabla, no de la vista.
- **Reasignar el producto es «Mover»**, no un desplegable fijo por fila: repetía en cada
  línea lo que ya dice la cabecera del grupo. Solo una fila puede estar abierta a la vez,
  y elegir en el desplegable **no** aplica: hay **Aplicar** y **Cancelar**. Aplicar al
  vuelo dejaba la fila atrapada, porque elegir el producto que ya tenía no dispara el
  `onChange` y no había forma de cerrarla.
- **La velocidad estándar se teclea en la fila de su presentación**, pero es de la
  presentación y no de la combinación: el mismo gramaje en otra marca o en otro producto
  comparte esa única fila de la BD, y la pantalla lo avisa («misma meta en …»). El bloque
  del final solo conserva las presentaciones que la máquina ya no produce.
- **Las confirmaciones y los avisos nombran el producto.** Con dos combinaciones que solo
  se diferencian en él, un mensaje con la marca y la presentación no dice cuál se toca.

**La pestaña Jerarquía administra tres jerarquías con granularidad distinta**, y no es un
descuido: `maquina_productos` va por (máquina, marca, presentación),
`maquina_marca_fragancias` por (máquina, marca) —la fragancia no depende del gramaje— y
`maquina_presentacion_velocidad` por (máquina, presentación), que es la simétrica: el ritmo
depende del gramaje y de la máquina, no de la etiqueta. En
la tarjeta de cada máquina, el bloque «Fragancias por marca» solo ofrece las marcas que
esa máquina produce, las activas se muestran como chips ámbar con «×» y las quitadas
siguen visibles tachadas para poder reactivarlas. Una marca **sin ninguna fragancia
activa** se rotula «sin configurar · se ofrecen todas», porque el endpoint cae al catálogo
completo en ese caso y el hueco se leería, si no, como «esta marca no lleva fragancia».

El bloque **«Velocidad estándar por presentación · unidades/min»** (2026-08-21) es una fila
por presentación con dos casillas, mínimo y máximo, precargadas con lo guardado para
corregir encima. Detalles que no son evidentes:

- **Se teclea el minuto y se muestra la hora calculada** (`≈ 1.920 – 2.280 /hora`), nunca
  al revés: en planta se habla del objetivo por hora, pero lo que manda la tablet son
  unidades por minuto y es contra eso contra lo que se compara.
- Una casilla vacía **no** es un cero. `Number('')` es 0, así que el hueco se descarta
  antes de convertir; si no, una presentación sin configurar mostraría «≈ 0 – 0 /hora»
  como si tuviera rango puesto. Lo mismo en `rangoVelocidad`/`estadoVelocidad`.
- **«Quitar» es `PUT {activo:false}`**, y su confirmación dice qué se pierde y qué no: las
  lecturas se siguen registrando, lo que se apaga es la comparación.
- La velocidad estándar **no viaja a las tablets**: la app manda su lectura y esta web la
  interpreta. Cambiar un rango no cambia nada de lo que ve el operario, al revés que el
  resto de esta pestaña. La cabecera lo dice.

### Tests

```bash
cd frontend_sigep && CI=true npx react-scripts test --watchAll=false
npx eslint --ext .js src/
```

- `src/lib/format.test.js` — formato es-EC, duraciones, turnos, antigüedad.
- `src/lib/fotos.test.js` — la URL de las fotos: que una ruta de `/static` sale resuelta
  **al 8000** y no relativa (por el 3000 la ruta relativa devuelve la SPA con un 200, así
  que el fallo sería silencioso), que sin foto devuelve `null` y nunca `''` —un
  `src=""` recarga la propia página—, que una URL ya absoluta se respeta, que se conserva
  el host y el protocolo desde el que se ve la web y solo cambia el puerto, y que si la
  web ya se sirve desde el 8000 la ruta se deja intacta.
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
  Del reparto por áreas (2026-08-07): que un SUPERADMIN ve las once pestañas, un
  `ADMINPLANTA` y un `ADMIN` solo las seis de planta, un `ADMINBODEGA` solo Insumos —y
  que esa es la que se abre, porque la inicial del contenedor es «operarios», que no
  ve—, un `CONSULTA` las seis sin controles de escritura, y que un nivel desconocido lo
  dice en vez de romperse al buscar el componente. Las pestañas del admin son el
  **primer** `tablist` de la página: los componentes de dentro traen los suyos.
  De la velocidad y la foto (2026-08-19): que la tabla de líneas enseña la última lectura
  y que una sesión **sin ninguna** sale «—» con un título que lo explica, **nunca un cero**
  —sería indistinguible de una máquina detenida—, que el promedio viaja pegado al nº de
  lecturas, que un paro con foto la enseña mientras uno sin
  foto no deja hueco, que «Quitar foto» solo existe si hay foto y va a `/paros/{id}/foto` y
  **no** al paro —borrar el paro para arreglar una foto movida perdería el tiempo parado—, y
  que en Producción la velocidad media solo se pinta cuando hay lecturas, porque las
  sesiones anteriores al cambio nunca las tendrán. En `format.test.js`, que `velocidad(0)`
  sí enseña el cero: un cero registrado de verdad es un dato.
  Del estándar de velocidad (2026-08-21): que la lectura se compara con la meta de su
  (máquina, presentación) y que **una sesión con lectura pero sin estándar no se colorea**
  —el fixture de `estado_operativo` trae las tres filas por esto: con lectura y meta, sin
  lectura, y con lectura sin meta—, que en Producción se juzga el **promedio** y no la
  última, que el bloque de Jerarquía va por presentación y no por marca, que el alta manda
  `{maquina_id, presentacion, vel_min, vel_max}` y la corrección `PUT` **con `activo:true`**
  (guardar en una dada de baja es decir que vuelve a aplicar), que quitar es
  `PUT {activo:false}` y nunca `DELETE`, y que un rango imposible (mín > máx) **no sale de
  la web**. En `format.test.js`, los tres estados de `estadoVelocidad` con los extremos
  contando como **dentro**, y que ni `rangoVelocidad` ni `estadoVelocidad` concluyen nada
  con un hueco: `Number(null)` y `Number('')` son 0, y un cero colado ahí marcaría toda la
  planta en rojo.
  Del visor de fotos (2026-08-21): que el visor se monta **fuera** de la tarjeta —su padre
  es el `body`— y que desde ahí se puede cerrar; sin el portal ese test falla, que es el
  bug reportado en planta. Que la foto se pide **al 8000** y **no** con la ruta
  relativa —el test comprueba las dos cosas, porque con la relativa el 3000 devuelve la
  SPA con un 200 y no habría ni error de red—, que la miniatura abre un visor sobre la
  propia vista y que en la fila **no** queda enlace a otra pestaña (el enlace vive dentro
  del visor), que Escape lo cierra, que un `onError` de la imagen enseña el aviso **con la
  ruta** y retira la miniatura en vez de dejar el icono roto, que un paro sin foto no deja
  hueco ni rótulo, que la fila cerrada dice «con foto» en su nombre accesible solo cuando
  la trae, y que la tarjeta de la máquina **parada ahora** enseña la foto de su paro en
  curso emparejando `paro_actual.id` con `paros[]`. El fixture da foto al paro EN CURSO
  (108) y deja sin foto el SIN CIERRE (105) por esto. En el admin, que es el **mismo**
  componente y la misma URL resuelta.
  De la velocidad en la fila y del «Mover» confirmable (2026-08-31): que ya **no** existe
  el bloque «Velocidad estándar por presentación» y que las casillas están dentro de la
  fila de su gramaje, que dos combinaciones del mismo gramaje avisan de que la meta es
  una sola, que una presentación que la máquina ya no produce baja al bloque de sueltas
  conservando su rango, que elegir en el desplegable de «Mover» **no** manda nada hasta
  pulsar «Aplicar», que «Cancelar» cierra sin tocar nada, y que «Aplicar» nace
  deshabilitado cuando el destino es el producto que la fila ya tiene.
  De la fragancia en la jerarquía (2026-08-31): que el chip de una marca se pinta en
  **cada** producto donde esa marca aparece (COMISARIATO sale en los tres grupos de la
  Máquina 3, y su FLORAL con ella), que una marca presente en varios productos avisa de
  que la fragancia es la misma, que la fila **no** lleva desplegable de producto hasta
  pulsar «Mover» y que entonces el `PUT` va al `id` correcto, y que una marca sin ninguna
  combinación conserva sus fragancias en el bloque de abajo sin colarse como marca de la
  jerarquía. El fixture `FRAGANCIAS_M3` incluye HIT justo para ese último caso.
  De la agrupación por producto (2026-08-31): que dos productos en la misma máquina
  producen cabeceras de grupo —buscadas por encabezado y no por texto, porque
  «SUAVIZANTE» aparece también en cada desplegable, que es justo el sitio donde no se
  veía—, que el recuento de la máquina cuenta productos además de combinaciones, que el
  grupo sin producto se rotula y sale **el último**, que una máquina de un solo producto
  **no gana ninguna cabecera**, y que con dos filas de la misma marca y presentación la
  confirmación de borrado nombra el producto y el `PUT` va al `id` correcto. El fixture
  `MAQUINA_3` reproduce la máquina real, con `COMISARIATO · 2.9 LT` y `ULTREX · 3 LT`
  repetidos con productos distintos, y en el orden en que los manda el backend (por marca
  y presentación, sin mirar el producto).
  De la contingencia de paros (2026-08-19): que un paro **EN CURSO** avisa de que hay una
  tablet detrás con su botón local, que su confirmación de cierre nombra la consecuencia
  concreta —el «no hay paro activo» que verá el operario— y la salida, mientras la de un
  **SIN CIERRE** ni menciona tablets porque ahí ya no hay ninguna, que «Reabrir» manda
  `{fin_paro: ''}` —**cadena vacía, no la clave ausente**: el backend distingue «déjalo
  abierto» de «no toques el fin»—, que «Reabrir» y «Cerrar ahora» nunca salen a la vez, y
  que un `CONSULTA` no puede reabrir. El fixture `PAROS` incluye un paro EN CURSO por
  esto.
  De la pestaña Correo (2026-08-07): que solo existe para un `SUPERADMIN`, que la
  contraseña **no viaja al guardar si no se escribió** (mandarla vacía la borraría), que
  el puerto viaja como número y no como el texto del input, que añadir un destinatario
  manda la lista completa y rechaza lo que no parece un correo, que quitar el último
  avisa de que se vuelve al `.env`, y que cada tarjeta prueba **su** lista.
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
