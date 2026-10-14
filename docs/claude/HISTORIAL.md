# SIGEP — Historial de cambios

> Movido desde `CLAUDE.md` el 2026-10-14 para aligerar el contexto que se carga en cada
> sesión. Contenido literal, sin reescribir.

## Crónica del estado del proyecto (antes §0 de CLAUDE.md)

**Chat de bodega por conversaciones y torre de control renovada** (2026-10-13, app
posterior a la 1.7.1). La conversación pasa a ser el par (supervisor, insumista), como en
WhatsApp: cada supervisor ve solo sus hilos y el insumista tiene un chat por supervisor,
más «Supervisión» (`supervisor_id` NULL, lo ven todos: es lo que manda una tablet 1.7.1)
y los avisos generales (`insumista_id` NULL). `routers/mensajes_bodega.py` gana parámetros
**opcionales**: `GET ?supervisor_id=` (0 = Supervisión) `&difusion=`, `POST {supervisor_id}`,
`POST /leidos?insumista_id|supervisor_id|difusion`, y el resumen trae `ultimo_texto`,
`sin_leer` por hilo y `chats` para el insumista. Probado con el código viejo (8098) y el
nuevo (8099) contra `prueba_insumos_v17`: la tablet 1.7.1 del insumista recibe lo mismo.
Tag previo: `v1.20-pre-chat-conversaciones`. En la app: `ChatBodega.kt` (lista + burbujas),
barra inferior de vistas en la torre de control y tarjeta propia para el insumista. La bandeja del insumista pasa a 4 pestañas (Pedidos, Plan, Mis entregas, Mensajería). La máquina de la entrega proactiva es opcional (`maquina` NULL = sin máquina) y `GET /api/insumos/mis_entregas/{id}?desde=` junta proactivas y pedidos entregados del turno. Catálogo provisional hasta el ERP: `api_produccion/catalogo_provisional_20261013.sql`. Tag previo: `v1.21-pre-entregas-sin-maquina`.

**Módulo de insumos rehecho — v1.7.0** (2026-10-12). Tres fases cerradas y en
producción; la app está en `versionCode` 15 (1.7.1: el supervisor entra con contraseña, no PIN),
**instalada y verificada en planta el 2026-10-13**. Es la versión estable de referencia (tag `v1.7.1`).

- **El catálogo de insumos es la única fuente de verdad.** Antes vivía triplicado: la
  tabla `insumos`, una lista quemada en el modal del operario y otra distinta en el
  formulario del insumista. Añadir un insumo exigía publicar un APK.
- **`insumos.bodega` (EMPAQUE/GRANEL) decide a qué insumista le suena la alarma.** Antes
  se adivinaba buscando «granel»/«preten»/«polvo» dentro del texto que tecleaba el
  operario, así que «Lámina para polvo» se iba a Granel.
- **El BOM (`recetas_productos`) cruza las cinco dimensiones** (máquina, producto, marca,
  presentación, fragancia) con `NULL` = comodín. Antes miraba solo marca y presentación
  e ignoraba la fragancia que ya tenía en su propia columna. Si nada casa devuelve el
  catálogo completo y lo deja en el log (`BOM vacío para la sesión …`), que de paso es la
  lista de combinaciones sin recetar.
- **Login único real.** `device_id` llevaba desde la 1.5 viajando desde la app sin que el
  backend lo mirase. Ahora un segundo login en otra tablet devuelve **409** (que
  `LoginActivity` ya sabía manejar). Misma tablet = relevo; sesión de +9 h = se libera.
- **Token de sesión en `sesiones_usuario`**, no en memoria como el `_TOKENS` del admin.
  Con `X-User-Token` el servidor deduce quién pregunta en vez de creerle al rol que
  viaja en la URL. **Sin token se mantiene el comportamiento anterior** mientras queden
  tablets 1.6.0 (el log lo anota: `servida SIN token`).
- **Los avisos del pedido salen por un helper único** (`services/notificaciones_pedido.py`)
  hacia el insumista (canal de bodega) y hacia la tablet del operario (WS de CAMBIO 4).
  `/entregar` y `/confirmar_recepcion` no avisaban a nadie. El sondeo se queda de respaldo.
- **La foto de la entrega proactiva es obligatoria en el servidor**, no solo en la app.
- **Pedir un insumo entra en la cola offline** (`request_id`). Era la única escritura del
  operario que exigía red.

Migración: `api_produccion/alter_insumos_v17.sql` (idempotente, ya aplicada). Importador
del catálogo y del BOM: `api_produccion/importar_catalogo.py` + `plantillas/`. Regresión
de los avisos: `pruebas/prueba_ws_pedidos.py`.

**Pendiente:** cargar el catálogo real del ERP, y las fases 4 (planificación de
producción), 5 (supervisor en la app) y 6 (la vista `/insumos` de la web, hoy un card
vacío en `App.js`).


**Entorno de pruebas en Docker y respaldo diario en dos archivos** (2026-10-08). No toca
`api_produccion/`, la BD ni el despliegue: son archivos nuevos (`pruebas/`) y el script
de respaldo.

- **Respaldo** (`backups/backup_db.sh`, cron 02:00): la BD sale en **dos** archivos,
  `sigep_estructura_<TS>.sql.gz` (tablas sin filas) y `sigep_datos_<TS>.sql.gz` (solo
  filas), más `sigep_fotos_<TS>.tar.gz` con `static/entregas` **y** `static/paros` (antes
  solo entregas). Sin `CREATE DATABASE`/`USE`: se cargan en la base que se quiera.
- **Los volcados `produccion_detg_pre_*` de antes de cada `ALTER` ya no existían**: la
  rotación de 14 días los borraba porque su nombre casaba con el de los diarios. Las dos
  filas de «BD previa…» de la tabla de abajo apuntan a archivos que ya no están. Ahora la
  rotación solo borra `sigep_*`, así que un volcado manual con otro nombre se conserva.
- **`pruebas/instalar.sh [carpeta]`** levanta MySQL 8.0 + API + web (nginx, copia del
  sitio del 3000) con `docker compose` y carga el último par estructura/datos de
  `pruebas/datos/` (gitignorada) y las fotos si están. Volver a ejecutarlo resetea la BD.
  **Correo apagado por partida doble**: la imagen de la API no lleva `.env` ni `secrets/`
  y fija `SMTP_PASS=""`, y `neutralizar.sql` borra `config_correo.smtp_pass` y pone
  `semanal_activo=0` en la copia. FCM queda deshabilitado (sin credenciales).
