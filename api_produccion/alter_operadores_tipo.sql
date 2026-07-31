-- ============================================================================
-- Migración ADITIVA (no destructiva) para `operadores`
-- ----------------------------------------------------------------------------
-- Clasifica a los operarios por tipo de línea, igual que ya se hace con las
-- máquinas (`maquinas.tipo`), para que el selector de operador de la tablet no
-- tenga que mostrar a toda la planta.
--
-- Los operarios que YA existen son todos de línea sólida, así que el DEFAULT
-- 'SOLIDO' los deja correctamente clasificados sin tocar ni un dato.
--
-- Es seguro: solo añade una columna con valor por defecto. Ejecutar ANTES de
-- reiniciar el backend con el modelo nuevo (si el servidor arranca esperando la
-- columna y no existe, los SELECT fallan con "Unknown column 'tipo'" -> 500).
--
-- Compatibilidad con las 21 tablets:
--   GET /api/operadores  SIN parámetros sigue devolviendo TODOS los operarios
--   activos y con el MISMO formato [{id, nombre}] que hoy. El filtrado solo
--   ocurre si se pasa ?tipo=SOLIDO|LIQUIDO, cosa que la app actual no hace.
--   Por eso esta migración no requiere actualizar el APK.
-- ============================================================================

ALTER TABLE operadores
    ADD COLUMN tipo VARCHAR(10) NOT NULL DEFAULT 'SOLIDO' AFTER nombre;

-- Comprobación:
--   SELECT tipo, COUNT(*) FROM operadores GROUP BY tipo;
