-- Módulo de insumos — esquema de la v1.7.0 (2026-10-12).
--
-- Hoy el catálogo de insumos está TRIPLICADO y ninguna copia coincide con las otras:
--   1. la tabla `insumos` (6 filas de ejemplo, sin uso real),
--   2. una lista quemada en el modal del operario (MainActivity.kt:2443),
--   3. otra lista distinta quemada en el formulario de entrega proactiva del
--      insumista (BandejaInsumosActivity.kt).
-- Añadir un insumo exige hoy publicar un APK nuevo, que es justo lo que la tabla
-- `productos` vino a resolver en la 1.6.0: las tablets se actualizan a mano por
-- sideload y un catálogo no puede depender de eso.
--
-- Y el enrutado Empaque/Granel se decide buscando las palabras "granel", "preten" o
-- "polvo" DENTRO del texto libre que escribe el operario (routers/insumos.py:36). Un
-- pedido de "Lámina para polvo" se le va hoy al insumista de Granel.
--
-- Esta migración deja el catálogo como única fuente de verdad y mueve esa decisión a
-- un dato (`insumos.bodega`).
--
--   mysql -uadmin -p produccion_detg < alter_insumos_v17.sql
--
-- APLICADA EN PRODUCCIÓN el 2026-10-12 16:22 (America/Guayaquil). Volcado previo en
-- `backups/produccion_detg_pre_insumos_v17_20261012_162228.sql.gz` — con ese nombre, y
-- no `sigep_*`, para que la rotación de 14 días no se lo lleve. Tras aplicarla: las 756
-- sesiones, 4130 pacas, 4 pedidos y 1 entrega siguen intactos, las 4 tablas nuevas y
-- las 5 claves foráneas creadas, y los endpoints en vivo responden 200.
--
-- IDEMPOTENTE: se puede volver a ejecutar sin romper nada. Los ADD COLUMN pasan por
-- `add_col_si_falta` y los índices por `add_idx_si_falta`, que consultan
-- INFORMATION_SCHEMA antes de tocar la tabla — hace falta porque MySQL 8 no tiene
-- `ADD COLUMN IF NOT EXISTS`, y esta migración también se va a correr sobre la copia
-- de `pruebas/`.
--
-- NINGUNA columna existente cambia de tipo ni se borra. Todo lo que se añade es NULL
-- o trae DEFAULT, así que las tablets anteriores a la 1.7.0 siguen escribiendo igual.

-- ── Utilidades: añadir columna / índice solo si faltan ────────────────────────────
DROP PROCEDURE IF EXISTS add_col_si_falta;
DROP PROCEDURE IF EXISTS add_idx_si_falta;
DELIMITER $$

CREATE PROCEDURE add_col_si_falta(IN p_tabla VARCHAR(64), IN p_col VARCHAR(64), IN p_def TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_tabla AND COLUMN_NAME = p_col
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_tabla, '` ADD COLUMN `', p_col, '` ', p_def);
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END$$

CREATE PROCEDURE add_idx_si_falta(IN p_tabla VARCHAR(64), IN p_idx VARCHAR(64), IN p_def TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_tabla AND INDEX_NAME = p_idx
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_tabla, '` ADD ', p_def);
    PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END$$

DELIMITER ;


-- ══════════════════════════════════════════════════════════════════════════════════
-- 1. `insumos` — el catálogo pasa a ser la fuente de verdad
-- ══════════════════════════════════════════════════════════════════════════════════
--
-- `bodega` es la columna que mata la heurística de texto. Va VARCHAR(20) y no ENUM por
-- consistencia con el resto del esquema (`pedidos_bodega.categoria` es VARCHAR(50)) y
-- porque los modelos SQLAlchemy usan String: un ENUM obligaría a migrar la tabla entera
-- cada vez que aparezca una bodega nueva. El valor lo validan el importador y el CRUD.
--
-- DEFAULT 'EMPAQUE' y no NULL: las 6 filas que ya existen son todas de empaque, y un
-- insumo sin bodega no es un insumo a medio configurar, es un pedido que no le llega a
-- nadie. Mejor que caiga en la bodega mayoritaria y se corrija, a que se pierda.
--
-- `codigo_erp` porque el catálogo real se descarga del ERP: sin la clave de origen, la
-- segunda carga no sabe si una fila es nueva o es la misma con el nombre retocado.
CALL add_col_si_falta('insumos', 'codigo_erp', "VARCHAR(50) NULL AFTER id");
CALL add_col_si_falta('insumos', 'bodega',     "VARCHAR(20) NOT NULL DEFAULT 'EMPAQUE' AFTER categoria");