- Probado en este servidor con puertos alternativos: `/maquinas`, `/operadores`,
  `/fragancias`, `kpis` y `estado_operativo` idénticos byte a byte a producción; `paros`
  solo difiere en el cronómetro de un paro abierto. **Aquí no es accesible desde fuera
  del contenedor**: `ufw` deja pasar la red de `dtlink` pero no una nueva, y el firewall
  no se tocó. Está pensado para otra máquina (WSL2 o VM).

**La velocidad estándar baja a la fila de su presentación, y «Mover» gana Aplicar y
Cancelar** (2026-08-31, tercera vuelta). Solo frontend, y **ya está en producción**
(`main.8150c86a.js`); respaldo del build anterior:
`~/respaldos_build_sigep/build_2026-08-31_163607`.

- **La meta se teclea en la propia fila.** Las casillas mín/máx, el equivalente por hora
  y los botones estaban en un bloque al final de la tarjeta: para saber a qué ritmo debe
  ir una presentación había que bajar y buscarla en otra lista. Ahora la jerarquía enseña
  de un vistazo qué hace la máquina, con qué fragancia y a qué ritmo.
- **El aviso de que la meta es una sola.** `maquina_presentacion_velocidad` va por
  (máquina, presentación) y **no** por marca ni producto, así que `2.9 LT` es **una sola
  fila en la BD** aunque salga bajo SUAVIZANTE y bajo DETERGENTE LIQUIDO: comparten el
  estado de edición y teclear en una mueve la otra. Es correcto, pero sin decirlo parece
  un fallo.
- **Los nombres accesibles llevan el contexto solo cuando el gramaje se repite.** Si no,
  «Velocidad mínima de 2.9 LT» señalaría a dos casillas distintas. Donde la presentación
  es única el nombre se queda corto, como estaba.
- **El bloque del final queda solo para presentaciones que la máquina ya no produce**,
  igual que con las marcas huérfanas: sin combinación no tienen fila donde salir y su
  rango sigue comparándose con lo que mande cualquier sesión vieja.
- **«sin configurar» pasa a decir «sin meta»**, que es el vocabulario del dashboard
  («meta 32 – 38») y cabe en una fila ya cargada.
- **«Mover» se aplica o se cancela.** Aplicaba al vuelo, y eso dejaba la fila atrapada:
  elegir el producto que ya tenía no dispara el `onChange`, así que el desplegable se
  quedaba abierto sin salida. Ahora elegir no confirma —el destino se guarda en el
  estado— y hay **Aplicar** (deshabilitado si el destino es el producto actual, con un
  `title` que dice por qué) y **Cancelar**; Escape sobre el desplegable también cierra.

**La fragancia entra en la jerarquía, y el producto deja de repetirse fila a fila**
(2026-08-31, segunda vuelta sobre la agrupación de ese mismo día). Solo frontend, y **ya
está en producción** (`main.e8116b7f.js`). Tag previo: `v1.19-pre-jerarquia-fragancia`;
respaldo del build anterior: `~/respaldos_build_sigep/build_2026-08-31_145150`.

Agrupar por producto dejó ver dos cosas que antes no se notaban: que la marca se repetía
en cada línea del grupo, y que el desplegable de producto de cada fila decía otra vez lo
que ya decía la cabecera. Y faltaba la fragancia, que se administraba en un bloque
aparte al final de la tarjeta, lejos de la jerarquía que describe.

- **La jerarquía pasa a ser producto → marca → presentación**, con la fragancia colgando
  de la marca, que es de donde cuelga en la BD. **Por marca se agrupa siempre**, también
  en las SÓLIDO; la cabecera de producto sigue apareciendo solo cuando hay más de uno.
- **La fila enseña solo el gramaje.** La marca la dice su bloque. El nombre accesible de
  la fila sí sigue trayendo la combinación entera, producto incluido: quien la escucha la
  oye suelta, y dos presentaciones iguales sonarían idénticas.
- **Aviso cuando una marca está en varios productos.** `maquina_marca_fragancias` va por
  (máquina, marca) y **no** por producto, así que el FLORAL de COMISARIATO es el mismo en
  SUAVIZANTE que en DETERGENTE LIQUIDO, y quitarlo bajo uno lo quita en todos. Sin
  decirlo, el cambio parecería local y se tocaría a ciegas. **Esta es la limitación de
  fondo**: para que un producto tenga fragancias propias haría falta cambiar la tabla, y
  eso necesita autorización.
- **El desplegable de producto sale de la fila y se pide con «Mover».** Reasignar es algo
  que se hace al clasificar, no cada día; fijo en cada fila repetía el nombre del producto
  tantas veces como presentaciones tuviera el grupo. Solo una fila puede estar abierta a
  la vez: dos desplegables en la misma tarjeta invitan a mover la que no era.
- **El bloque «Fragancias por marca» del final desaparece**, salvo para las marcas que ya
  no producen nada en esa máquina. Esas no tienen sitio en la jerarquía y sus fragancias
  siguen configuradas: si no salieran en ningún lado, quedarían activas sin que nadie
  pudiera verlas ni quitarlas.
- **Nada de esto toca `api_produccion/` ni la BD.** Se dibuja con lo que
  `/admin/maquina_productos` y `/admin/maquina_fragancias` ya devuelven. **Las tablets no
  se ven afectadas.**

**El logo original de la empresa sustituye al SVG dibujado a mano** (2026-08-31). Solo
frontend, y **ya está en producción**: `main.62e53954.js`, el bundle vigente, que trae
también la agrupación por producto. Respaldo del build anterior:
`~/respaldos_build_sigep/build_2026-08-31_142715`. El backend no se tocó.

`logo/logo_sigep.png` apareció el 2026-08-31 y con él se cierra el pendiente que estaba
abierto desde la reconstrucción: la cabecera enseñaba un SVG hecho a partir de las
capturas de `referencia_ui/`, y `public/` seguía con los iconos y el `manifest.json` de
Create React App —la pestaña del navegador llevaba el átomo de React y la app se
anunciaba como «Create React App Sample»—.

