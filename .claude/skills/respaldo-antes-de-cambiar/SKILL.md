---
name: respaldo-antes-de-cambiar
description: Verifica que el proyecto esté respaldado en git y en GitHub ANTES de tocar un solo archivo, y crea el respaldo en el momento si falta. Úsala al empezar cualquier tarea que vaya a modificar, crear o borrar archivos de SIGEP — frontend, backend, configuración, SQL o documentación — y también antes de un `./deploy.sh`, un `kill -HUP`, un `systemctl restart sigep` o cualquier `ALTER` de base de datos. Se aplica igual a cambios que parecen triviales (una línea, un texto, un color).
---

# Respaldo antes de cambiar

Regla del proyecto: **no se modifica nada de SIGEP sin que el estado anterior esté
guardado en git y subido a GitHub.** Esto no es burocracia: en este proyecto ya se
perdió el frontend entero por compilar sobre un `src/` viejo, y el código corre en una
planta con hasta 21 tablets en producción. El respaldo es lo que hace que cualquier
error sea reversible en un comando.

Esta comprobación se hace **antes** del primer `Edit`/`Write`, no después. Si falta el
respaldo, se crea en ese instante y se le dice al usuario qué se hizo.

## 1. Comprobar (siempre, aunque el cambio sea de una línea)

```bash
cd /home/john/Proyectos/App_Llenadora
git status --short                 # ¿árbol limpio?
git rev-parse HEAD origin/main     # ¿mismo commit local y remoto?
git tag --list | tail -5           # ¿qué puntos de recuperación hay?
```

Interpretación:

| Situación | Qué significa |
|---|---|
| `git status` vacío y los dos hashes iguales | Respaldado. Se puede trabajar. |
| `git status` con archivos | Hay trabajo sin guardar que un error podría borrar. |
| Hashes distintos | Hay commits que solo existen en este servidor. |

## 2. Crear el respaldo si falta

**Nunca** `git checkout`, `git reset`, `git stash drop` ni `git clean` para «limpiar» el
árbol: eso destruye justo lo que hay que respaldar. Se guarda, no se descarta.

```bash
git status --short                      # leer QUÉ hay pendiente antes de añadirlo
git diff                                # y por qué está ahí
git add -A && git commit -m "..."       # mensaje que diga el por qué, no el qué
git push origin main
```

Si los archivos pendientes no son del trabajo en curso y no está claro de dónde salen,
**preguntar al usuario** antes de commitearlos en su nombre. Ningún respaldo justifica
subir a GitHub algo que el usuario no sabe que está ahí — revisar en especial que no
haya credenciales, `.env`, dumps de BD ni APKs nuevos.

## 3. Tag de punto de recuperación

Un tag hace falta cuando el cambio puede romper la planta o es difícil de deshacer a
mano. Se crea **antes** de empezar, nombrado por lo que viene después:

```bash
git tag -a v1.3-pre-segmentadores -m "estado previo a <el cambio>"
git push origin v1.3-pre-segmentadores
```

| Tipo de cambio | ¿Tag? |
|---|---|
| Cualquier cosa en `api_produccion/` | **Sí, obligatorio** |
| `ALTER`/migración de base de datos | **Sí**, y además dump: `backups/produccion_detg_pre_<motivo>_<fecha>.sql.gz` |
| Vista nueva o refactor grande del frontend | **Sí** |
| Retoque de texto, color o un componente aislado | No hace falta; basta el commit + push |

Los tags vigentes están listados en `CLAUDE.md` §0 «Punto de recuperación». Al crear uno
nuevo, **añadirlo a esa tabla** en el mismo trabajo.

## 4. Antes de desplegar

`./deploy.sh` ya aborta si hay cambios sin commitear o commits sin subir, y respalda el
build vigente en `~/respaldos_build_sigep/` (rota a 10). Por eso se usa **siempre el
script** y nunca `npm run build` a mano: `npm run build` vacía su carpeta de destino
antes de empezar y ya se llevó por delante una versión buena.

Para el backend: probar primero el código nuevo en el puerto 8001 y comparar las
respuestas byte a byte con `diff` contra el servicio vivo del 8000. Si el `diff` está
vacío sin los parámetros nuevos, las tablets no necesitan actualización. Procedimiento
completo en `CLAUDE.md` §1.

## 5. Qué decirle al usuario

Reportar el estado en una línea antes de tocar nada, con hechos y no con promesas:

- «Repo limpio y sincronizado con `origin/main` en `7aca122`; empiezo.»
- «Había 3 archivos sin commitear (`src/App.js`, …): los he commiteado y subido como
  `abc1234` antes de tocar nada.»
- «Este cambio toca `api_produccion/`, así que he creado el tag `v1.3-pre-x` y lo he
  subido; para volver: `git checkout v1.3-pre-x`.»

Si el respaldo no se puede hacer (sin red, push rechazado, credenciales), **parar y
avisar**. No se empieza a modificar con el respaldo pendiente.

## Recordatorio de la regla de oro

El respaldo autoriza a deshacer, no a cambiar de más: `api_produccion/` y la base de
datos **no se tocan sin autorización explícita del responsable**. Si la UI necesita algo
que la API no ofrece, hay que parar y preguntar.