-- `activo` nació NULL-able y las filas viejas podrían tener NULL, que el código lee como
-- "dado de baja" sin que nadie lo haya dado de baja. Se normaliza antes de fijar el NOT NULL.
UPDATE insumos SET activo = 1 WHERE activo IS NULL;
ALTER TABLE insumos MODIFY COLUMN activo TINYINT(1) NOT NULL DEFAULT 1;

-- El nombre ya es UNIQUE. El código del ERP también debe serlo, pero admitiendo varios
-- NULL (MySQL no cuenta los NULL en un índice único), porque un insumo creado a mano
-- desde el admin no tiene código.
CALL add_idx_si_falta('insumos', 'uq_insumos_codigo_erp', 'UNIQUE KEY uq_insumos_codigo_erp (codigo_erp)');
CALL add_idx_si_falta('insumos', 'ix_insumos_bodega',     'KEY ix_insumos_bodega (bodega, activo)');


-- ══════════════════════════════════════════════════════════════════════════════════
-- 2. `recetas_productos` — el BOM gana comodines
-- ══════════════════════════════════════════════════════════════════════════════════
--
-- Con 7 productos × 11 marcas × 36 presentaciones × 9 fragancias, una fila por
-- combinación son decenas de miles de filas que alguien tendría que mantener a mano.
-- NULL = COMODÍN, el mismo criterio que ya usa `maquina_productos.producto` (ver
-- CLAUDE.md §4): la fila vale para cualquier valor de esa columna.
--
-- `maquina` entra porque hay diferencias que no se deducen del producto: la Máquina 7B
-- usa sacos tejidos donde las demás usan lámina en rollo, y hoy eso es un `if` quemado
-- en el APK contra el literal "Máquina 7B".
--
-- `producto` entra porque la 1.6.0 metió el producto en la jerarquía del turno y el BOM
-- se quedó atrás: hoy cruza solo marca+presentación e IGNORA la fragancia que ya tiene
-- en su propia columna (routers/operaciones.py:242).
--
-- `cantidad_sugerida` es con lo que se precarga el pedido del operario. Un entero basta:
-- se piden millares de fundas o unidades de caja, nunca fracciones.
CALL add_col_si_falta('recetas_productos', 'maquina',           "VARCHAR(100) NULL AFTER id");
CALL add_col_si_falta('recetas_productos', 'producto',          "VARCHAR(100) NULL AFTER maquina");
CALL add_col_si_falta('recetas_productos', 'cantidad_sugerida', "INT NOT NULL DEFAULT 1");
CALL add_col_si_falta('recetas_productos', 'activo',            "TINYINT(1) NOT NULL DEFAULT 1");

-- El UNIQUE atrapa la duplicación con valores concretos. NO atrapa dos comodines
-- idénticos, porque MySQL deja repetir filas que llevan NULL en una columna del índice;
-- de eso se encarga el importador, que compara tratando NULL como un valor más.
CALL add_idx_si_falta('recetas_productos', 'uq_receta_combinacion',
     'UNIQUE KEY uq_receta_combinacion (maquina, producto, marca, presentacion, fragancia, insumo_id)');
CALL add_idx_si_falta('recetas_productos', 'ix_receta_insumo', 'KEY ix_receta_insumo (insumo_id)');


