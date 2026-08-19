-- Registro fotográfico de paros + velocidad de máquina (2026-08-19).
--
-- Dos cosas independientes que se piden a la vez, y que se piden porque hoy no hay forma
-- de saber POR QUÉ una máquina estuvo parada más allá de un comentario escrito a mano, ni
-- a qué ritmo trabajaba mientras producía.
--
-- Bloque 1 — `paros_maquina.foto_path`: es una columna NUEVA sobre una tabla que YA
-- existe, y `Base.metadata.create_all()` crea tablas que faltan pero NUNCA altera las que
-- están. Hay que ejecutarlo a mano y ANTES de desplegar el código que la lee.
--
-- Bloque 2 — `registro_velocidad`: tabla nueva, así que `create_all()` la crearía sola en
-- cuanto un proceso importe el modelo. Va aquí de todas formas por dos razones: para que
-- el esquema quede escrito en el repo y no solo en Python, y porque `create_all` no pone
-- el DEFAULT en MySQL (SQLAlchemy lo resuelve en Python), así que una fila insertada a
-- mano sin `fecha_hora` quedaría en NULL.
--
-- Idempotente a mano: si la columna ya existe, MySQL da error 1060 y no pasa nada; el
-- CREATE lleva IF NOT EXISTS.
--
--   mysql -u <usuario> -p produccion_detg < alter_paros_foto_velocidad.sql

-- ── 1. Foto opcional en cada paro ──────────────────────────────────────────────────
-- Mismo patrón exacto que `entregas_proactivas.foto_path`, que lleva en producción desde
-- antes y ya funciona: aquí se guarda la RUTA (`/static/paros/<archivo>`), nunca la
-- imagen. Un BLOB haría que cada dump de `backups/` pesara megabytes y que restaurar la
-- BD dejara de ser una operación de segundos.
--
-- NULL = sin foto, y es lo normal: el registro fotográfico es OPCIONAL. Un paro sin foto
-- no es un paro mal registrado, así que la columna no lleva NOT NULL ni default.
ALTER TABLE paros_maquina
  ADD COLUMN foto_path VARCHAR(500) NULL;

-- ── 2. Velocidad de máquina ────────────────────────────────────────────────────────
-- Una FILA POR LECTURA, no una columna en `sesiones_trabajo`: el operario la registra
-- cuando cambia el ritmo, así que lo que interesa es la serie a lo largo del turno. Con
-- una sola columna por sesión, la segunda lectura borraría la primera y no se podría
-- saber en qué tramo la máquina fue más lenta — que es justo lo que hace falta para
-- calcular eficiencia contra las pacas registradas.
--
-- `velocidad` es FLOAT en fundas/min. Float y no INT porque una lectura de 42.5 es legítima
-- y redondearla en la BD perdería el dato de origen.
--
-- `request_id` es UNIQUE por lo mismo que en `registro_pallets`: las tablets son
-- offline-first y reintentan el mismo envío hasta que entra. Sin él, una tablet sin red
-- guardaría la misma lectura cinco veces y la media del turno saldría sesgada.
CREATE TABLE IF NOT EXISTS registro_velocidad (
  id          INT NOT NULL AUTO_INCREMENT,
  session_id  INT DEFAULT NULL,
  velocidad   FLOAT DEFAULT NULL,
  fecha_hora  DATETIME DEFAULT NULL,
  request_id  VARCHAR(50) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_registro_velocidad_request_id (request_id),
  KEY ix_registro_velocidad_session_id (session_id),
  KEY ix_registro_velocidad_id (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- El default en MySQL, que `create_all()` no pone. Idempotente: repetirlo no molesta.
ALTER TABLE registro_velocidad
  MODIFY fecha_hora DATETIME NULL DEFAULT CURRENT_TIMESTAMP;
