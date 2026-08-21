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

/**
 * Velocidad de máquina -> "45,5" (unidades/min). Un decimal como máximo.
 *
 * Se dice UNIDADES y no «fundas» en toda la web: la línea líquida no llena fundas, y el
 * mismo número va a describir las dos líneas.
 *
 * Sin lectura devuelve «—» y NO cero: una máquina sin velocidad registrada no va a cero
 * unidades/min, es que nadie la anotó. Mismo criterio que el resto de `format`, y aquí
 * importa más porque el cero sería una cifra creíble.
 */
export function velocidad(v) {
  if (v === null || v === undefined || v === '') return '—';
  const n = Number(v);
  if (!Number.isFinite(n)) return '—';
  return n.toLocaleString('es-EC', { maximumFractionDigits: 1 });
}

/**
 * Número utilizable, o null. `Number(null)` y `Number('')` son 0, así que los huecos hay
 * que descartarlos ANTES de convertir: es el mismo cuidado que `num()`, y aquí un cero
 * colado convertiría «sin estándar» en «meta 0 – 0» y marcaría toda la planta en rojo.
 */
function cifra(v) {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

/**
 * Rango estándar de velocidad -> "32 – 38". Sin los dos extremos devuelve null.
 *
 * Devuelve NULL y no «—» a propósito, al revés que el resto de este módulo: quien llama
 * decide si pintar algo. Un «—» donde debería ir el rango se leería como «esta máquina
 * no cumple», y lo que pasa de verdad es que nadie le ha configurado el estándar.
 */
export function rangoVelocidad(min, max) {
  const a = cifra(min);
  const b = cifra(max);
  if (a === null || b === null) return null;
  return `${velocidad(a)} – ${velocidad(b)}`;
}

/**
 * Compara una lectura con su rango estándar -> 'bajo' | 'dentro' | 'sobre' | null.
 *
 * `null` cuando falta la lectura o falta el estándar, que son dos huecos distintos pero
 * llevan a la misma conclusión: no hay nada que comparar, así que no se juzga.
 *
 * Los extremos CUENTAN COMO DENTRO: el rango lo teclea una persona pensando «de 32 a 38»,
 * y marcar en rojo un turno que fue exactamente a 32 contradiría lo que quiso decir.
 */
export function estadoVelocidad(valor, min, max) {
  const v = cifra(valor);
  const a = cifra(min);
  const b = cifra(max);
  if (v === null || a === null || b === null) return null;
  if (v < a) return 'bajo';
  if (v > b) return 'sobre';
  return 'dentro';
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
 * Duración en SEGUNDOS -> "45s" / "12m 30s" / "1h 50m".
 * Los paros llegan en segundos con decimales (`duracion_segundos`), y los cortos
 * —un atasco de 20 s— tienen que poder leerse, así que por debajo de la hora se
 * muestran los segundos.
 */
export function duracionSeg(segundos) {
  // Mismo cuidado que en `duracion`: Number(null) es 0 y pintaría un paro de «0s».
  if (segundos === null || segundos === undefined || segundos === '') return '—';
  const bruto = Number(segundos);
  if (!Number.isFinite(bruto) || bruto < 0) return '—';
  const s = Math.round(bruto);
  if (s < 60) return `${s}s`;
  if (s < 3600) {
    const m = Math.floor(s / 60);
    const resto = s % 60;
    return resto ? `${m}m ${String(resto).padStart(2, '0')}s` : `${m}m`;
  }
  const h = Math.floor(s / 3600);
  const m = Math.round((s % 3600) / 60);
  // 1h 60m no existe: el redondeo al alza sube la hora.
  return m === 60 ? `${h + 1}h 00m` : `${h}h ${String(m).padStart(2, '0')}m`;
}

/**
 * "2026-08-04 19:22:39" -> "19:22" (o "19:22:39" con segundos).
 * Se corta el texto en lugar de parsear a Date: el backend ya manda la hora local
 * de la planta, y convertirla a Date y volver a formatearla solo añade riesgo de
 * desplazarla una zona horaria.
 */
export function horaDeTexto(ts, conSegundos = false) {
  const m = /(\d{2}):(\d{2})(?::(\d{2}))?/.exec(String(ts ?? ''));
  if (!m) return '—';
  return conSegundos && m[3] ? `${m[1]}:${m[2]}:${m[3]}` : `${m[1]}:${m[2]}`;
}

/** "2026-08-04 19:22:39" -> "04 ago" (usa el mismo formato que el eje del gráfico). */
export function fechaDeTexto(ts) {
  const m = /^(\d{4}-\d{2}-\d{2})/.exec(String(ts ?? '').trim());
  return m ? fechaEje(m[1]) : '—';
}

/**
 * "2026-08-04 19:22:39" -> Date en hora local, o null.
 * Necesario para el cronómetro de los paros en curso; se construye componente a
 * componente porque `new Date('2026-08-04 19:22:39')` no está garantizado por el
 * estándar y en algunos motores se lee como UTC.
 */
export function fechaHoraDesdeTexto(ts) {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?/.exec(String(ts ?? '').trim());
  if (!m) return null;
  const d = new Date(
    Number(m[1]), Number(m[2]) - 1, Number(m[3]),
    Number(m[4]), Number(m[5]), Number(m[6] || 0)
  );
  return Number.isNaN(d.getTime()) ? null : d;
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
