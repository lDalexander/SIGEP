# Cambio pendiente en la app Android — operarios por línea

**Fecha:** 2026-07-31
**Estado del backend:** ya desplegado y compatible. **No hay urgencia**: la app actual
sigue funcionando exactamente igual sin ningún cambio.

## Qué cambió en el servidor

Los operarios ahora se clasifican por línea, igual que las máquinas:

```sql
ALTER TABLE operadores ADD COLUMN tipo VARCHAR(10) NOT NULL DEFAULT 'SOLIDO';
```

Los 13 operarios que existían quedaron como `SOLIDO`. Desde la web
(`/admin` → Operarios) ya se pueden dar de alta operarios de línea `LIQUIDO`.

## Contrato del endpoint

`GET /api/operadores` acepta un parámetro **opcional**:

| Petición | Devuelve |
|---|---|
| `GET /api/operadores` | **todos** los activos — exactamente igual que antes |
| `GET /api/operadores?tipo=SOLIDO` | solo los de línea sólida |
| `GET /api/operadores?tipo=LIQUIDO` | solo los de línea líquida |

El **formato de la respuesta no cambió**: sigue siendo `[{"id": int, "nombre": str}]`.
No se añadió ningún campo, precisamente para no arriesgar el parseo en la app.

Un `tipo` desconocido se ignora y se devuelven todos, en vez de dejar el selector vacío.

## Qué hay que hacer en la app

Para que el selector «-- Seleccione Operador --» muestre solo a los operarios de la
línea correspondiente, hay que añadir el parámetro a la llamada que llena ese spinner:

```
GET /api/operadores?tipo={TIPO_DE_LA_MAQUINA}
```

**No hace falta preguntarle nada al operador.** La app ya sabe en qué máquina está, y
`GET /api/maquinas` devuelve el `tipo` de cada una:

```json
[{"id": 1, "nombre": "Máquina 7", "tipo": "SOLIDO", "marcas": [...]}, ...]
```

Así que el flujo es: máquina seleccionada → su `tipo` → pedir los operarios de ese tipo.

## Precauciones

- **La caché offline.** Si la app cachea la lista de operarios, la clave de caché debe
  incluir el tipo; si no, una tablet que estuvo en una máquina sólida podría mostrar esa
  lista en una líquida.
- **Valores válidos:** `SOLIDO` y `LIQUIDO`, en mayúsculas y sin tilde. El servidor
  tolera `Sólido`/`líquido`, pero conviene mandar la forma canónica.
- **Un operario pertenece a una sola línea.** Si alguien tiene que trabajar en ambas,
  hoy hay que cambiarlo de línea desde la web (botón `→ Líquido` / `→ Sólido`). Si esto
  pasa a ser habitual, habría que replantear el modelo para admitir las dos a la vez.
- El nombre de operario sigue siendo **único en toda la tabla**: no puede haber un
  «JUAN PÉREZ» en sólido y otro distinto en líquido.

## Hasta que la app se actualice

El selector sigue mostrando a todos los operarios, como hasta ahora. La clasificación ya
está guardada y visible en la web, así que el cambio de la app se puede hacer cuando
convenga, sin prisa y sin coordinar despliegues.
