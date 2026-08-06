-- ============================================================================
-- Jerarquía de fragancias por máquina + marca  (2026-08-06)
-- ============================================================================
-- Hasta ahora la fragancia era universal: la app Android ofrecía la misma lista
-- (Floral / Limón) en cualquier máquina y marca. Con la línea líquida entrando en
-- producción eso deja de ser cierto — cada máquina y marca hace fragancias
-- distintas — así que la fragancia pasa a formar parte de la jerarquía, al lado
-- de `maquina_productos`.
--
-- QUÉ NO SE TOCA (a propósito):
--   * `maquina_productos` se queda tal cual, con su UNIQUE (maquina_id, marca,
--     presentacion). Añadir ahí la fragancia habría multiplicado sus filas y
--     cambiado la respuesta de GET /api/maquinas, que las 21 tablets consumen.
--   * `fragancias` YA EXISTÍA en MySQL (creada a mano como `marcas` y
--     `presentaciones`, con Limón y Floral dentro) y nadie la usaba. Aquí solo se
--     empieza a usar como catálogo maestro; no se modifica ni su esquema ni sus
--     filas.
--
-- Este script crea UNA tabla nueva y no altera ninguna existente, así que
-- ninguna respuesta de la API cambia por sí sola.
--
-- Aplicar:  mysql -u admin -p produccion_detg < alter_fragancias_jerarquia.sql
-- Antes:    respaldo en backups/produccion_detg_pre_fragancias_<fecha>.sql.gz

-- ---------------------------------------------------------------------------
-- 1. La jerarquía: qué fragancias puede hacer cada máquina de cada marca
-- ---------------------------------------------------------------------------
-- La granularidad es (máquina, marca): la fragancia no depende del gramaje —
-- ULTREX 1 KG y ULTREX 3 KG en la misma máquina llevan las mismas fragancias—,
-- así que la presentación queda fuera y no hay que configurar una fila por cada
-- combinación de `maquina_productos`.
--
-- `marca` y `fragancia` se guardan como texto, igual que en `maquina_productos`
-- y `recetas_productos`, y con los mismos valores que `sesiones_trabajo.marca` /
-- `.fragancia`: así el histórico se cruza sin traducir ids.
--
-- `activo` es baja lógica, como en `maquina_productos`: quitar una fragancia de
-- una máquina no puede borrar el histórico que la usó.

CREATE TABLE IF NOT EXISTS `maquina_marca_fragancias` (
  `id` int NOT NULL AUTO_INCREMENT,
  `maquina_id` int NOT NULL,
  `marca` varchar(100) NOT NULL,
  `fragancia` varchar(100) NOT NULL,
  `activo` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_maquina_marca_fragancia` (`maquina_id`,`marca`,`fragancia`),
  KEY `ix_mmf_maquina` (`maquina_id`),
  CONSTRAINT `fk_mmf_maquina` FOREIGN KEY (`maquina_id`) REFERENCES `maquinas` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- OJO: `main.py` llama a `Base.metadata.create_all()` al arrancar, así que en
-- cuanto un proceso importa el modelo nuevo la tabla se crea sola (create_all crea
-- lo que falta, nunca altera lo que existe). Si nació por ahí, le falta el DEFAULT
-- del `activo`, porque SQLAlchemy lo resuelve en Python y no en MySQL: una fila
-- insertada a mano sin ese campo quedaría en NULL y se leería como dada de baja.
-- Este MODIFY es idempotente y deja el esquema igual por los dos caminos.
ALTER TABLE `maquina_marca_fragancias` MODIFY `activo` tinyint(1) NOT NULL DEFAULT '1';

-- ---------------------------------------------------------------------------
-- 2. Siembra: el estado de hoy, tal como funciona la planta ahora mismo
-- ---------------------------------------------------------------------------
-- Cada máquina+marca ya configurada arranca con TODAS las fragancias activas del
-- catálogo (hoy Floral y Limón), que es exactamente lo que la app ofrece hoy en
-- todas las máquinas. Así la jerarquía nace describiendo la realidad y desde la
-- web se va recortando lo que no aplique, en vez de empezar vacía y dejar
-- máquinas sin ninguna fragancia.
--
-- Se leen los nombres de la tabla `fragancias` en lugar de escribirlos como
-- literales para no depender del encoding de este archivo ('Limón' lleva tilde).
--
-- INSERT IGNORE + UNIQUE hacen el script reejecutable sin duplicar.

INSERT IGNORE INTO `maquina_marca_fragancias` (`maquina_id`, `marca`, `fragancia`, `activo`)
SELECT DISTINCT mp.maquina_id, mp.marca, f.nombre, 1
FROM `maquina_productos` mp
CROSS JOIN `fragancias` f
WHERE mp.activo = 1
  AND f.activa = 1;

-- Comprobación (debería listar una fila por máquina+marca con su nº de fragancias):
-- SELECT m.nombre AS maquina, mmf.marca, COUNT(*) AS fragancias
-- FROM maquina_marca_fragancias mmf JOIN maquinas m ON m.id = mmf.maquina_id
-- WHERE mmf.activo = 1 GROUP BY m.nombre, mmf.marca ORDER BY m.nombre, mmf.marca;
