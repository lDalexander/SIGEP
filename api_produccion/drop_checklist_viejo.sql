-- ============================================================================
-- Eliminación del sistema VIEJO de checklist dinámico (constructor de formularios)
-- ----------------------------------------------------------------------------
-- Ejecutar contra la base `produccion_detg` cuando decidas eliminar físicamente
-- las tablas. El backend ya NO referencia estos modelos/tablas.
--
-- ORDEN OBLIGATORIO: primero las tablas hijas (FKs) y luego las padre.
--   checklist_respuesta_valores  -> FK a checklist_respuestas
--   checklist_respuestas         -> FK a checklist_plantillas
--   checklist_campos             -> FK a checklist_plantillas
--   checklist_plantillas         (padre)
--
-- DESTRUCTIVO E IRREVERSIBLE: se pierden los datos históricos de esos checklists.
-- Haz respaldo antes (mysqldump produccion_detg checklist_respuesta_valores
-- checklist_respuestas checklist_campos checklist_plantillas > backup.sql).
-- ============================================================================

DROP TABLE IF EXISTS checklist_respuesta_valores;
DROP TABLE IF EXISTS checklist_respuestas;
DROP TABLE IF EXISTS checklist_campos;
DROP TABLE IF EXISTS checklist_plantillas;
