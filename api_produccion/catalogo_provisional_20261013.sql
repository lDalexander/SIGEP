-- Catálogo PROVISIONAL de insumos (2026-10-13), hasta importar el del ERP.
--
-- Los 6 insumos de ejemplo se DESACTIVAN, no se borran: el histórico de pedidos y
-- entregas los nombra. Se cargan 9 genéricos; `bodega` decide a qué insumista le suena
-- la alarma (Materia Prima -> GRANEL, el resto -> EMPAQUE).
--
-- Cuando se importe el ERP con importar_catalogo.py, su política «lo ausente queda
-- activo = 0» desactivará estos genéricos solos si el ERP no los trae.
--
-- Idempotente: se puede volver a ejecutar sin duplicar (nombre es UNIQUE).

START TRANSACTION;

UPDATE insumos SET activo = 0
 WHERE nombre IN (
   'Funda Plástica ULTREX 1KG',
   'Funda Plástica TORBELLINO 1KG',
   'Saco Tejido PQP 25KG',
   'Caja Corrugada Estándar (Detergentes)',
   'Cinta Adhesiva Transparente',
   'Etiqueta Código de Barras'
 );

INSERT INTO insumos (nombre, categoria, bodega, unidad_medida, activo) VALUES
  ('Materia Prima Sólido', 'Materia Prima', 'GRANEL',  'Kg',       1),
  ('Caja',                 'Empaque',       'EMPAQUE', 'Unidades', 1),
  ('Fajilla',              'Empaque',       'EMPAQUE', 'Unidades', 1),
  ('Etiqueta',             'Empaque',       'EMPAQUE', 'Unidades', 1),
  ('Envase',               'Empaque',       'EMPAQUE', 'Unidades', 1),
  ('Tapa',                 'Empaque',       'EMPAQUE', 'Unidades', 1),
  ('Pad',                  'Empaque',       'EMPAQUE', 'Unidades', 1),
  ('Funda',                'Empaque',       'EMPAQUE', 'Unidades', 1),
  ('Saco',                 'Empaque',       'EMPAQUE', 'Unidades', 1)
ON DUPLICATE KEY UPDATE activo = 1, bodega = VALUES(bodega), categoria = VALUES(categoria);

COMMIT;

SELECT id, nombre, categoria, bodega, unidad_medida, activo FROM insumos ORDER BY activo DESC, bodega, nombre;
