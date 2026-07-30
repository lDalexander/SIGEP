#!/usr/bin/env bash
# Respaldo diario de la BD produccion_detg + fotos de evidencia. Retiene 14 días.
set -uo pipefail
BK="/home/john/Proyectos/App_Llenadora/backups"
CNF="$BK/.dbcreds.cnf"
FOTOS="/home/john/Proyectos/App_Llenadora/api_produccion/static/entregas"
RET=14
TS="$(date +%Y%m%d_%H%M%S)"
LOG="$BK/backup.log"
mkdir -p "$BK"

DB_OUT="$BK/produccion_detg_${TS}.sql.gz"
if mysqldump --defaults-extra-file="$CNF" --single-transaction --quick --databases produccion_detg 2>>"$LOG" | gzip > "$DB_OUT"; then
  echo "[$(date '+%F %T')] OK db -> $(basename "$DB_OUT") ($(du -h "$DB_OUT" | cut -f1))" >> "$LOG"
else
  echo "[$(date '+%F %T')] ERROR respaldo BD" >> "$LOG"; rm -f "$DB_OUT"
fi

if [ -d "$FOTOS" ]; then
  PH_OUT="$BK/entregas_fotos_${TS}.tar.gz"
  tar czf "$PH_OUT" -C "$(dirname "$FOTOS")" "$(basename "$FOTOS")" 2>>"$LOG" \
    && echo "[$(date '+%F %T')] OK fotos -> $(basename "$PH_OUT")" >> "$LOG"
fi

# Retención (borra respaldos > RET días)
find "$BK" -maxdepth 1 -name 'produccion_detg_*.sql.gz' -mtime +$RET -delete 2>>"$LOG"
find "$BK" -maxdepth 1 -name 'entregas_fotos_*.tar.gz' -mtime +$RET -delete 2>>"$LOG"
