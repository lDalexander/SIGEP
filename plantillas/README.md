# Plantillas de importación — Catálogo de insumos y recetas (BOM)

Dos CSV que alimentan la Fase 1 del módulo de insumos. Se abren con Excel
directamente (están en UTF-8 con BOM y separador `;`, que es lo que espera
Excel en Windows en español — con `,` metería todo en una sola columna).

Las filas que traen son **ejemplos reales del catálogo actual**. Bórralas y
pon las tuyas; el encabezado no se toca.

---

## 1. `insumos.csv` — el catálogo

| Columna | Qué va | Obligatorio |
|---|---|---|
| `nombre` | Nombre exacto del insumo. Es la llave: debe ser único y es lo que verá el operario en su lista. | Sí |
| `categoria` | Familia para agrupar (Plástico, Cartón, Etiquetas, Envases, Granel…). Libre. | No |
| `unidad_medida` | Millares, Unidades, Rollos, Kilogramos… | Sí |
| `bodega` | `EMPAQUE` o `GRANEL`. **Esto decide a qué insumista le suena la alarma.** | Sí |
| `activo` | `1` visible, `0` oculto sin borrar el histórico. | Sí |

> La columna `bodega` es la que reemplaza el truco actual de buscar las palabras
> "granel"/"preten"/"polvo" dentro del texto del pedido. Si un insumo queda mal
> marcado aquí, el pedido le llega al insumista equivocado.

---

## 2. `recetas.csv` — el BOM (qué insumos lleva cada producto)

| Columna | Qué va | Obligatorio |
|---|---|---|
| `maquina` | Nombre exacto de la máquina. **Vacío = cualquier máquina.** | No |
| `producto` | DETERGENTE EN POLVO, SUAVIZANTE, CLORO… **Vacío = cualquier producto.** | No |
| `marca` | ULTREX, TORBELLINO, PQP… **Vacío = cualquier marca.** | No |
| `presentacion` | 1 KG, 25 KG, 900 ml… **Vacío = cualquier presentación.** | No |
| `fragancia` | LIMÓN, FLORAL… **Vacío = cualquier fragancia.** | No |
| `insumo` | Tiene que coincidir **exacto** con un `nombre` de `insumos.csv`. | Sí |
| `cantidad_sugerida` | Con cuánto se precarga el pedido del operario. | Sí |

### La regla que ahorra miles de filas: celda vacía = comodín

Con 7 productos × 11 marcas × 36 presentaciones × 9 fragancias, escribir una
fila por combinación sería imposible. Por eso una celda vacía significa
"vale para todas".

```
;DETERGENTE EN POLVO;ULTREX;1 KG;;Funda Plástica ULTREX 1KG;1
```
→ La funda ULTREX 1KG solo aplica a ese producto, marca y presentación,
  en cualquier máquina y cualquier fragancia.

```
;DETERGENTE EN POLVO;;;;Materia Prima Detergente Polvo;1
```
→ Todo el detergente en polvo lleva materia prima, sea la marca que sea.

```
;;;;;Cinta Adhesiva Transparente;1
```
→ La cinta la puede pedir cualquiera, siempre.

```
Máquina 1;SUAVIZANTE;ULTREX;1 LT;;Botella 1 LT;1
```
→ Solo para esa máquina: así se resuelven casos como el de la Máquina 7B,
  que usa sacos tejidos mientras las demás usan lámina en rollo.

**Lo más específico manda.** Si una fila nombra la máquina y otra no, el
operario de esa máquina ve las dos, pero no ve las de otras máquinas.

---

## Valores válidos hoy (si escribes otra cosa, la importación lo rechaza)

- **Productos:** DETERGENTE EN POLVO, CLORO, DETERGENTE LIQUIDO, QUITAMANCHAS,
  SUAVIZANTE, DESINFECTANTE, LAVAVAJILLAS
- **Marcas:** TORBELLINO, ULTREX, COMISARIATO, PQP, HIT, DIAMANT, WISE, EKONO,
  Prolimpio, Lavavajilla diamant
- **Fragancias:** LIMÓN, FLORAL, LAVANDA, FRUTOS ROJOS, BEBE, CITRUS, NEUTRO,
  VAINILLA, MANZANA
- **Presentaciones:** 100 GR, 250 GR, 360 GR, 500 GR, 1 KG, 1.2 KG, 2 KG, 3 KG,
  5 KG, 15 KG, 25 KG, 1 LT, 500 ml, 2LT duopack, 3.9 LT Onpack, 2.9 LT, 3 LT,
  425 ml, 350 ml, 4 Lt, 3.8 Lt, 20 Lt, 900 ml, 150 ml, 530 ml, 1.9 Lt, 360 ml,
  400 ml, 450 ml, 650 ml, 20LT

La importación corre en seco primero: te dice qué filas tienen problemas
**antes** de escribir nada en la base.