- **`ui/Logo.js` pasa a ser un `<img>`** del archivo real, servido desde `public/`. Las
  tres cabeceras que ya lo usaban (dashboard, `/admin` y el login) no cambian una línea:
  la prop `tamano` se conserva, y ahora es el **alto**, con el ancho sacado de la
  proporción del archivo — el logo es más alto que ancho y cuadrarlo lo deformaba. Se
  declara para reservar el hueco: sin ancho, la cabecera pegaba un salto al cargar.
- **El original trae 21 px de margen transparente**, así que se recortó al contenido
  antes de generar nada; si no, el logo saldría pequeño y descentrado dentro de su caja.
- **Iconos regenerados desde el mismo archivo**: `favicon.ico` con los cinco tamaños de
  16 a 64, `logo192.png` y `logo512.png` centrados en un lienzo cuadrado transparente con
  un 6 % de respiro. El favicon lleva menos margen (2 %): a 16 px lo único que sobrevive
  es la silueta y los dos colores, y cada píxel cuenta.
- **`manifest.json` e `index.html` dejan de ser la plantilla**: nombre y `short_name` de
  SIGEP, `theme_color` y `background_color` al `#0A100E` del sistema (§5) en vez del
  `#0a0f1a` y el `#ffffff` heredados, y `lang="es"`.
- **Los iconos llevan `?v=2` en `index.html`, y no es decorativo.** El favicon vive en
  una caché aparte del navegador que no se vacía ni con `Ctrl+Shift+R`: tras el
  despliegue seguía saliendo el átomo de React aunque el servidor ya mandaba el bueno
  (comprobado por md5). Cambiar la URL es lo único que lo fuerza. **Si algún día se
  cambia el logo otra vez, hay que subir ese número** o nadie verá el nuevo. Van además
  `favicon-16.png` y `favicon-32.png` sueltos, que los navegadores modernos prefieren al
  ICO y a esos tamaños salen más nítidos.
- **En el login el logo va centrado y a 72 px.** Es la única pantalla sin datos delante y
  la primera que se ve al entrar; en las cabeceras sigue a 34, que es lo que cabe en la
  barra.

**Las combinaciones de la Jerarquía se agrupan por producto** (2026-08-31). Solo
frontend, y **ya está en producción** (`main.5303e47c.js`; el backend no se tocó, worker
`3976349` sin recargar). Tag previo: `v1.18-pre-jerarquia-producto`; respaldo del build
anterior: `~/respaldos_build_sigep/build_2026-08-31_141538`.

La línea líquida rompió un supuesto que la pantalla daba por bueno: que una máquina hace
**un solo producto**. La Máquina 3 llena SUAVIZANTE y DETERGENTE LIQUIDO, y los dos
existen en `COMISARIATO · 2.9 LT` y en `ULTREX · 3 LT`. **No son la misma combinación**
—el producto las distingue— pero la fila solo pintaba marca y presentación, así que en
`/admin` → Jerarquía salían dos filas de texto idéntico y el producto quedaba escondido
en un desplegable al otro extremo de una fila ancha. Se leía «SUAVIZANTE» sin saber de
qué marca.

- **El producto pasa a mandar la estructura**, que es lo que ya era en los datos: cabecera
  por producto con su recuento, y debajo sus marcas y presentaciones. La fila sigue siendo
  la unidad que se activa, se borra y se reasigna; lo único que cambia es dónde vive.
- **Solo se agrupa cuando la máquina tiene más de un producto.** Con uno solo la cabecera
  no distinguiría nada y sería una línea repetida en cada máquina SÓLIDO, que todas hacen
  DETERGENTE EN POLVO y nada más. La condición es exactamente aquella en la que la lista
  plana miente.
- **El comodín se rotula y va el último**: «Sin producto · vale para cualquiera». Un
  producto vacío significa que la combinación vale para todos, no que falte un dato, y
  callarlo dejaba la fila con aspecto de estar a medio configurar. Va al final porque es
  lo que queda por clasificar, no una categoría más.
- **La confirmación de borrado nombra el producto, y eso era un fallo de verdad.** Decía
  «¿Eliminar COMISARIATO · 2.9 LT de Máquina 3?» con dos filas así en pantalla: no había
  forma de saber cuál se daba de baja. Lo mismo en el aviso de éxito y en el de reasignar.
- **La fila lleva su nombre accesible completo** (`aria-label` con el producto delante).
  En pantalla lo dice la cabecera del grupo, pero quien la escucha oye la fila suelta y
  dos de la misma marca y presentación sonarían idénticas.
- **La cabecera de la máquina cuenta también los productos** («8 combinaciones ·
  2 productos»), y solo cuando hay más de uno.
- **Nada de esto toca `api_produccion/` ni la BD**, ninguna respuesta de la API cambia y
  no se pidió ningún endpoint nuevo: la agrupación se hace en cliente sobre el
  `producto` que `/admin/maquina_productos` ya devuelve. **Las tablets no se ven
  afectadas.**

**Última sesión: 2026-08-21.** El frontend está reconstruido y **desplegado en producción**
(nginx, puerto 3000). Dashboard y las cinco pestañas de administración equivalentes a las
capturas de `referencia_ui/`. 236 tests en verde.

**La foto del paro no se veía, y ahora se ve** (2026-08-21). Solo frontend; **escrito y
validado, sin desplegar**. Tag previo: `v1.17-pre-visor-fotos-paro`.

El código que enseña la foto existe desde el 2026-08-19 en las dos pantallas que muestran
paros, y estaba bien escrito. Lo que estaba roto era la **URL**, y no se había notado
porque **nunca ha llegado ninguna foto** (el 2026-08-21: 0 de 157 paros, `static/paros/`
vacío), así que no había imagen con la que descubrirlo.

