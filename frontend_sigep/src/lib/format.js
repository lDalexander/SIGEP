/**
 * Formateo compartido. Todo número que se muestre al usuario pasa por aquí:
 * el locale de la planta es es-EC (separador de miles con punto -> 1.873).
 */

/** 1873 -> "1.873". Devuelve "—" si no hay dato (no "0", que sería un dato falso). */
export function num(v) {
  if (v === null || v === undefined || v === '') return '—';
  const n = Number(v);
  return Number.isFinite(n) ? n.toLocaleString('es-EC') : '—';
}

/** 28.23 -> "28.2%". Un decimal, como en las capturas. */
export function pct(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return '—';
  return `${n.toFixed(1)}%`;
}

/**
 * Duración en minutos -> "48m" / "1h 02m" / "4h 48m".
 * Los minutos van a dos dígitos cuando hay horas delante.
 */
export function duracion(minutos) {
  // Ojo: Number(null) es 0, así que la ausencia de dato se descarta antes de convertir;
  // si no, un `tiempo_transcurrido: null` se mostraría como "0m".
  if (minutos === null || minutos === undefined || minutos === '') return '—';
  const m = Number(minutos);
  if (!Number.isFinite(m) || m < 0) return '—';
  if (m < 60) return `${Math.round(m)}m`;
  const h = Math.floor(m / 60);
  const resto = Math.round(m % 60);
  return `${h}h ${String(resto).padStart(2, '0')}m`;
}

/**
 * Antigüedad de un contacto, en la escala corta de los chips de tablets:
 * "45s" / "31m" / "68m" / "5h" / "21d".
 */
export function antiguedad(segundos) {
  // El backend manda `segundos_desde_heartbeat: null` cuando la tablet nunca ha
  // reportado. Sin este descarte previo, Number(null)=0 lo pintaría como "0s", es
  // decir como si acabara de conectarse.
  if (segundos === null || segundos === undefined || segundos === '') return '—';
  const s = Number(segundos);
  if (!Number.isFinite(s) || s < 0) return '—';
  if (s < 60) return `${s}s`;
  const min = Math.floor(s / 60);
  if (min < 120) return `${min}m`;
  const h = Math.floor(min / 60);
  if (h < 48) return `${h}h`;
  return `${Math.floor(h / 24)}d`;
}

/** Date -> "12:21:31" */
export function hora(d = new Date()) {
  return d.toLocaleTimeString('es-EC', { hour12: false });
}

/** Date -> "23 · Jul" (como en la cabecera de las capturas). */
export function fechaCorta(d = new Date()) {
  const dia = d.getDate();
  const mes = d.toLocaleDateString('es-EC', { month: 'short' }).replace('.', '');
  return `${dia} · ${mes.charAt(0).toUpperCase()}${mes.slice(1)}`;
}

/**
 * "2026-08-01" -> Date local, o null si no es una fecha ISO válida.
 * Se parsea a mano a propósito: `new Date('2026-08-01')` se interpreta como UTC y
 * en Guayaquil (UTC-5) se mostraría como el día anterior.
 */
function fechaDesdeISO(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso ?? '').trim());
  if (!m) return null;
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  return Number.isNaN(d.getTime()) ? null : d;
}

/** "2026-08-01" -> "01 ago". Etiqueta del eje X cuando la serie va por día. */
export function fechaEje(iso) {
  const d = fechaDesdeISO(iso);
  if (!d) return String(iso ?? '—');
  const mes = d.toLocaleDateString('es-EC', { month: 'short' }).replace('.', '');
  return `${String(d.getDate()).padStart(2, '0')} ${mes}`;
}

/** "2026-08-01" -> "sáb 01 ago". Cabecera del tooltip en la serie por día. */
export function fechaLegible(iso) {
  const d = fechaDesdeISO(iso);
  if (!d) return String(iso ?? '—');
  const dia = d.toLocaleDateString('es-EC', { weekday: 'short' }).replace('.', '');
  return `${dia} ${fechaEje(iso)}`;
}

/** Date -> "2026-07-23", el formato que esperan los input[type=date] y la API. */
export function fechaISO(d = new Date()) {
  const mes = String(d.getMonth() + 1).padStart(2, '0');
  const dia = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mes}-${dia}`;
}

/**
 * Turno en curso según la hora local de la planta.
 * Replica la regla de api_produccion/services/turnos.py:
 *   DIA   07:00–18:59
 *   NOCHE 19:00–06:59
 * Se calcula en el cliente porque no hay endpoint que lo exponga.
 */
export function turnoActual(d = new Date()) {
  const h = d.getHours();
  return h >= 7 && h <= 18
    ? { codigo: 'DÍA', franja: '07:00–19:00' }
    : { codigo: 'NOCHE', franja: '19:00–07:00' };
}

/**
 * Etiqueta corta de una tablet. La API expone `device_id` (UUID) y `nombre`, que en
 * realidad guarda el nombre del operario, así que las tablets sin máquina asignada se
 * identifican por los primeros 4 caracteres de su device_id: "TAB-18c1".
 */
export function etiquetaTablet(tablet) {
  if (tablet?.maquina) return tablet.maquina;
  const id = String(tablet?.device_id || '');
  return id ? `TAB-${id.slice(0, 4)}` : '—';
}

/** Pluralización simple: plural(1,'sesión','sesiones') -> "1 sesión". */
export function plural(n, singular, plural_) {
  return `${num(n)} ${Number(n) === 1 ? singular : plural_}`;
}
