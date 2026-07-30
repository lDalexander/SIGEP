import {
  num, pct, duracion, antiguedad, fechaCorta, fechaISO, turnoActual, etiquetaTablet, plural,
} from './format';

describe('num — separador de miles es-EC', () => {
  test.each([
    [1873, '1.873'],
    [1063, '1.063'],
    [1547, '1.547'],
    [300, '300'],
    [0, '0'],           // un cero real es un dato, no un hueco
  ])('num(%p) -> %p', (entrada, salida) => {
    expect(num(entrada)).toBe(salida);
  });

  test.each([null, undefined, '', 'abc'])('num(%p) devuelve el guion largo', (entrada) => {
    expect(num(entrada)).toBe('—');
  });
});

describe('pct — un decimal', () => {
  test.each([
    [28.2, '28.2%'],
    [23.456, '23.5%'],
    [7.1, '7.1%'],
    [0, '0.0%'],
  ])('pct(%p) -> %p', (entrada, salida) => {
    expect(pct(entrada)).toBe(salida);
  });
});

describe('duracion — minutos a "4h 48m"', () => {
  test.each([
    [288, '4h 48m'],
    [249, '4h 09m'],   // los minutos van a dos dígitos cuando hay horas
    [62, '1h 02m'],
    [60, '1h 00m'],
    [48, '48m'],
    [0, '0m'],
  ])('duracion(%p) -> %p', (entrada, salida) => {
    expect(duracion(entrada)).toBe(salida);
  });

  test('sin dato devuelve el guion largo', () => {
    expect(duracion(null)).toBe('—');
    expect(duracion(-5)).toBe('—');
  });
});

describe('antiguedad — escala corta de los chips de tablets', () => {
  test.each([
    [45, '45s'],
    [1860, '31m'],
    [4080, '68m'],       // hasta 2 h se sigue contando en minutos
    [10800, '3h'],
    [1814400, '21d'],
    [5270400, '61d'],
  ])('antiguedad(%p) -> %p', (entrada, salida) => {
    expect(antiguedad(entrada)).toBe(salida);
  });

  test('sin dato devuelve el guion largo', () => {
    expect(antiguedad(null)).toBe('—');
  });
});

describe('turno de planta', () => {
  // Replica la regla de api_produccion/services/turnos.py.
  test.each([
    [7, 'DÍA'], [12, 'DÍA'], [18, 'DÍA'],
    [19, 'NOCHE'], [23, 'NOCHE'], [0, 'NOCHE'], [6, 'NOCHE'],
  ])('a las %p:00 el turno es %p', (horaDelDia, esperado) => {
    const d = new Date(2026, 6, 23, horaDelDia, 30, 0);
    expect(turnoActual(d).codigo).toBe(esperado);
  });

  test('la franja acompaña al código', () => {
    expect(turnoActual(new Date(2026, 6, 23, 12, 0, 0)).franja).toBe('07:00–19:00');
    expect(turnoActual(new Date(2026, 6, 23, 22, 0, 0)).franja).toBe('19:00–07:00');
  });
});

describe('fechas', () => {
  test('fechaCorta da "23 · Jul"', () => {
    expect(fechaCorta(new Date(2026, 6, 23))).toBe('23 · Jul');
  });

  test('fechaISO usa la fecha local, no UTC', () => {
    // Con toISOString(), una hora local temprana caería en el día anterior.
    expect(fechaISO(new Date(2026, 6, 23, 0, 30, 0))).toBe('2026-07-23');
    expect(fechaISO(new Date(2026, 11, 1, 23, 45, 0))).toBe('2026-12-01');
  });
});

describe('etiquetaTablet', () => {
  test('con máquina asignada muestra la máquina', () => {
    expect(etiquetaTablet({ maquina: 'Máquina 16', device_id: 'abcd-1234' }))
      .toBe('Máquina 16');
  });

  test('sin máquina compone TAB- con el inicio del device_id', () => {
    expect(etiquetaTablet({ device_id: '18c1f0aa-0000' })).toBe('TAB-18c1');
  });

  test('sin datos devuelve el guion largo', () => {
    expect(etiquetaTablet({})).toBe('—');
    expect(etiquetaTablet(null)).toBe('—');
  });
});

describe('plural', () => {
  test.each([
    [1, '1 sesión'],
    [2, '2 sesiones'],
    [0, '0 sesiones'],
    [1063, '1.063 sesiones'],  // pluraliza y formatea a la vez
  ])('plural(%p) -> %p', (entrada, salida) => {
    expect(plural(entrada, 'sesión', 'sesiones')).toBe(salida);
  });
});