- **El problema.** La API manda la ruta relativa (`foto_url`:
  `/static/paros/130_447_….jpg`) y **esa ruta no carga en la web**: nginx sirve el 3000 con
  `try_files $uri $uri/ /index.html` y solo proxea `/api/`, así que `/static/…` no encuentra
  archivo y cae a la SPA. Medido con un JPG de prueba puesto y borrado en el momento: el
  **8000 responde `200 image/jpeg`** y el **3000 responde `200 text/html` de 700 bytes**.
  Es el mismo desajuste ya documentado para el APK (§1, «Distribución del APK»), y lo
  peligroso es que **no es un error**: el `<img>` recibe un 200, pinta el icono de imagen
  rota y nada en la consola dice por qué; el enlace «abrir a tamaño completo» abría la web
  otra vez.
- **La salida es de cliente, contra el 8000** (`lib/fotos.js`), porque las dos alternativas
  necesitan autorización que no se pidió: un `location /static/` en nginx (regla 5) o servir
  las fotos bajo `/api/` (regla 2). El 8000 escucha en `0.0.0.0` —es de donde las tablets
  bajan el APK— y `/static/` no pide autenticación, así que la imagen carga sin token. Se
  respeta el protocolo y el host desde el que se está viendo la web y **solo** se cambia el
  puerto; si la web se sirviera ya desde el 8000, la ruta se deja intacta.
  **Si algún día se añade el `location` a nginx, este módulo es lo único que hay que
  quitar.** Con HTTPS delante habría que hacerlo, o el navegador bloquearía la imagen por
  contenido mixto.
- **Un solo componente para las dos pantallas** (`ui/FotoEvidencia.js`), por el mismo
  motivo que `services/velocidad.py` en el backend: arreglar la URL en `/paros` y no en
  `/admin` → Paros dejaría a la mitad de la planta mirando un icono roto.
- **La foto se abre en un visor sobre la propia página**, no en otra pestaña. Antes el
  enlace era la única forma de verla a tamaño útil, y ahora que la URL apunta al 8000 eso
  saca al supervisor del centro de control y encima a otro puerto. El enlace sigue estando
  **dentro** del visor, para quien quiera pantalla completa o descargarla. Escape y el clic
  fuera cierran; el clic sobre la imagen no, que es justo donde se pincha al mirar una pieza
  rota.
- **El visor se monta en `document.body` con un portal, y eso no es opcional.** Salió de
  probarlo en planta: al abrir la foto desde «Estado de máquinas» el botón de cerrar
  quedaba fuera de la pantalla y, como el visor bloquea el scroll de la página, **no había
  forma de llegar a él**. La causa son dos cosas de `Card` a la vez: lleva
  `animate-fade-in`, cuya animación está declarada con **`both`**, así que conserva el
  `transform: translateY(0)` del último fotograma **para siempre** —y un `transform`
  convierte al elemento en el bloque contenedor de sus descendientes `fixed`—, y además
  lleva `overflow-hidden`, que recortaba lo que sobresaliera. Es el mismo transform del
  que ya avisaba el comentario del `z-40` de la barra de segmentadores en `App.js`.
  **Cuidado al meter cualquier otro elemento flotante dentro de una `Card`**: le pasará
  igual.
- **Si la imagen no carga, se dice, con la ruta delante.** Es el fallo que de verdad va a
  pasar cuando empiecen a llegar fotos: el archivo borrado a mano de `static/paros/`, o el
  8000 inalcanzable desde ese equipo. Un icono roto se lee como «la web está mal hecha» y no
  deja ninguna pista de qué mirar.
- **La máquina parada AHORA enseña la foto de su paro en su propia tarjeta** («Estado de
  máquinas»), que es donde se está mirando cuando una línea está detenida. `paro_actual` no
  trae `foto_url` y el backend **no se tocó**: se empareja `paro_actual.id` con el `paros[]`
  de la misma respuesta, en `ParosView`. Consecuencia asumida: `paro_actual` es de ahora y
  `paros[]` es del rango, así que consultando una fecha pasada la tarjeta se queda sin
  miniatura — mejor eso que enseñar la foto de otro paro.
- **La fila cerrada avisa con un icono de cámara de qué paros traen foto**, y el nombre
  accesible de la fila lo dice también. Sin eso habría que desplegar los paros uno a uno
  para descubrir cuál tiene evidencia, y lo normal es que no la tengan.
- **`rounded-sig` no existe en `tailwind.config.js`.** La miniatura del 2026-08-19 la usaba,
  así que su borde salía cuadrado; nadie lo vio porque nunca hubo foto. Se cambió por
  `rounded-xl`, que ya son los 12px del sistema (§5), y no queda ninguna en `src/`.
- **Nada de esto toca `api_produccion/` ni la BD**, y ninguna respuesta de la API cambia.
  **Las tablets no se ven afectadas.**

**Velocidad estándar por (máquina, presentación)** (2026-08-21). **Ya está en producción**
(worker `3327707`, frontend `main.de32e396.js`), y la tabla nueva
`maquina_presentacion_velocidad` **está creada** con el `.sql` a mano. Tag previo:
`v1.16-pre-estandar-velocidad`; respaldo del build anterior:
`~/respaldos_build_sigep/build_2026-08-21_152227`.

Desde el 2026-08-19 la tablet manda la velocidad, pero **el número llegaba solo**: 30
unidades/min no es rápido ni lento hasta saber qué se esperaba de **esa** máquina con
**ese** gramaje. La Máquina 7 con 1 KG puede ir a 32–38 y la Máquina 9 con el mismo 1 KG a
28–32. Eso es lo que ahora se configura desde `/admin` → **Jerarquía**, sin entrar a MySQL.

- **Granularidad (máquina, presentación), sin marca.** Es la simétrica de las fragancias,
  que van por (máquina, marca) y dejan fuera el gramaje: aquí manda el gramaje y la
  máquina que lo llena, no la etiqueta. ULTREX 1 KG y TORBELLINO 1 KG en la Máquina 7 van
  al mismo ritmo, así que colgarlo de `maquina_productos` habría multiplicado las filas a
  mantener a mano sin ganar precisión.
- **Un RANGO y no un valor único.** El ritmo real oscila con el material y el operario; un
  objetivo puntual marcaría como desviación cualquier turno normal y la señal dejaría de
  servir. Los límites aceptados son **los mismos que `POST /api/velocidad`** (0.1–1000): un
  estándar fuera de ellos no lo cumpliría ninguna lectura posible, y el error saldría al
  leer el dashboard en vez de donde se puede arreglar.
