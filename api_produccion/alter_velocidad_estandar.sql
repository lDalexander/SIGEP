-- Velocidad estándar por (máquina, presentación) — 2026-08-21.
--
-- Desde el 2026-08-19 la tablet registra a qué velocidad trabaja la máquina
-- (`registro_velocidad`, en unidades/min), pero el número llegaba solo: 30 unidades/min
-- no es rápido ni lento hasta que se sabe qué se esperaba de ESA máquina con ESE gramaje.
-- Esta tabla guarda ese esperado, y es lo que permite que el dashboard pinte la lectura
-- como dentro o fuera de rango en vez de limitarse a mostrarla.
--
-- Es una TABLA NUEVA, así que `Base.metadata.create_all()` la crearía sola en cuanto un
-- proceso importe el modelo — es exactamente lo que pasó con `registro_velocidad`. Va
-- aquí de todas formas por las dos razones de siempre: para que el esquema quede escrito
-- en el repo y no solo en Python, y porque `create_all` NO pone el DEFAULT en MySQL
-- (SQLAlchemy resuelve `activo=True` en Python), así que una fila insertada a mano sin
-- ese campo quedaría en NULL, o sea leída como dada de baja.
--
-- NINGUNA tabla existente se toca. No hay ALTER de columnas: nada de lo que ya está en
-- producción cambia de forma.
--
--   mysql -u <usuario> -p produccion_detg < alter_velocidad_estandar.sql

-- ── Estándar de velocidad por máquina y presentación ───────────────────────────────
-- GRANULARIDAD (máquina, presentación), sin marca. Es el mismo criterio con el que
-- `maquina_marca_fragancias` deja fuera la presentación: lo que manda el ritmo es el
-- gramaje y la máquina que lo llena, no la etiqueta que lleve la funda. ULTREX 1 KG y
-- TORBELLINO 1 KG en la Máquina 7 salen al mismo ritmo, así que colgar esto de
-- `maquina_productos` habría multiplicado las filas a configurar sin ganar precisión —
-- y cada fila de más es una que alguien tiene que mantener a mano.
--
-- UN RANGO (`vel_min`, `vel_max`) Y NO UN VALOR ÚNICO: el ritmo real oscila con el
-- material, el operario y el turno. Con un objetivo puntual, cualquier turno normal
-- aparecería como desviación y la señal dejaría de servir para nada.
--
-- FLOAT y no INT: la Máquina 8 con 100 GR va a decenas de unidades por minuto y un
-- 37.5 legítimo no debe redondearse en la BD. Mismo tipo que `registro_velocidad.velocidad`,
-- que es contra lo que se compara.
--
-- Los dos NOT NULL: un estándar sin mínimo o sin máximo no es medio estándar, es una
-- fila que nadie sabría interpretar. Si hay que dejar de aplicarlo se usa `activo`.
CREATE TABLE IF NOT EXISTS maquina_presentacion_velocidad (
  id            INT NOT NULL AUTO_INCREMENT,
  maquina_id    INT NOT NULL,
  presentacion  VARCHAR(100) NOT NULL,
  vel_min       FLOAT NOT NULL,
  vel_max       FLOAT NOT NULL,
  activo        TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uq_maquina_presentacion_velocidad (maquina_id, presentacion),
  KEY ix_maquina_presentacion_velocidad_maquina_id (maquina_id),
  KEY ix_maquina_presentacion_velocidad_id (id),
  CONSTRAINT fk_maquina_presentacion_velocidad_maquina
    FOREIGN KEY (maquina_id) REFERENCES maquinas (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- El default que `create_all()` no pone en MySQL. Idempotente: repetirlo no molesta, y
-- hace falta si la tabla ya la creó el arranque del servicio antes que este script.
ALTER TABLE maquina_presentacion_velocidad
  MODIFY activo TINYINT(1) NOT NULL DEFAULT 1;
