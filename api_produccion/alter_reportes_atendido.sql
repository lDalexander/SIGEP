-- Seguimiento de los reportes de problemas de la app desde /admin (2026-08-07).
--
-- Son columnas NUEVAS sobre una tabla que ya existe: `Base.metadata.create_all()` crea
-- tablas que faltan pero NUNCA altera las que están, así que esto hay que ejecutarlo a
-- mano y ANTES de desplegar el código que las lee.
--
-- Idempotente a mano: si ya existen, MySQL da error 1060 y no pasa nada.
--
--   mysql -u <usuario> -p produccion_detg < alter_reportes_atendido.sql

ALTER TABLE reportes_app
  ADD COLUMN atendido TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN atendido_en DATETIME NULL,
  ADD COLUMN atendido_por VARCHAR(150) NULL;

-- Los reportes que ya estaban quedan como pendientes (DEFAULT 0), que es lo correcto:
-- nadie ha confirmado todavía que se hayan resuelto.
