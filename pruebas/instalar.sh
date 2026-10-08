#!/usr/bin/env bash
# Levanta SIGEP de pruebas (MySQL + API + web en Docker) y le carga una copia de la BD.
#
#   ./instalar.sh [carpeta]     # por defecto pruebas/datos/
#
# En la carpeta se dejan los archivos del respaldo diario (backups/backup_db.sh):
#   sigep_estructura_<TS>.sql.gz   obligatorio
#   sigep_datos_<TS>.sql.gz        obligatorio, mismo <TS>
#   sigep_fotos_<TS>.tar.gz        opcional
# Si hay varios, se usa el más reciente. Volver a ejecutarlo reemplaza la BD de pruebas
# entera por la copia: es también la forma de «resetear» el entorno.
#
# El correo y el reporte semanal quedan apagados (ver neutralizar.sql y docker-compose.yml).
set -euo pipefail
cd "$(dirname "$0")"
DATOS="${1:-datos}"

command -v docker >/dev/null || { echo "Falta Docker."; exit 1; }
docker compose version >/dev/null || { echo "Falta el plugin «docker compose»."; exit 1; }

EST=$(ls -1 "$DATOS"/sigep_estructura_*.sql.gz 2>/dev/null | sort | tail -1 || true)
[ -n "$EST" ] || { echo "No hay sigep_estructura_*.sql.gz en $DATOS/"; exit 1; }
TS=${EST##*sigep_estructura_}; TS=${TS%.sql.gz}
DAT="$DATOS/sigep_datos_${TS}.sql.gz"
FOT="$DATOS/sigep_fotos_${TS}.tar.gz"
[ -f "$DAT" ] || { echo "Falta $DAT (debe ser del mismo respaldo que $EST)"; exit 1; }
echo "Copia: $TS"

# Contraseñas propias del entorno de pruebas, generadas la primera vez.
if [ ! -f .env ]; then
  printf 'DB_ROOT_PASSWORD=%s\nDB_PASSWORD=%s\n' "$(openssl rand -hex 16)" "$(openssl rand -hex 16)" > .env
  chmod 600 .env
fi
set -a; . ./.env; set +a

echo "1/4  Base de datos"
docker compose up -d db
# Por TCP y no por socket: el servidor temporal que MySQL arranca al inicializar solo
# escucha en el socket, y cargar ahí se perdería al reiniciarse.
until docker compose exec -T db mysql -h127.0.0.1 -uroot -p"$DB_ROOT_PASSWORD" -e 'SELECT 1' >/dev/null 2>&1; do sleep 2; done

echo "2/4  Cargando estructura y datos"
docker compose stop api >/dev/null 2>&1 || true   # que nada escriba mientras se carga
sql() { docker compose exec -T db mysql -h127.0.0.1 -uroot -p"$DB_ROOT_PASSWORD" produccion_detg 2>&1 | grep -v 'Using a password' || true; }
docker compose exec -T db mysql -h127.0.0.1 -uroot -p"$DB_ROOT_PASSWORD" \
  -e 'DROP DATABASE IF EXISTS produccion_detg; CREATE DATABASE produccion_detg' 2>/dev/null
gunzip -c "$EST" | sql
gunzip -c "$DAT" | sql
sql < neutralizar.sql

echo "3/4  API y web (la primera vez compila; tarda unos minutos)"
docker compose up -d --build api web

if [ -f "$FOT" ]; then
  echo "4/4  Fotos de evidencia"
  docker compose exec -T api tar xzf - -C static < "$FOT"
else
  echo "4/4  Sin archivo de fotos; se omite"
fi

cat <<EOF

Listo.
  Web:  http://localhost:${WEB_PORT:-3000}      (usuarios admin: los mismos de producción)
  API:  http://localhost:${API_PORT:-8000}/api/maquinas
  BD:   127.0.0.1:${DB_PORT:-3307}  usuario sigep, contraseña en pruebas/.env

Apagar:            docker compose -f $(pwd)/docker-compose.yml down
Borrar todo (BD):  docker compose -f $(pwd)/docker-compose.yml down -v
EOF
