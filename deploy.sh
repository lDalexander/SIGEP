#!/usr/bin/env bash
#
# Despliegue del frontend SIGEP a producción (nginx, puerto 3000).
#
# Por qué existe este script
# --------------------------
# El código fuente del frontend se perdió una vez: se ejecutó `npm run build` sobre
# un `src/` viejo y el resultado sobrescribió el único build que tenía la versión
# buena. `npm run build` VACÍA la carpeta de destino nada más empezar, así que si
# algo falla a mitad te quedas sin build y sin fuente.
#
# Este script hace imposible repetirlo:
#
#   1. Se niega a desplegar si hay cambios sin commitear.
#   2. Se niega a desplegar si hay commits sin subir a GitHub. El fuente SIEMPRE
#      está en el remoto antes de tocar nada.
#   3. Respalda el build vigente con fecha, antes de compilar.
#   4. Compila en un directorio APARTE (BUILD_PATH). Si la compilación falla, el
#      build en producción sigue intacto y servido: nginx nunca ve un hueco.
#   5. Solo cuando el resultado está completo lo intercambia por el vigente.
#
# Uso:  ./deploy.sh            despliega
#       ./deploy.sh --revisar  solo comprueba, no toca nada
#
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRONT="$RAIZ/frontend_sigep"
BUILD="$FRONT/build"
NUEVO="$FRONT/.build-nuevo"
RESPALDOS="$HOME/respaldos_build_sigep"
CONSERVAR=10
SELLO="$(date +%F_%H%M%S)"
PUERTO=3000

rojo()  { printf '\033[31m%s\033[0m\n' "$*"; }
verde() { printf '\033[32m%s\033[0m\n' "$*"; }
paso()  { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }

abortar() {
  rojo "✗ $1"
  [[ -n "${2:-}" ]] && printf '  %s\n' "$2"
  exit 1
}

SOLO_REVISAR=false
[[ "${1:-}" == "--revisar" ]] && SOLO_REVISAR=true

# ─────────────────────────────────────────────────────────────────────────────
paso "1/6  El fuente tiene que estar a salvo"

[[ -d "$FRONT/src" ]] || abortar "no encuentro $FRONT/src"

if [[ -n "$(git -C "$RAIZ" status --porcelain)" ]]; then
  git -C "$RAIZ" status --short | sed 's/^/      /'
  abortar "hay cambios sin commitear" \
          "Commitéalos antes de desplegar: lo que no está en git se puede perder."
fi

git -C "$RAIZ" fetch -q origin
PENDIENTES="$(git -C "$RAIZ" log --oneline origin/main..HEAD | wc -l)"
if [[ "$PENDIENTES" -gt 0 ]]; then
  git -C "$RAIZ" log --oneline origin/main..HEAD | sed 's/^/      /'
  abortar "$PENDIENTES commit(s) sin subir a GitHub" \
          "Ejecuta: git push origin main"
fi
verde "      árbol limpio y sincronizado con origin/main"
verde "      HEAD: $(git -C "$RAIZ" rev-parse --short HEAD)"

if $SOLO_REVISAR; then
  paso "Revisión superada. No se ha tocado nada."
  exit 0
fi

# ─────────────────────────────────────────────────────────────────────────────
paso "2/6  Respaldo del build vigente"

mkdir -p "$RESPALDOS"
if [[ -d "$BUILD" ]]; then
  cp -a "$BUILD" "$RESPALDOS/build_$SELLO"
  verde "      $RESPALDOS/build_$SELLO ($(du -sh "$BUILD" | cut -f1))"
else
  printf '      no hay build previo que respaldar\n'
fi

# ─────────────────────────────────────────────────────────────────────────────
paso "3/6  Compilando en un directorio aparte"
printf '      el build en producción sigue servido mientras tanto\n\n'

rm -rf "$NUEVO"
( cd "$FRONT" && BUILD_PATH="$NUEVO" npm run build )

# ─────────────────────────────────────────────────────────────────────────────
paso "4/6  Comprobando que el resultado sirve"

[[ -f "$NUEVO/index.html" ]] || abortar "la compilación no generó index.html" \
                                        "El build anterior sigue intacto en $BUILD"
JS="$(find "$NUEVO/static/js" -name 'main.*.js' 2>/dev/null | head -1)"
[[ -n "$JS" ]] || abortar "la compilación no generó el bundle principal" \
                          "El build anterior sigue intacto en $BUILD"
grep -q 'id="root"' "$NUEVO/index.html" || abortar "index.html no tiene el punto de montaje"
verde "      index.html + $(basename "$JS") ($(du -sh "$NUEVO" | cut -f1))"

# ─────────────────────────────────────────────────────────────────────────────
paso "5/6  Publicando"

if [[ -d "$BUILD" ]]; then
  mv "$BUILD" "$FRONT/.build-anterior.$SELLO"
fi
mv "$NUEVO" "$BUILD"
rm -rf "$FRONT/.build-anterior.$SELLO"
verde "      $BUILD actualizado"

# nginx sirve estáticos leyéndolos en cada petición: recargar solo hace falta si
# cambia su configuración. Se intenta sin pedir contraseña y no es crítico.
if sudo -n systemctl reload nginx 2>/dev/null; then
  verde "      nginx recargado"
else
  printf '      nginx no recargado (no hace falta para archivos estáticos)\n'
fi

# Rotación: conservar solo los últimos respaldos.
ls -1dt "$RESPALDOS"/build_* 2>/dev/null | tail -n +$((CONSERVAR + 1)) | while read -r viejo; do
  rm -rf "$viejo"
done

# ─────────────────────────────────────────────────────────────────────────────
paso "6/6  Verificación"

CODIGO="$(curl -s -o /dev/null -w '%{http_code}' -m 10 "http://127.0.0.1:$PUERTO/" || echo 000)"
API="$(curl -s -o /dev/null -w '%{http_code}' -m 10 "http://127.0.0.1:$PUERTO/api/dashboard/kpis" || echo 000)"

printf '      index  -> %s\n' "$CODIGO"
printf '      /api   -> %s\n' "$API"

if [[ "$CODIGO" == "200" && "$API" == "200" ]]; then
  verde "\n✓ Desplegado. http://150.36.200.252:$PUERTO"
  printf '  Respaldo del build anterior: %s\n' "$RESPALDOS/build_$SELLO"
else
  rojo "\n✗ El sitio no responde como se esperaba"
  printf '  Para volver atrás:\n'
  printf '    rm -rf %s && cp -a %s %s\n' "$BUILD" "$RESPALDOS/build_$SELLO" "$BUILD"
  exit 1
fi
