#!/usr/bin/env bash
# ============================================================================
#  SIGEP — Reinicio / diagnóstico de servicios
#  Reinicia la pila de la aplicación cuando algo se comporta mal (bug, cuelgue,
#  servicio degradado). Las sesiones de trabajo NO se pierden: viven en MySQL y
#  las tablets reenvían su cola offline al reconectar.
#
#  Uso:
#    ./reiniciar_servicios.sh            Reinicia la app (sigep + nginx)
#    ./reiniciar_servicios.sh --all      Reinicia TODO, incluida la base (mysql)
#    ./reiniciar_servicios.sh --status   Solo muestra estado/salud (no reinicia)
#    ./reiniciar_servicios.sh -h         Ayuda
#
#  Nota: --all reinicia MySQL (corta conexiones unos segundos). Úsalo solo si la
#        base es la que falla; para problemas del API/web basta el modo normal.
# ============================================================================
set -uo pipefail

API_URL="http://127.0.0.1:8000/api/maquinas"   # endpoint liviano para health-check
WEB_URL="http://127.0.0.1:3000/"               # dashboard servido por nginx

c_ok=$'\033[0;32m'; c_err=$'\033[0;31m'; c_warn=$'\033[0;33m'; c_dim=$'\033[0;90m'; c_off=$'\033[0m'

log()  { echo "${c_dim}[$(date '+%H:%M:%S')]${c_off} $*"; }
ok()   { echo "  ${c_ok}✔${c_off} $*"; }
err()  { echo "  ${c_err}✘${c_off} $*"; }
warn() { echo "  ${c_warn}!${c_off} $*"; }

# sudo solo si no somos root
SUDO=""
if [ "$(id -u)" -ne 0 ]; then SUDO="sudo"; fi

uso() { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

estado_servicio() {
  local s="$1"
  local act; act="$(systemctl is-active "$s" 2>/dev/null)"
  local en;  en="$(systemctl is-enabled "$s" 2>/dev/null)"
  if [ "$act" = "active" ]; then
    ok "$(printf '%-8s' "$s") activo · arranque-automático=${en}"
  else
    err "$(printf '%-8s' "$s") ${act:-desconocido} · arranque-automático=${en}"
  fi
}

reiniciar() {
  local s="$1"
  log "Reiniciando ${s}…"
  if $SUDO systemctl restart "$s"; then
    sleep 2
    if [ "$(systemctl is-active "$s" 2>/dev/null)" = "active" ]; then
      ok "${s} reiniciado y activo"
    else
      err "${s} NO quedó activo tras el reinicio. Últimas líneas del log:"
      $SUDO journalctl -u "$s" -n 12 --no-pager 2>/dev/null | sed 's/^/      /'
      return 1
    fi
  else
    err "Falló el reinicio de ${s}"
    return 1
  fi
}

salud() {
  echo
  log "Verificación de salud:"
  # API
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$API_URL" 2>/dev/null)"
  if [ "$code" = "200" ]; then ok "API responde (HTTP $code en /api/maquinas)"; else err "API no responde correctamente (HTTP ${code:-sin-respuesta})"; fi
  # Web
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$WEB_URL" 2>/dev/null)"
  if [ "$code" = "200" ]; then ok "Dashboard responde (HTTP $code en :3000)"; else err "Dashboard no responde (HTTP ${code:-sin-respuesta})"; fi
  # Sesiones de trabajo activas (informativo)
  local n
  n="$(mysql -u admin -p3012 produccion_detg -N -e 'SELECT COUNT(*) FROM sesiones_trabajo WHERE fin_turno IS NULL;' 2>/dev/null)"
  [ -n "$n" ] && log "Sesiones de trabajo activas en este momento: ${n}"
}

# ---- argumentos ----
MODO="app"
case "${1:-}" in
  -h|--help) uso ;;
  --status)  MODO="status" ;;
  --all)     MODO="all" ;;
  "")        MODO="app" ;;
  *) err "Opción desconocida: $1"; echo "Usa -h para ayuda."; exit 2 ;;
esac

echo "============================================================"
echo "  SIGEP · gestión de servicios   (modo: ${MODO})"
echo "============================================================"

echo
log "Estado actual:"
for s in mysql sigep nginx; do estado_servicio "$s"; done

if [ "$MODO" = "status" ]; then
  salud
  echo; log "Modo solo-estado: no se reinició nada."
  exit 0
fi

echo
# Aviso si hay trabajo activo (no bloquea: el reinicio es seguro, pero conviene saberlo)
activas="$(mysql -u admin -p3012 produccion_detg -N -e 'SELECT COUNT(*) FROM sesiones_trabajo WHERE fin_turno IS NULL;' 2>/dev/null)"
if [ -n "${activas:-}" ] && [ "${activas:-0}" -gt 0 ]; then
  warn "Hay ${activas} sesión(es) de trabajo ACTIVA(s). El reinicio es seguro:"
  warn "las sesiones quedan en MySQL y las tablets reenvían su cola al reconectar."
fi

rc=0
if [ "$MODO" = "all" ]; then
  warn "Reiniciando también MySQL (cortará conexiones unos segundos)."
  reiniciar mysql || rc=1
fi
reiniciar sigep || rc=1
reiniciar nginx || rc=1

salud

echo
if [ "$rc" -eq 0 ]; then
  echo "${c_ok}✔ Listo. Servicios reiniciados correctamente.${c_off}"
else
  echo "${c_err}✘ Hubo problemas al reiniciar uno o más servicios. Revisa los logs arriba.${c_off}"
fi
exit $rc