- **Se juzgan cosas distintas en cada pantalla, a propósito.** El dashboard compara la
  **última lectura** (cómo va la línea ahora) y `/admin` → Producción el **promedio** (cómo
  fue el turno entero). El emparejamiento vive en `services/velocidad.py` y no en cada
  router, para que no puedan divergir: si el criterio cambiara en un solo sitio, la misma
  sesión saldría dentro de rango en una pantalla y fuera en la otra.
- **Sin estándar no se colorea nada.** Las claves `velocidad_min`/`velocidad_max` viajan
  siempre en `null` cuando esa combinación no tiene rango, y la web enseña la lectura sin
  juzgarla. Un color ahí sería un juicio inventado, igual que un cero sería una lectura
  inventada.
- **El emparejamiento es por TEXTO** (nombre de máquina + presentación de la sesión), como
  ya hacen los filtros del dashboard: si a una máquina se le cambia el nombre desde
  `/admin`, las sesiones viejas dejan de encontrar su estándar. Se prefiere eso a
  aproximar: un rango equivocado es peor que ninguno.
- **La UI deja de decir «fundas/min» y dice «unidades/min»** — la línea líquida no llena
  fundas y el mismo número va a describir las dos líneas. El `detail` del 400 de
  `POST /api/velocidad` **no se tocó**: es respuesta de la API que consume la tablet, y la
  app además valida en local con su propio texto.
- **Dos respuestas cambian de forma** (ninguna la consume la app): `/dashboard/estado_operativo`
  y `/admin/sesiones` añaden `velocidad_min` y `velocidad_max`.
- Verificado con `diff` 8000 vs 8001 en **25 endpoints**: 22 idénticos byte a byte, y los 3
  de `estado_operativo` distintos **solo** por las dos claves nuevas, comprobado clave a
  clave. Los endpoints nuevos, probados contra la BD real **dentro de una transacción
  revertida** (15 casos: alta, 409 del duplicado, 422 de una presentación que la máquina no
  produce, 400 de mín>máx y de fuera de rango, baja lógica, reactivación con los valores
  nuevos) y **la tabla quedó en 0 filas**. Tras el HUP: `/api/maquinas`, `/api/operadores` y
  `/api/fragancias` idénticas byte a byte, y **una tablet finalizó turno contra el código
  nuevo un minuto después** (sesión 553, cierre normal sin marca de cierre automático).
  **Las tablets no necesitan actualización.**

**El APK 1.5.1 ya implementa las tres cosas de Android** (2026-08-21). Se comprobó
desensamblándolo: llama a `api/paro/estado`, `api/paro/{paro_id}/foto` con el part `foto` y
`api/velocidad` en unidades/min con el mismo rango 0.1–1000, y **encola la foto offline**
(`enqueueFotoParo`), así que una tablet sin red no la pierde. Está versionado en
`api_produccion/static/SIGEP-1.5.1.apk` y **no** publicado como `sigep_latest.apk`.
El 2026-08-21 solo la Máquina 9 había mandado velocidad (1 lectura) y **no había llegado
ninguna foto** (0 de 157 paros, `static/paros/` vacío): la web lleva desde el 2026-08-20
esperándolas, no hay nada que arreglar ahí.

**Foto del paro, velocidad de máquina y `GET /api/paro/estado`** (2026-08-19).
**Ya está en producción** (2026-08-20: worker `3196184`, frontend `main.3ec38e92.js`), y el
`ALTER` de `alter_paros_foto_velocidad.sql` **está ejecutado**. Tag previo:
`v1.15-pre-foto-velocidad`; respaldo del build anterior:
`~/respaldos_build_sigep/build_2026-08-20_115631`.

Se recargó **con 5 turnos activos**, y por eso se verificó antes lo que de verdad
importaba: que una tablet con el **APK viejo** siga funcionando igual contra el código
nuevo. Se simuló el ciclo completo —`iniciar_turno` → `registrar_pallet` (con su reintento
idempotente) → `paro/iniciar` (409 en el duplicado) → `paro/finalizar` → `heartbeat` →
`finalizar_turno`— **contra la BD real dentro de una transacción revertida**, más los
9 endpoints de lectura que usa la app comparados byte a byte. Es la prueba de que la
columna nueva en `paros_maquina` no rompe el camino de las tablets. Después del HUP:
`/api/maquinas` y `/api/operadores` idénticas byte a byte, KPIs sin moverse (1975 pacas ·
150 sacos) y los 5 turnos abiertos con su producción intacta.

**La tabla `registro_velocidad` la creó `Base.metadata.create_all()`**, no una migración a
mano: pasó al importar el modelo para una comprobación, que es exactamente lo que este
archivo ya advertía que ocurre con las tablas nuevas. La columna `foto_path` **no** se creó
sola, porque `create_all` nunca altera una tabla que ya existe — de ahí el `ALTER`.

- **Foto opcional en cada paro.** Copia el patrón que ya funciona en las entregas de
  insumo: se guarda la **ruta** (`/static/paros/...`) y nunca la imagen, para que los dumps
  de `backups/` sigan pesando kilobytes y restaurar la BD siga siendo cosa de segundos.
  JPG/PNG, máximo 5 MB. La app **ya tiene cámara** («TOMAR FOTO», `fotoUri`, `fotoPart`):
  esto es reutilizar esa pantalla, no construirla.
  `POST /api/paro/{paro_id}/foto` es **ruta nueva y aparte de `/paro/iniciar` a
  propósito**: `iniciar` recibe JSON y pasarlo a multipart rompería a todas las tablets de
  la planta. Se ve en `/paros` y en `/admin` → Paros, y desde allí se puede quitar
  (`DELETE /admin/paros/{id}/foto`), que hace falta porque la foto la toma alguien con
  prisa y borrar el paro entero para arreglarla perdería el tiempo parado.
  **Ojo:** `/static/` se sirve **sin autenticación**, igual que las fotos de entregas.