-- ══════════════════════════════════════════════════════════════════════════════════
-- 3. `pedidos_bodega` — el pedido deja de ser texto libre
-- ══════════════════════════════════════════════════════════════════════════════════
--
-- `detalle_pedido` NO se toca ni se borra: es contrato congelado con la app y es lo
-- único que hace legible el histórico. Lo que se añade es la referencia dura al
-- catálogo, y el servidor seguirá escribiendo `detalle_pedido` a partir de ella.
--
-- `request_id` es lo que mete por fin este flujo en la cola offline (CLAUDE.md §5). Hoy
-- pedir un insumo es la ÚNICA operación del operario que exige red: el botón se bloquea
-- sin conexión. UNIQUE porque es la idempotencia: un reintento no puede crear dos pedidos.
--
-- `confirmado_por_tipo` / `confirmado_por_id` responden a una pregunta que hoy no se
-- puede contestar: ¿quién dio por recibido el insumo? Si el supervisor confirma porque
-- el operario no estaba en la máquina, eso tiene que quedar escrito y no pasar por una
-- confirmación del operario que nunca ocurrió.
CALL add_col_si_falta('pedidos_bodega', 'request_id',          "VARCHAR(64) NULL AFTER id");
CALL add_col_si_falta('pedidos_bodega', 'insumo_id',           "INT NULL AFTER detalle_pedido");
CALL add_col_si_falta('pedidos_bodega', 'unidad',              "VARCHAR(20) NULL AFTER cantidad_solicitada");
CALL add_col_si_falta('pedidos_bodega', 'plan_id',             "INT NULL");
CALL add_col_si_falta('pedidos_bodega', 'confirmado_por_tipo', "VARCHAR(20) NULL");
CALL add_col_si_falta('pedidos_bodega', 'confirmado_por_id',   "INT NULL");

CALL add_idx_si_falta('pedidos_bodega', 'uq_pedidos_request_id', 'UNIQUE KEY uq_pedidos_request_id (request_id)');
CALL add_idx_si_falta('pedidos_bodega', 'ix_pedidos_insumo',     'KEY ix_pedidos_insumo (insumo_id)');
CALL add_idx_si_falta('pedidos_bodega', 'ix_pedidos_plan',       'KEY ix_pedidos_plan (plan_id)');


-- ══════════════════════════════════════════════════════════════════════════════════
-- 4. `entregas_proactivas` — se atan al turno y al plan, y las verifica el supervisor
-- ══════════════════════════════════════════════════════════════════════════════════
--
-- Hoy una entrega proactiva solo guarda el NOMBRE de la máquina: el operario de esa
-- máquina ni se entera de que le dejaron material, y nadie acusa recibo. `session_id`
-- la ata al turno que estaba corriendo y `plan_id` a la producción planificada, que es
-- el caso que no tiene máquina todavía ("se deja para que luego alguna la tome").
--
-- `estado` + `verificado_por` + `fecha_verificacion`: el supervisor confirma que lo
-- entregado está donde dice la foto. 'Registrada' es el estado de todo lo que ya existe.
CALL add_col_si_falta('entregas_proactivas', 'request_id',         "VARCHAR(64) NULL AFTER id");
CALL add_col_si_falta('entregas_proactivas', 'insumo_id',          "INT NULL AFTER insumo");
CALL add_col_si_falta('entregas_proactivas', 'session_id',         "INT NULL");
CALL add_col_si_falta('entregas_proactivas', 'plan_id',            "INT NULL");
CALL add_col_si_falta('entregas_proactivas', 'estado',             "VARCHAR(20) NOT NULL DEFAULT 'Registrada'");
CALL add_col_si_falta('entregas_proactivas', 'verificado_por',     "INT NULL");
CALL add_col_si_falta('entregas_proactivas', 'fecha_verificacion', "DATETIME NULL");

CALL add_idx_si_falta('entregas_proactivas', 'uq_entregas_request_id', 'UNIQUE KEY uq_entregas_request_id (request_id)');
CALL add_idx_si_falta('entregas_proactivas', 'ix_entregas_insumo',     'KEY ix_entregas_insumo (insumo_id)');
CALL add_idx_si_falta('entregas_proactivas', 'ix_entregas_plan',       'KEY ix_entregas_plan (plan_id)');
CALL add_idx_si_falta('entregas_proactivas', 'ix_entregas_sesion',     'KEY ix_entregas_sesion (session_id)');


