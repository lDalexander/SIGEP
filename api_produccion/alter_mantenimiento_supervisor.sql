-- ============================================================================
-- Migración ADITIVA (no destructiva) para mantenimiento_checklist
-- ----------------------------------------------------------------------------
-- El contrato de la app Android agregó dos campos al checklist de mantenimiento:
--   supervisor  (obligatorio en la app)
--   comentarios (puede venir "")
-- Estas columnas NO existían en la tabla. Agrégalas ANTES de reiniciar el
-- backend con el código nuevo (el modelo SQLAlchemy ya las referencia; si el
-- servidor arranca sin estas columnas, los INSERT fallarán con
-- "Unknown column 'supervisor'" -> 500).
--
-- Es seguro/aditivo: solo añade columnas NULL, no toca datos existentes.
-- ============================================================================

ALTER TABLE mantenimiento_checklist
    ADD COLUMN supervisor  VARCHAR(150) NULL AFTER hora,
    ADD COLUMN comentarios TEXT         NULL AFTER supervisor;