- **Velocidad de máquina, en fundas/min.** Botón nuevo en la tablet debajo de «REGISTRAR
  PALLET». Es **una fila por lectura** (`registro_velocidad`) y no una columna en la
  sesión: el ritmo cambia durante el turno y para medir eficiencia hace falta la serie, no
  el último valor. `POST /api/velocidad`, idempotente por `request_id` con el mismo
  criterio que los pallets —incluida la normalización del `""` de Gson, que en una columna
  UNIQUE colisiona y revienta en 500. Rango aceptado 0.1–1000: no es estética, es que el
  campo lo teclea alguien con guantes y un 4500 por un dedo torcido ensuciaría la media
  para siempre.
  Se lee en la tabla de líneas del dashboard (última lectura, con promedio y nº de lecturas
  en el título) y en `/admin` → Producción (promedio · lecturas). **El promedio nunca va
  solo**: una media de una sola lectura no describe un turno de ocho horas.
- **`GET /api/paro/estado?sesion_id=`** — ruta nueva, de solo lectura, para que la tablet
  deje de adivinar. Devuelve `en_paro`, `paro_abierto` y `ultimo_paro`. Es la mitad de la
  contingencia que le toca a la app: el arreglo del 404 evita el bloqueo, pero solo esto
  permite que el botón nazca correcto al arrancar. Contrato completo en
  `CAMBIO_ANDROID_paro_estado_foto_velocidad.md`.
- **Sin lectura no se inventa un cero.** `velocidad()` en `lib/format.js` devuelve «—»
  cuando no hay dato, y las cuatro claves de velocidad viajan siempre con `null`: un cero
  por defecto sería indistinguible de una máquina detenida.
- **Dos respuestas existentes cambian de forma** (no las consume ninguna tablet, solo esta
  web): `/dashboard/paros` y `/admin/paros` añaden `foto_url`; `/dashboard/estado_operativo`
  añade las cuatro claves de velocidad y `/admin/sesiones` dos. Mismo criterio con el que se
  añadió `fragancias` a `/admin/catalogos`.

**Contingencia de paros: la tablet que se queda sin poder reanudar** (2026-08-19).
**Ya está en producción** (worker `3127121`, frontend `main.d737d2e6.js`). Se recargó
aprovechando una ventana con **0 turnos activos**, y se comprobó después que
`/api/maquinas` seguía idéntica byte a byte al snapshot previo y que los KPIs (3288 pacas ·
50 sacos) no se movieron. Tag previo: `v1.14-pre-contingencia-paros`; respaldo del build
anterior: `~/respaldos_build_sigep/build_2026-08-19_165958`.

Salió de un incidente real de ese mismo día, y conviene leerlo antes de tocar esta parte:

1. 15:01:28 — la tablet de **Máquina 7** (sesión 531) abre el paro **237**
   (`[Mantenimiento] - desembobinador`).
2. 15:19:43 — el admin `jvalle` (ADMINPLANTA) lo cierra desde **/admin → Paros**
   (`POST /admin/paros/237/cerrar`). En la BD queda cerrado con 18m 16s.
3. **Nada avisa a la tablet.** Su estado de paro es un flag en memoria de `MainActivity`,
   y la app solo lo apaga dentro del callback de éxito de `/api/paro/finalizar`
   (`llamarApiFinalizarParo$aplicarReanudacion` en el dex). El botón se queda en verde.
4. Al pulsar «reanudar», `/api/paro/finalizar` no encuentra ninguna fila con `fin_paro`
   NULL y responde **404 «No hay paro activo»** → la app **no** apaga el flag → bloqueo
   permanente. La Máquina 7 estuvo **desde las 14:57 hasta las 16:20 sin registrar un
   pallet**, y a las 16:06 el operario lo reportó desde la app (reporte id 4).

Tres cosas que se aprendieron y que el arreglo da por sentadas:

- **La app no persiste el estado de paro.** Se comprobó desensamblando
  `static/sigep_latest.apk`: sus claves de preferencias son `sesion_activa`,
  `turno_maquina`, `turno_operador` y `turno_pendiente_crear` — **ninguna de paro**. Por
  eso **cerrar la app y volver a abrirla desatasca la tablet** y es la salida más rápida
  en planta; el turno se restaura de `sesion_activa`.
- **El 404 era invisible.** No dejaba ni una línea en el journal, así que nadie supo que
  una máquina llevaba una hora atascada hasta que el operario lo reportó. El camino nuevo
  loguea `warning`.
- **Reabrir un paro ya se podía** (`PUT /admin/paros/{id} {fin_paro: ""}`, vaciando el
  campo «Fin»), pero había que adivinarlo. Ahora es un botón.

Qué se cambió:

- **Backend** — `POST /api/paro/finalizar` (`routers/operaciones.py`) deja de dar 404
  cuando la sesión **sí** tuvo paros y ninguno está abierto: responde **200 con los datos
  reales del último paro** (`paro_id`, `duracion_segundos` registrada) más
  `ya_cerrado: true`. El estado que la tablet quiere conseguir —no estar en paro— ya se
  cumple, así que negarlo solo la bloquea. Cubre además la doble pulsación y el reintento
  de la cola offline cuya respuesta se perdió. **Si la sesión no tuvo ningún paro sigue
  dando 404**: un 200 ahí tendría que inventarse un `paro_id` y una duración.
  **El camino normal no cambia una línea** —lo único que el `diff` borra es el `raise` del
  404, que se movió dentro del caso nuevo— y **la clave `ya_cerrado` no se añade a la
  respuesta del camino feliz**, para que siga siendo idéntica byte a byte.
- **Frontend** — `/admin` → **Paros**: cerrar un paro **EN CURSO** y cerrar uno
  **SIN CIERRE** se parecían en la pantalla y no son lo mismo. Ahora el paro EN CURSO
  avisa en el desplegable de que hay una tablet detrás con su botón local, y su
  confirmación nombra la consecuencia concreta («le dirá que no hay paro activo») y la
  salida. Un «¿estás seguro?» genérico no habría frenado el cierre del 2026-08-19.
  Botón **«Reabrir»** de un clic en los paros cerrados, que manda `{fin_paro: ""}`.
- Verificado con `diff` 8000 vs 8001 en **24 endpoints**, todos idénticos byte a byte, y
  el caso nuevo probado **contra la sesión 531 real** (el 8000 reproduce el 404, el 8001
  responde 200 con el paro 237 y su duración de 1095.66 s). Es una llamada de solo
  lectura cuando no hay paro abierto, así que no escribió nada: se comprobó después que el
  paro 237 seguía cerrado a las 15:19:44 y los KPIs intactos. Cuerpo incompleto sigue
  dando 422. **Las tablets no necesitan actualización** — de hecho el arreglo existe
  precisamente para no depender de eso.

