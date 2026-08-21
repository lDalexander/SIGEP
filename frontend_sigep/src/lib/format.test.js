import {
  num, pct, duracion, antiguedad, fechaCorta, fechaISO, turnoActual, etiquetaTablet, plural,
  fechaEje, fechaLegible, velocidad, rangoVelocidad, estadoVelocidad,
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

describe('velocidad — unidades/min, un decimal', () => {
  test.each([
    [45.5, '45,5'],
    [45, '45'],
    [43.266, '43,3'],   // se redondea, no se enseña la precisión falsa del promedio
    [120, '120'],
  ])('velocidad(%p) -> %p', (entrada, salida) => {
    expect(velocidad(entrada)).toBe(salida);
  });

  /* Lo importante de este bloque: sin lectura NO sale un cero. Un «0 unidades/min» es una
     cifra creíble —una máquina detenida— y aquí significaría solo que nadie la anotó. */
  test.each([null, undefined, '', 'abc'])('velocidad(%p) no inventa un cero', (entrada) => {
    expect(velocidad(entrada)).toBe('—');
  });

  test('un cero registrado de verdad sí se enseña', () => {
    expect(velocidad(0)).toBe('0');
  });
});

/* Velocidad estándar por (máquina, presentación) — 2026-08-21.
   Estas dos funciones son las que convierten una lectura suelta en «va bien» o «va mal»,
   así que lo que se protege aquí es que NO juzguen cuando no hay con qué. */
describe('rangoVelocidad — el par mínimo/máximo', () => {
  test('formatea los dos extremos en es-EC', () => {
    expect(rangoVelocidad(32, 38)).toBe('32 – 38');
    expect(rangoVelocidad(28.5, 32.25)).toBe('28,5 – 32,3');
  });

  /* Devuelve null y no «—», al revés que el resto del módulo: un guion donde debería ir
     el rango se leería como «esta máquina no cumple», y lo que pasa es que nadie le ha
     configurado el estándar. Quien llama decide no pintar nada. */
  test.each([
    [null, null], [32, null], [null, 38], [undefined, undefined], ['', ''], ['abc', 38],
  ])('rangoVelocidad(%p, %p) no pinta nada', (min, max) => {
    expect(rangoVelocidad(min, max)).toBeNull();
  });
});

describe('estadoVelocidad — comparar la lectura con su meta', () => {
  test.each([
    [30, 'bajo'],
    [45, 'sobre'],
    [35, 'dentro'],
    [32, 'dentro'],     // los extremos cuentan como dentro: el rango lo escribió alguien
    [38, 'dentro'],     // pensando «de 32 a 38», y un 38 exacto no es un incumplimiento
  ])('una lectura de %p contra 32–38 es %p', (valor, esperado) => {
    expect(estadoVelocidad(valor, 32, 38)).toBe(esperado);
  });

  /* Sin lectura o sin estándar no hay nada que comparar. Son dos huecos distintos pero
     llevan a lo mismo: no se juzga, y la UI se limita a enseñar lo que hay. */
  test.each([
    [null, 32, 38], [undefined, 32, 38], ['', 32, 38], ['abc', 32, 38],
    [35, null, null], [35, 32, null], [35, null, 38],
  ])('estadoVelocidad(%p, %p, %p) no concluye nada', (valor, min, max) => {
    expect(estadoVelocidad(valor, min, max)).toBeNull();
  });

  test('un cero registrado de verdad sí se juzga', () => {
    expect(estadoVelocidad(0, 32, 38)).toBe('bajo');
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

describe('fechas ISO del eje por día', () => {
  test('fechaEje formatea sin desfase de zona horaria', () => {
    // new Date('2026-08-01') sería UTC y en Guayaquil (UTC-5) daría "31 jul".
    expect(fechaEje('2026-08-01')).toBe('01 ago');
    expect(fechaEje('2026-12-25')).toBe('25 dic');
  });

  test('fechaLegible antepone el día de la semana', () => {
    expect(fechaLegible('2026-08-01')).toMatch(/^\S+ 01 ago$/);
  });

  test('un valor que no es fecha se devuelve tal cual, sin inventar', () => {
    expect(fechaEje('08:00')).toBe('08:00');
    expect(fechaEje(null)).toBe('—');
  });
});
