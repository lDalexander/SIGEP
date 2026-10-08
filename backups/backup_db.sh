#!/usr/bin/env bash
# Respaldo diario de la BD produccion_detg + fotos de evidencia. Retiene 14 días.
#
# La BD sale en DOS archivos, a la manera de 4D (estructura aparte de los datos):
#   sigep_estructura_<TS>.sql.gz  — tablas, índices y claves, sin filas
#   sigep_datos_<TS>.sql.gz       — solo las filas (INSERT con nombre de columna)
# Ninguno lleva `CREATE DATABASE` ni `USE`: se cargan en la base que se quiera, que es
# lo que hace pruebas/instalar.sh. Restaurar a mano:
#   zcat sigep_estructura_<TS>.sql.gz | mysql produccion_detg
#   zcat sigep_datos_<TS>.sql.gz      | mysql produccion_detg
#
# El prefijo `sigep_` es a propósito: la rotación solo borra estos. Los volcados manuales
# de antes de un ALTER (produccion_detg_pre_*.sql.gz) ya no caen en ella; con el nombre
# anterior se borraban a los 14 días como si fueran diarios.
set -uo pipefail
BK="/home/john/workspace/apps/sigep/backups"
CNF="$BK/.dbcreds.cnf"
STATIC="/home/john/workspace/apps/sigep/api_produccion/static"
DB=produccion_detg
RET=14
TS="$(date +%Y%m%d_%H%M%S)"
LOG="$BK/backup.log"
mkdir -p "$BK"

volcar() {  # $1 = nombre, resto = opciones de mysqldump
  local out="$BK/sigep_$1_${TS}.sql.gz"; shift
  if mysqldump --defaults-extra-file="$CNF" --single-transaction --quick "$@" "$DB" 2>>"$LOG" | gzip > "$out"; then
    echo "[$(date '+%F %T')] OK $(basename "$out") ($(du -h "$out" | cut -f1))" >> "$LOG"
  else
    echo "[$(date '+%F %T')] ERROR respaldo $(basename "$out")" >> "$LOG"; rm -f "$out"
  fi
}
volcar estructura --no-data --routines --triggers --events
volcar datos --no-create-info --skip-triggers --complete-insert

# Fotos de entregas y de paros (las referencian filas de la BD por su ruta).
DIRS=(); for d in entregas paros; do [ -d "$STATIC/$d" ] && DIRS+=("$d"); done
if [ ${#DIRS[@]} -gt 0 ]; then
  PH_OUT="$BK/sigep_fotos_${TS}.tar.gz"
  tar czf "$PH_OUT" -C "$STATIC" "${DIRS[@]}" 2>>"$LOG" \
    && echo "[$(date '+%F %T')] OK $(basename "$PH_OUT")" >> "$LOG"
fi

# Retención: solo los diarios. Los dos últimos patrones vacían el formato anterior
# (un solo volcado completo); se pueden quitar cuando no quede ninguno.
find "$BK" -maxdepth 1 -name 'sigep_*_*.gz' -mtime +$RET -delete 2>>"$LOG"
find "$BK" -maxdepth 1 -name 'produccion_detg_2*.sql.gz' -mtime +$RET -delete 2>>"$LOG"
find "$BK" -maxdepth 1 -name 'entregas_fotos_*.tar.gz' -mtime +$RET -delete 2>>"$LOG"