**Administrar desde /admin lo que antes obligaba a entrar a MySQL** (2026-08-07) **ya
está en producción** (worker `2245869`, frontend `main.022da3b5.js`), junto con el
**reparto de pestañas por nivel de acceso**. Nació de un caso real: el paro 105 se borró
por SQL sin ver de qué sesión colgaba. Cuatro bloques —**Paros**, **Insumos**,
**Reportes** y **Tablets**—, el `ALTER` de `alter_reportes_atendido.sql` y las áreas por
nivel (§3). Respaldo del build anterior:
`~/respaldos_build_sigep/build_2026-08-07_150405`. Detalle abajo.

El **panel de correo y el reporte semanal de paros** (2026-08-07) **ya están en
producción** (worker `2230646`, frontend `main.7e508521.js`). Nueva pestaña
`/admin` → **Correo** (solo SUPERADMIN) y una tabla nueva, `config_correo`. El reporte
sale **los viernes a las 12:00** con la semana cerrada de viernes a viernes. Respaldo del
build anterior: `~/respaldos_build_sigep/build_2026-08-07_114148`.

El **aviso por correo de los reportes de la app** (2026-08-07) **ya está en producción**
(backend con `kill -HUP`, worker nuevo `2227985`). **No hubo despliegue de frontend**: el
cambio es solo de API, así que el build sigue siendo `main.898b7185.js`. Detalle abajo.

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

**Ojo con la app Android — esto cambió con el APK 1.5.1:** la v1.3.1 **no leía las
fragancias del servidor** (`"Floral"` y `"Limón"` estaban como literales en el dex, y su
data class `MarcaResponse(nombre=…)` no tenía campo `fragancias`), así que configurarlas en
la web no cambiaba nada en la tablet. La **1.5.1 sí las lee** (`api/fragancias`,
`refrescarFraganciasDesdeServidor`, `cache_fragancias`), comprobado desensamblándola el
2026-08-21. **Depende por tanto de qué APK tenga cada tablet**, y el OTA sigue
deshabilitado: una tablet con la 1.3.1 seguirá ofreciendo la lista fija.

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

### Cambios de backend ya autorizados y aplicados

Tras la reconstrucción se autorizaron **catorce** excepciones a la regla de oro:

- **Velocidad estándar por (máquina, presentación)** (2026-08-21, **en producción**).
  Semántica y decisiones, arriba en «Velocidad estándar por (máquina, presentación)».

  - **BD:** una tabla nueva, `maquina_presentacion_velocidad`
    (`api_produccion/alter_velocidad_estandar.sql`), con UNIQUE de (`maquina_id`,
    `presentacion`) y baja lógica. **Ningún `ALTER`; ninguna tabla existente se tocó.**
    Como `registro_velocidad`, `create_all()` la crearía sola al importar el modelo, así
    que el `.sql` se ejecutó **antes** de levantar nada con el código nuevo: es lo que pone
    el `DEFAULT 1` en `activo` que SQLAlchemy resuelve en Python y MySQL se quedaría sin.
  - **Endpoints nuevos:** `GET /admin/velocidad_estandar` (`require_planta_lectura`),
    `POST` y `PUT /admin/velocidad_estandar/{id}` (`require_planta`). **No hay `DELETE`**:
    quitar es `PUT {activo:false}`, como en las otras dos jerarquías.
  - **Dos respuestas existentes añaden `velocidad_min`/`velocidad_max`**:
    `/dashboard/estado_operativo` y `/admin/sesiones`. Ninguna la consume la app.
  - `services/velocidad.py` es el **único** sitio donde se resuelve el emparejamiento, para
    que el dashboard y el admin no puedan contradecirse.
  - Verificado con `diff` 8000 vs 8001 en **25 endpoints** (22 idénticos byte a byte) y los
    nuevos contra la BD real en una transacción revertida. **Las tablets no necesitan
    actualización.**

- **Contingencia de paros** (2026-08-19, **escrito y validado, sin desplegar**).
  `POST /api/paro/finalizar` tolera que el paro ya estuviera cerrado. Detalle arriba, en
  «Contingencia de paros: la tablet que se queda sin poder reanudar».

- **Administración de paros, insumos, reportes y tablets, y reparto por áreas**
  (2026-08-07, **en producción**).
  Todo lo que hasta ahora obligaba a abrir MySQL a mano.

  - **Paros** — `GET/PUT /admin/paros`, `POST /admin/paros/{id}/cerrar` (operativo) y
    `DELETE` (SUPERADMIN). El rango usa el mismo criterio que `/dashboard/paros`. El
    `motivo` se acepta **por partes** (`categoria` + `comentario`) y se recompone como
    `"[CAT] - comentario"`: escribir los corchetes a mano es lo que se olvida y rompe el
    desglose. **La duración se recalcula** al mover las horas —dejar la vieja daría un dato
    que nadie sabría de dónde salió— y los tiempos **se validan antes de asignarse**.
    `fin_paro: ""` reabre un paro cerrado por error. `POST /cerrar` tapa el agujero del
    recolector, que cierra turnos colgados pero no sus paros.
  - **Insumos** — **solo frontend**: `PUT`/`DELETE` de `/admin/pedidos` y `/admin/entregas`
    existían desde antes y nunca tuvieron pantalla. Se editan **solo cantidades**: las horas
    las escribe el flujo de bodega y tocarlas daría tiempos de respuesta imposibles. La
    cantidad **solicitada** tampoco se toca: es lo que pidió el operario, no lo que pasó
    después. Se lee del endpoint **público** `/api/insumos/dashboard`, el único que arma
    pedidos y entregas con máquina y tiempos.
  - **Reportes de la app y comentarios de turno** — `GET/PUT/DELETE` de
    `/admin/reportes_app` y `/admin/comentarios`. Los reportes **se atienden, no se
    borran** (`atendido`, `atendido_en`, `atendido_por`): el historial de qué falló en las
    tablets es justo lo que hoy se pierde en el buzón de correo; desmarcar limpia las dos
    marcas. **Sin rango de fechas por defecto**, al revés que el resto del admin: son
    esporádicos y la pantalla saldría vacía casi siempre.
  - **Tablets** — `GET/PUT /admin/tablets`, `DELETE` (SUPERADMIN). El 2026-08-07 había 24
    registradas y **17 sin señales en más de un día**. Dos avisos que da la pantalla:
    «conectada» es **WebSocket abierto ahora** (los latidos llegan cada 20-25 min), y
    **borrar no es permanente si el equipo sigue vivo** — el siguiente heartbeat recrea la
    fila, porque es la propia tablet quien la escribe.
  - **`ALTER` aplicado** (`alter_reportes_atendido.sql`): tres columnas nuevas en
    `reportes_app`. Son columnas sobre una tabla que ya existía, así que `create_all` **no**
    las crea. **Ya está ejecutado en producción**, y es aditivo: el código vigente no las
    usa. Respaldo previo:
    `backups/produccion_detg_pre_reportes_atendido_20260807_124057.sql.gz`.
  - **Áreas por nivel** (tabla en §3): `require_operativo` desaparece y cada endpoint pasa
    a `require_planta`, `require_planta_lectura`, `require_bodega` o `require_superadmin`.
    Verificado con una matriz de los **57 endpoints × 5 niveles**: SUPERADMIN 57,
    ADMINPLANTA y ADMIN 30, CONSULTA 11 (solo `GET`), ADMINBODEGA 4.
  - Verificado con `diff` 8000 vs 8001 en **23 endpoints**, idénticos byte a byte, y cada
    endpoint nuevo probado contra la BD real **dentro de una transacción revertida**.
    **Las tablets no necesitan actualización.**


