#!/usr/bin/env bash
# ============================================================
#  SIGEP — Script de arranque para producción
#  Levanta FastAPI con Gunicorn + Uvicorn workers
#  Uso: ./start_produccion.sh
# ============================================================

set -euo pipefail

# Directorio donde reside este script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "[SIGEP] Iniciando servidor de producción..."
echo "[SIGEP] Workers: 4 | Bind: 0.0.0.0:8000"

exec gunicorn main:app \
    -w 4 \
    -k uvicorn.workers.UvicornWorker \
    --bind 0.0.0.0:8000 \
    --access-logfile - \
    --error-logfile -