-- ══════════════════════════════════════════════════════════════════════════════════
-- 5. `planes_produccion` — lo que producción planifica cada día
-- ══════════════════════════════════════════════════════════════════════════════════
--
-- `maquina` es NULL-able A PROPÓSITO, y es el punto del que nace toda la fase: hay
-- producción planificada que todavía no tiene máquina asignada, y el insumista deja el
-- material para que después cualquier máquina tome lo que necesite. Obligar a elegir
-- máquina aquí mataría justo ese caso.
--
-- producto/marca/presentacion/fragancia se guardan como TEXTO y no como FK a los
-- catálogos, igual que hace `sesiones_trabajo`: un plan es una foto de lo que se iba a
-- hacer ese día y no puede cambiar de contenido porque alguien renombre una marca.
CREATE TABLE IF NOT EXISTS planes_produccion (
  id                   INT NOT NULL AUTO_INCREMENT,
  fecha                DATE NOT NULL,
  turno                VARCHAR(20) NULL,
  maquina              VARCHAR(100) NULL,
  producto             VARCHAR(100) NULL,
  marca                VARCHAR(100) NULL,
  presentacion         VARCHAR(100) NULL,
  fragancia            VARCHAR(100) NULL,
  cantidad_planificada INT NULL,
  unidad               VARCHAR(20) NULL,
  estado               VARCHAR(20) NOT NULL DEFAULT 'Planificado',
  observaciones        VARCHAR(500) NULL,
  creado_por           VARCHAR(100) NULL,
  creado_en            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_planes_fecha (fecha, estado)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ══════════════════════════════════════════════════════════════════════════════════
-- 6. `plan_insumos` — qué necesita cada plan
-- ══════════════════════════════════════════════════════════════════════════════════
--
-- Se genera explotando el BOM al crear el plan, y queda editable: el BOM dice qué lleva
-- el producto, pero la cantidad del día la ajusta quien planifica.
--
-- NO lleva `cantidad_entregada`: se calcula sumando las entregas de ese plan. Guardarla
-- sería un contador que hay que mantener sincronizado a mano y que se desfasa en cuanto
-- el admin corrija una cantidad desde la web — que es exactamente lo que `TabInsumos`
-- permite hacer hoy.
--
-- `prioridad` es la palanca del supervisor: marcar un insumo como urgente cuando el plan
-- está por arrancar y ese material no ha llegado.
--
-- ON DELETE CASCADE sobre el plan: una lista de insumos de un plan borrado no significa
-- nada. Hacia `insumos` NO hay cascada, porque los insumos se dan de baja con `activo`.
CREATE TABLE IF NOT EXISTS plan_insumos (
  id                 INT NOT NULL AUTO_INCREMENT,
  plan_id            INT NOT NULL,
  insumo_id          INT NOT NULL,
  cantidad_requerida INT NOT NULL DEFAULT 0,
  prioridad          VARCHAR(20) NOT NULL DEFAULT 'Normal',
  observaciones      VARCHAR(500) NULL,
  creado_en          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_plan_insumo (plan_id, insumo_id),
  KEY ix_plan_insumos_insumo (insumo_id),
  CONSTRAINT fk_plan_insumos_plan   FOREIGN KEY (plan_id)   REFERENCES planes_produccion(id) ON DELETE CASCADE,
  CONSTRAINT fk_plan_insumos_insumo FOREIGN KEY (insumo_id) REFERENCES insumos(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ══════════════════════════════════════════════════════════════════════════════════
-- 7. `sesiones_usuario` — turno del insumista: login único, autorización y presencia
-- ══════════════════════════════════════════════════════════════════════════════════
--
-- Una sola tabla resuelve tres agujeros distintos:
--
--   a) LOGIN ÚNICO. `ModelosRed.kt:197` dice que `device_id` "bloquea el segundo login
--      del mismo usuario desde otra tablet" y que el server "auto-libera tras 9h".
--      Nada de eso existe: `/api/login` ni mira el campo. Hoy el mismo insumista puede
--      estar operando en dos tablets y duplicar aceptaciones.
--
--   b) AUTORIZACIÓN. `GET /api/insumos/pendientes/{rol}` recibe el rol COMO TEXTO desde
--      el cliente: cualquiera con la URL ve y toma pedidos de cualquier bodega. Con el
--      token, el servidor deduce quién es y qué categoría le toca en vez de creerle.
--
--   c) PRESENCIA. "Qué insumistas están de turno", que es lo que el supervisor necesita
--      ver en su panel y hoy no se puede saber.
--
-- El token va EN LA BASE y no en memoria como el `_TOKENS` del admin, que se vacía en
-- cada `systemctl restart sigep`. A un admin en su escritorio volver a loguearse le
-- cuesta nada; a un insumista con la tablet en el bolsillo en mitad del turno, no.
--
-- Historial en filas y no columnas en `usuarios`: la sesión ya cerrada es justo el dato
-- que el supervisor necesita para saber quién estuvo de turno ayer.
CREATE TABLE IF NOT EXISTS sesiones_usuario (
  id           INT NOT NULL AUTO_INCREMENT,
  usuario_id   INT NOT NULL,
  device_id    VARCHAR(100) NOT NULL,
  token        VARCHAR(64) NOT NULL,
  rol          VARCHAR(50) NULL,
  inicio       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ultimo_visto DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  fin          DATETIME NULL,
  cerrada_por  VARCHAR(30) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_sesion_token (token),
  KEY ix_sesion_abierta (usuario_id, fin),
  CONSTRAINT fk_sesion_usuario FOREIGN KEY (usuario_id) REFERENCES usuarios(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ══════════════════════════════════════════════════════════════════════════════════
-- 8. `mensajes_bodega` — chat supervisor ↔ insumista, con historial
-- ══════════════════════════════════════════════════════════════════════════════════
--
-- Tabla aparte de `mensajes_tablet` (CAMBIO 4) porque aquella es admin → tablet de
-- PRODUCCIÓN, de un solo sentido y colgada de `sesion_id`. Esta es bidireccional y entre
-- personas, no entre una persona y una máquina.
--
-- Los dos extremos viven en tablas DISTINTAS y por eso hay dos columnas y no una:
-- los supervisores entran por `administradores` (`/api/admin/login`, lo confirma
-- LoginActivity.kt:120) y los insumistas por `usuarios` (`/api/login`, por PIN).
--
-- `insumista_id` NULL = difusión a todos los de turno. La conversación es el par
-- (supervisor_id, insumista_id), así que cada insumista ve solo la suya.
--
-- Sin borrado: el historial es el punto. Para eso se pidió trazabilidad de lo que se
-- conversa.
CREATE TABLE IF NOT EXISTS mensajes_bodega (
  id            INT NOT NULL AUTO_INCREMENT,
  supervisor_id INT NULL,
  insumista_id  INT NULL,
  emisor        VARCHAR(20) NOT NULL,
  texto         VARCHAR(500) NOT NULL,
  creado_en     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  leido_en      DATETIME NULL,
  PRIMARY KEY (id),
  KEY ix_mensajes_conversacion (insumista_id, creado_en),
  KEY ix_mensajes_supervisor (supervisor_id, creado_en),
  CONSTRAINT fk_mensajes_supervisor FOREIGN KEY (supervisor_id) REFERENCES administradores(id),
  CONSTRAINT fk_mensajes_insumista  FOREIGN KEY (insumista_id)  REFERENCES usuarios(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ── Limpieza ──────────────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS add_col_si_falta;
DROP PROCEDURE IF EXISTS add_idx_si_falta;

-- ── Verificación ──────────────────────────────────────────────────────────────────
SELECT 'insumos'              AS tabla, COUNT(*) AS filas FROM insumos
UNION ALL SELECT 'recetas_productos',   COUNT(*) FROM recetas_productos
UNION ALL SELECT 'pedidos_bodega',      COUNT(*) FROM pedidos_bodega
UNION ALL SELECT 'entregas_proactivas', COUNT(*) FROM entregas_proactivas
UNION ALL SELECT 'planes_produccion',   COUNT(*) FROM planes_produccion
UNION ALL SELECT 'plan_insumos',        COUNT(*) FROM plan_insumos
UNION ALL SELECT 'sesiones_usuario',    COUNT(*) FROM sesiones_usuario
UNION ALL SELECT 'mensajes_bodega',     COUNT(*) FROM mensajes_bodega;