- **Panel de correo + reporte semanal de paros** (2026-08-07, **en producción**).

  - **BD:** una tabla nueva, `config_correo`, de **fila única** (`id=1`). La crea
    `Base.metadata.create_all()` al arrancar, así que no hizo falta migración. **Ningún
    `ALTER`; ninguna tabla existente se tocó.**
  - **La regla que hay que entender antes de tocar nada:** para cada campo manda el valor
    de la BD si está puesto, y si no, el del `.env`. Una lista vacía **no** es «sin
    destinatarios», es «usa la de siempre». Gracias a eso la tabla pudo crearse vacía y el
    correo siguió comportándose exactamente igual que antes. La pantalla enseña el origen
    de cada lista (*configurado aquí* / *del servidor (.env)* / *heredado*) para que nadie
    edite el `.env` creyendo que sigue mandando.
  - **Ventana del reporte: viernes 12:00 → viernes 12:00**, siete días completos. Un paro
    cuenta **entero si su inicio cae dentro**, aunque termine después del corte: es el
    criterio de `GET /dashboard/paros`.
  - **El total NO cuadra con `/paros`, a propósito** (2026-08-07): `CATEGORIAS_EXCLUIDAS`
    deja fuera **ALMUERZO** —parada prevista de la jornada, no una incidencia— y la vista
    sí lo cuenta. El correo lo dice en dos sitios, y **lo dice aunque sea cero**: un total
    más bajo sin explicación se lee como un fallo. La exclusión se aplica **también a la
    semana anterior**, o la variación sería inventada.
  - **La duración de un paro abierto se acota al corte de la ventana** (2026-08-07). Contra
    el reloj es lo correcto en `/paros`, que es una vista en vivo, pero aquí metería tiempo
    posterior al periodo: se nota con «Enviar ahora» un miércoles, o si el servidor arranca
    tarde y el reporte sale con retraso. El estado y la duración salen de
    `_estado_paro`/`_dur_segundos` de `routers/dashboard.py`, no de una copia: son las que
    saben acotar al fin del turno un paro «SIN CIERRE».
  - **El programador vive en `tasks.py`**, como el recolector de turnos, y **comprueba el
    reloj cada 10 minutos** en vez de dormir una semana: el servicio se recarga en cada
    despliegue y un temporizador en memoria no llegaría nunca al viernes. Guarda la
    **ventana** enviada (`semanal_ultima_ventana`), no la fecha del envío, así que no puede
    mandar dos veces la misma semana ni perderla si el servidor estuvo apagado al mediodía
    — saldría en cuanto vuelva. La marca se escribe **aunque el envío falle**: si no, con
    un SMTP roto se reintentaría cada 10 minutos toda la semana.
  - **La contraseña SMTP nunca sale por la API.** La respuesta dice si existe y de dónde
    sale, jamás su valor; al guardar, un campo vacío significa «no la toques» (mandarla
    vacía borraría la autenticación al editar un destinatario). Se guarda en claro porque
    SMTP la necesita así — es la razón de que la pantalla sea solo SUPERADMIN.
  - **Endpoints nuevos**, todos `require_superadmin`: `GET`/`PUT /admin/correo`,
    `POST /admin/correo/prueba`, `POST /admin/correo/semanal_ahora` y
    `GET /admin/correo/semanal_vista_previa`. `semanal_ahora` **no mueve la marca**: sirve
    para reenviar o revisar, y el envío del viernes sigue su curso.
  - `email_service` pasa a resolver la configuración **en cada envío** en vez de leerla al
    importar el módulo: un cambio en la web tiene efecto en el siguiente correo, sin
    recargar el servicio.
  - **Ojo con la instancia paralela del 8001**: además del recolector, arranca **también
    este programador** contra la misma BD. Si se deja levantada un viernes al mediodía,
    será ella quien mande el reporte oficial y marque la semana como enviada.


- **Aviso por correo de los reportes de la app** (2026-08-07, **en producción**). El botón de «reportar problema» de las tablets escribía en
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
  - **Ojo con el `.env` y los `HUP`**: `sigep.service` lo carga con `EnvironmentFile`, que
    systemd solo lee **al arrancar el servicio**, así que una variable añadida al `.env` no
    llega a los workers de un `kill -HUP` por esa vía. Aquí funcionó porque
    `email_service.py` llama a `load_dotenv()` al importarse y el `WorkingDirectory` del
    unit es `api_produccion/`; se comprobó antes de recargar, reproduciendo el entorno
    exacto del master. Para una variable que se consulte con `os.getenv` fuera de ese
    módulo haría falta `systemctl restart`.


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
