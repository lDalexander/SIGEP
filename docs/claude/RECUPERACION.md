# SIGEP — Puntos de recuperación

> Movido desde `CLAUDE.md` el 2026-10-14 para aligerar el contexto que se carga en cada
> sesión. Contenido literal, sin reescribir.

Al crear un tag nuevo, **añadirlo a esta tabla** en el mismo trabajo.

### Punto de recuperación

| | |
|---|---|
| Tag estable | `v1.0-frontend-reconstruido` (en GitHub) |
| Tag previo al filtro de horas | `v1.1-pre-filtro-horas` (en GitHub) |
| Tag previo a la vista de paros | `v1.2-pre-paros` (en GitHub) |
| Tag previo a los segmentadores | `v1.3-pre-segmentadores` (en GitHub) |
| Tag previo a los filtros de estadísticas | `v1.4-pre-filtros-estadisticas` (en GitHub) |
| Tag previo a los menús encadenados | `v1.5-pre-opciones-encadenadas` (en GitHub) |
| Tag previo a la jerarquía de fragancias | `v1.6-pre-fragancias` (en GitHub) |
| Tag previo a la gestión de usuarios | `v1.7-pre-gestion-usuarios` (en GitHub) |
| Tag previo al historial de pacas | `v1.8-pre-historial-pacas` (en GitHub) |
| Tag previo a la caducidad de sesión | `v1.9-pre-caducidad-sesion` (en GitHub) |
| Tag previo al estado real de tablet | `v1.10-pre-estado-tablet` (en GitHub) |
| Tag previo al correo de reportes | `v1.11-pre-correo-reportes` (en GitHub) |
| Tag previo al panel de correo | `v1.12-pre-config-correo` (en GitHub) |
| Tag previo a excluir el almuerzo | `v1.13-pre-excluir-almuerzo` (en GitHub) |
| Tag previo a la contingencia de paros | `v1.14-pre-contingencia-paros` (en GitHub) |
| Tag previo a la foto y la velocidad | `v1.15-pre-foto-velocidad` (en GitHub) |
| Tag previo al estándar de velocidad | `v1.16-pre-estandar-velocidad` (en GitHub) |
| Tag previo al visor de fotos de paro | `v1.17-pre-visor-fotos-paro` (en GitHub) |
| Tag previo a agrupar la jerarquía por producto | `v1.18-pre-jerarquia-producto` (en GitHub) |
| Tag previo a la fragancia en la jerarquía | `v1.19-pre-jerarquia-fragancia` (en GitHub) |
| Tag previo al chat por conversaciones | `v1.20-pre-chat-conversaciones` (en GitHub) |
| Tag previo a la entrega sin máquina | `v1.21-pre-entregas-sin-maquina` (en GitHub) |
| Build previo a agrupar por producto | `~/respaldos_build_sigep/build_2026-08-31_141538` |
| Build previo al logo original | `~/respaldos_build_sigep/build_2026-08-31_142715` |
| Build previo a la fragancia en la jerarquía | `~/respaldos_build_sigep/build_2026-08-31_145150` |
| Build previo a la meta en la fila | `~/respaldos_build_sigep/build_2026-08-31_163607` |
| Build previo al estándar de velocidad | `~/respaldos_build_sigep/build_2026-08-21_152227` |
| Build previo a la contingencia de paros | `~/respaldos_build_sigep/build_2026-08-19_165958` |
| BD previa al `ALTER` de reportes (**borrado por la rotación**) | `backups/produccion_detg_pre_reportes_atendido_20260807_124057.sql.gz` |
| Build previo al panel de correo | `~/respaldos_build_sigep/build_2026-08-07_114148` |
| Build previo al estado de tablet | `~/respaldos_build_sigep/build_2026-08-07_104035` |
| Build previo a la caducidad de sesión | `~/respaldos_build_sigep/build_2026-08-07_101252` |
| Build previo a estos dos cambios | `~/respaldos_build_sigep/build_2026-08-06_164105` |
| Build previo a las fragancias | `~/respaldos_build_sigep/build_2026-08-06_130039` |
| Build de esa versión | `~/respaldos_build_sigep/build_pre-paros_2026-08-05_*` |
| Build previo a los segmentadores | `~/respaldos_build_sigep/build_2026-08-05_165753` |
| Build estable v1.0 | `~/RESPALDO_build_estable_v1.0` |
| Builds rotados | `~/respaldos_build_sigep/` (últimos 10) |
| BD previa al último `ALTER` (**borrado por la rotación**) | `backups/produccion_detg_pre_tipo_operario_*.sql.gz` |

```bash
git checkout v1.0-frontend-reconstruido     # volver al frontend estable
git checkout v1.2-pre-paros                 # volver a justo antes de la vista de paros
git checkout v1.3-pre-segmentadores         # volver a justo antes de los segmentadores
```

La comprobación de respaldo previa a cualquier cambio está automatizada como skill del
proyecto: `.claude/skills/respaldo-antes-de-cambiar/SKILL.md` (qué comprobar, cuándo hace
falta tag, y la prohibición de «limpiar» el árbol con `checkout`/`reset`/`clean`).
