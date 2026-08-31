import React, { useState } from 'react';
import { X } from 'lucide-react';
import { Card, Badge, Button, Dot, Estado, Input, Select, Aviso, useAviso, Label } from '../ui';
import { plural, num } from '../../lib/format';
import useApi from '../../lib/useApi';
import { admin, mensajeDeError } from '../../lib/adminApi';

const TIPOS = ['Sólido', 'Líquido'];

/* Los mismos límites que acepta POST /api/velocidad, que es la lectura contra la que se
   compara: un estándar fuera de ellos no lo cumpliría ninguna lectura posible. */
const VEL_MIN = 0.1;
const VEL_MAX = 1000;

/** Lo que se teclea son unidades/min; esto es el equivalente por hora, para reconocerlo.
 *
 *  La casilla vacía se descarta ANTES de convertir: `Number('')` es 0, y con él una
 *  presentación sin configurar mostraría «≈ 0 – 0 /hora» como si tuviera un rango puesto.
 */
function porHora(min, max) {
  if (min === '' || max === '' || min === null || max === null) return null;
  const a = Number(min);
  const b = Number(max);
  if (!Number.isFinite(a) || !Number.isFinite(b)) return null;
  return `${num(Math.round(a * 60))} – ${num(Math.round(b * 60))}`;
}

/** El backend normaliza a SOLIDO/LIQUIDO; aquí se muestra con tilde. */
function tipoBonito(tipo) {
  return String(tipo || '').toUpperCase() === 'LIQUIDO' ? 'Líquido' : 'Sólido';
}
function tipoContrario(tipo) {
  return String(tipo || '').toUpperCase() === 'LIQUIDO' ? 'Sólido' : 'Líquido';
}

/** Un producto vacío es comodín —vale para cualquiera—, no un hueco sin rellenar. */
const SIN_PRODUCTO = 'Sin producto · vale para cualquiera';

/**
 * Nombra una combinación entera, con su producto delante.
 *
 * Hace falta porque la misma marca y presentación puede existir DOS veces en la misma
 * máquina con productos distintos: la Máquina 3 tiene COMISARIATO · 2.9 LT como
 * SUAVIZANTE y como DETERGENTE LIQUIDO. Sin el producto, «¿Eliminar COMISARIATO ·
 * 2.9 LT?» no dice cuál de las dos se está borrando.
 */
function etiquetaCombo(p) {
  return p.producto
    ? `${p.producto} · ${p.marca} · ${p.presentacion}`
    : `${p.marca} · ${p.presentacion}`;
}

/** Nombre legible de un producto en un rótulo, con el comodín dicho y no callado. */
function etiquetaProducto(producto) {
  return producto || SIN_PRODUCTO;
}

/**
 * Agrupa las combinaciones de una máquina en producto → marca → presentaciones, con el
 * comodín al final.
 *
 * La lista plana miente en cuanto una máquina hace dos productos: las filas repetidas
 * de marca + presentación se leían como duplicados y el producto quedaba escondido en
 * un desplegable al otro extremo de la fila. El producto es justo lo que las distingue,
 * así que es él quien manda la estructura.
 *
 * La marca es el segundo nivel porque es de quien cuelgan las fragancias
 * (`maquina_marca_fragancias` va por máquina + marca), y así cada una las enseña una
 * sola vez en lugar de repetirlas en cada presentación.
 *
 * El grupo sin producto va el último a propósito: es lo que falta por clasificar, no
 * una categoría más.
 */
function agruparPorProducto(filas) {
  const porProducto = new Map();
  filas.forEach((f) => {
    const clave = f.producto || '';
    if (!porProducto.has(clave)) porProducto.set(clave, new Map());
    const marcas = porProducto.get(clave);
    if (!marcas.has(f.marca)) marcas.set(f.marca, []);
    marcas.get(f.marca).push(f);
  });
  return [...porProducto.entries()]
    .map(([producto, marcas]) => ({
      producto,
      total: [...marcas.values()].reduce((n, items) => n + items.length, 0),
      marcas: [...marcas.entries()]
        .map(([marca, items]) => ({ marca, items }))
        .sort((a, b) => a.marca.localeCompare(b.marca, 'es')),
    }))
    .sort((a, b) => {
      if (!a.producto) return 1;
      if (!b.producto) return -1;
      return a.producto.localeCompare(b.producto, 'es');
    });
}

/**
 * Marca → los productos en los que esa marca aparece en esta máquina.
 *
 * Hace falta para avisar de algo que si no se lee al revés: las fragancias cuelgan de
 * (máquina, marca) y **no** del producto, así que COMISARIATO tiene las mismas en
 * SUAVIZANTE que en DETERGENTE LIQUIDO. Sin el aviso, editarlas bajo un producto
 * parecería tocar solo ese, y las cambia en todos.
 */
function productosDeCadaMarca(filas) {
  const idx = new Map();
  filas.forEach((f) => {
    if (!idx.has(f.marca)) idx.set(f.marca, new Set());
    idx.get(f.marca).add(f.producto || '');
  });
  return idx;
}

/**
 * Pestaña «Jerarquía» — qué produce cada máquina: marca + presentación, y las
 * fragancias que hace de cada marca.
 *
 * Es la fuente de verdad que consume la app Android para filtrar los selectores al
 * iniciar turno, y la cachea offline; editar aquí se refleja en las tablets al
 * reconectar, sin reinstalar la app.
 *
 * Son TRES jerarquías con granularidad distinta, y no es un descuido:
 * `maquina_productos` va por (máquina, marca, presentación), `maquina_marca_fragancias`
 * por (máquina, marca) —la fragancia no depende del gramaje, ULTREX 1 KG y ULTREX 3 KG de
 * la misma máquina llevan las mismas— y `maquina_presentacion_velocidad` por (máquina,
 * presentación), que es justo la otra mitad: el ritmo lo marca el gramaje y la máquina que
 * lo llena, no la etiqueta que lleve encima.
 *
 * La velocidad estándar, además, NO viaja a las tablets: la app manda su lectura y esta
 * web la interpreta. Cambiar un rango aquí no cambia nada de lo que ve el operario.
 *
 * «Eliminar» hace baja lógica (`PUT {activo:false}`) en lugar del `DELETE` físico,
 * para no romper el histórico.
 */
export default function TabJerarquia() {
  const matriz = useApi('/maquina_productos', { cliente: admin });
  const fragMatriz = useApi('/maquina_fragancias', { cliente: admin });
  const velMatriz = useApi('/velocidad_estandar', { cliente: admin });
  const catalogos = useApi('/catalogos', { cliente: admin });
  const { aviso, ok, fallo } = useAviso();

  const [nuevaMaquina, setNuevaMaquina] = useState('');
  const [tipoMaquina, setTipoMaquina] = useState('Sólido');
  const [nuevaMarca, setNuevaMarca] = useState('');
  const [nuevaPresentacion, setNuevaPresentacion] = useState('');
  const [nuevaFragancia, setNuevaFragancia] = useState('');
  const [nuevoProducto, setNuevoProducto] = useState('');
  const [tipoProducto, setTipoProducto] = useState('Líquido');

  /* Combinación en preparación, por máquina: { [maquina_id]: {marca, presentacion} } */
  const [combo, setCombo] = useState({});
  /* Fragancia en preparación, por máquina+marca: { "3|ULTREX": "Floral" } */
  const [fragElegida, setFragElegida] = useState({});
  /* Rango en edición, por máquina+presentación: { "1|1 KG": {min:'32', max:'38'} }.
     Se guarda como TEXTO y no como número: es lo que hay en el input, y convertirlo en
     cada tecla haría que borrar el último dígito reescribiera un 0 en la casilla. */
  const [velEditada, setVelEditada] = useState({});
  /* Fila cuyo producto se está reasignando, por id. Vive fuera de la fila para que solo
     una esté abierta a la vez: dos desplegables abiertos en la misma tarjeta invitan a
     mover la que no era. */
  const [moviendo, setMoviendo] = useState(null);

  const maquinas = Array.isArray(matriz.datos) ? matriz.datos : [];
  const marcas = catalogos.datos?.marcas || [];
  const presentaciones = catalogos.datos?.presentaciones || [];
  const fragancias = catalogos.datos?.fragancias || [];
  /* [{nombre, tipo}] — el producto entro en la jerarquia el 2026-08-27. */
  const productos = catalogos.datos?.productos || [];

  /* maquina_id -> marcas[] con sus fragancias, tal como lo devuelve la API. */
  const fragPorMaquina = {};
  (Array.isArray(fragMatriz.datos) ? fragMatriz.datos : []).forEach((m) => {
    fragPorMaquina[m.maquina_id] = m.marcas || [];
  });

  /* maquina_id -> presentaciones[] con su estándar de velocidad, tal como lo da la API. */
  const velPorMaquina = {};
  (Array.isArray(velMatriz.datos) ? velMatriz.datos : []).forEach((m) => {
    velPorMaquina[m.maquina_id] = m.presentaciones || [];
  });

  /* Productos de una linea, solo nombres, para los Select. */
  const productosDeLinea = (tipo) => {
    const canon = (tipo || '').toUpperCase().startsWith('L') ? 'LIQUIDO' : 'SOLIDO';
    return productos.filter((p) => p.tipo === canon).map((p) => p.nombre);
  };

  const recargarTodo = () => {
    matriz.recargar();
    fragMatriz.recargar();
    velMatriz.recargar();
    catalogos.recargar();
  };

  /* ── Catálogos maestros ─────────────────────────────── */
  const crear = async (ruta, cuerpo, etiqueta, limpiar) => {
    try {
      const { data } = await admin.post(ruta, cuerpo);
      ok(data.reactivado ? `${etiqueta} reactivada` : `${etiqueta} agregada`);
      limpiar();
      recargarTodo();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  /* ── Máquinas ───────────────────────────────────────── */
  const alternarTipo = async (maquina) => {
    try {
      await admin.put(`/maquinas/${maquina.maquina_id}`, { tipo: tipoContrario(maquina.tipo) });
      ok(`${maquina.maquina} pasa a ${tipoContrario(maquina.tipo)}`);
      recargarTodo();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  const cambiarMaquinaActiva = async (maquina, activa) => {
    if (!activa && !window.confirm(
      `¿Desactivar ${maquina.maquina}?\n\nDejará de aparecer en las tablets al iniciar turno.`
    )) return;
    try {
      await admin.put(`/maquinas/${maquina.maquina_id}`, { activa });
      ok(`${maquina.maquina} ${activa ? 'reactivada' : 'desactivada'}`);
      recargarTodo();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  /* ── Combinaciones marca + presentación ─────────────── */
  const agregarCombinacion = async (maquina) => {
    const eleccion = combo[maquina.maquina_id] || {};
    if (!eleccion.marca || !eleccion.presentacion) {
      fallo('Elige marca y presentación');
      return;
    }
    try {
      await admin.post('/maquina_productos', {
        maquina_id: maquina.maquina_id,
        // Vacio = comodin: la combinacion vale para cualquier producto. Es lo que
        // deja la jerarquia usable mientras no se clasifica del todo.
        producto: eleccion.producto || '',
        marca: eleccion.marca,
        presentacion: eleccion.presentacion,
      });
      ok(`${eleccion.marca} · ${eleccion.presentacion} añadida a ${maquina.maquina}`);
      setCombo((prev) => ({ ...prev, [maquina.maquina_id]: {} }));
      matriz.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  const cambiarComboActivo = async (producto, activo, confirmar) => {
    if (confirmar && !window.confirm(confirmar)) return;
    try {
      await admin.put(`/maquina_productos/${producto.id}`, { activo });
      ok(`${etiquetaCombo(producto)} ${activo ? 'reactivada' : 'desactivada'}`);
      matriz.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  /* Reasigna el producto de una combinacion. Cadena vacia la devuelve a comodin. */
  const cambiarComboProducto = async (fila, producto) => {
    try {
      await admin.put(`/maquina_productos/${fila.id}`, { producto });
      ok(
        producto
          ? `${etiquetaCombo(fila)} → ${producto}`
          : `${etiquetaCombo(fila)} vale para cualquier producto`
      );
      matriz.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  /* ── Fragancias por máquina + marca ─────────────────── */
  const agregarFragancia = async (maquina, marca) => {
    const clave = `${maquina.maquina_id}|${marca}`;
    const fragancia = fragElegida[clave];
    if (!fragancia) {
      fallo(`Elige una fragancia para ${marca}`);
      return;
    }
    try {
      await admin.post('/maquina_fragancias', {
        maquina_id: maquina.maquina_id,
        marca,
        fragancia,
      });
      ok(`${fragancia} añadida a ${marca} en ${maquina.maquina}`);
      setFragElegida((prev) => ({ ...prev, [clave]: '' }));
      fragMatriz.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  /* Quitar es baja lógica, igual que en las combinaciones: el histórico de sesiones
     se cruza con la fragancia por texto y un borrado físico lo dejaría colgando. */
  const cambiarFraganciaActiva = async (fila, activo, contexto) => {
    try {
      await admin.put(`/maquina_fragancias/${fila.id}`, { activo });
      ok(`${fila.fragancia} ${activo ? 'reactivada' : 'quitada'} en ${contexto}`);
      fragMatriz.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  /* ── Velocidad estándar por máquina + presentación ──── */
  /* Lo que se ve en la casilla: lo tecleado si hay algo, y si no lo que está guardado.
     Se distingue con `=== undefined` y no con `||`: un 0 tecleado es un valor, y con
     `||` volvería a pintar el guardado en cuanto se borra el campo. */
  const valorVel = (clave, item, campo) => {
    const tocado = velEditada[clave]?.[campo];
    if (tocado !== undefined) return tocado;
    const guardado = item.estandar?.[campo === 'min' ? 'vel_min' : 'vel_max'];
    return guardado === null || guardado === undefined ? '' : String(guardado);
  };

  const escribirVel = (clave, campo, valor) =>
    setVelEditada((prev) => ({ ...prev, [clave]: { ...prev[clave], [campo]: valor } }));

  /* El backend valida lo mismo; esto es para no gastar un viaje y para que el aviso salga
     al lado de lo que se acaba de teclear. */
  const errorDelRango = (min, max) => {
    const a = Number(min);
    const b = Number(max);
    if (min === '' || max === '' || !Number.isFinite(a) || !Number.isFinite(b)) {
      return 'Escribe el mínimo y el máximo';
    }
    if (a < VEL_MIN || a > VEL_MAX || b < VEL_MIN || b > VEL_MAX) {
      return `La velocidad debe estar entre ${VEL_MIN} y ${VEL_MAX} unidades/min`;
    }
    if (a > b) return 'El mínimo no puede ser mayor que el máximo';
    return null;
  };

  const guardarVelocidad = async (maquina, item) => {
    const clave = `${maquina.maquina_id}|${item.presentacion}`;
    const min = valorVel(clave, item, 'min');
    const max = valorVel(clave, item, 'max');
    const problema = errorDelRango(min, max);
    if (problema) {
      fallo(`${item.presentacion}: ${problema}`);
      return;
    }
    try {
      /* Con fila existente se corrige (PUT, que también la reactiva si estaba de baja) y
         sin ella se crea (POST). El PUT lleva `activo: true` a propósito: guardar un
         rango en una presentación dada de baja es decir que vuelve a aplicar. */
      if (item.estandar) {
        await admin.put(`/velocidad_estandar/${item.estandar.id}`, {
          vel_min: Number(min), vel_max: Number(max), activo: true,
        });
      } else {
        await admin.post('/velocidad_estandar', {
          maquina_id: maquina.maquina_id,
          presentacion: item.presentacion,
          vel_min: Number(min),
          vel_max: Number(max),
        });
      }
      ok(`${item.presentacion} en ${maquina.maquina}: ${min}–${max} unidades/min`);
      setVelEditada((prev) => ({ ...prev, [clave]: undefined }));
      velMatriz.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  /* Baja lógica, como todo lo demás de esta pantalla: la fila se conserva para poder
     reactivarla, y los turnos ya leídos con ese rango no pierden su referencia. */
  const quitarVelocidad = async (maquina, item) => {
    if (!window.confirm(
      `¿Quitar la velocidad estándar de ${item.presentacion} en ${maquina.maquina}?\n\n` +
      'Las lecturas de la tablet se seguirán registrando, pero dejarán de compararse ' +
      'con un rango en el dashboard.'
    )) return;
    try {
      await admin.put(`/velocidad_estandar/${item.estandar.id}`, { activo: false });
      ok(`Velocidad estándar quitada de ${item.presentacion} en ${maquina.maquina}`);
      setVelEditada((prev) => ({ ...prev, [`${maquina.maquina_id}|${item.presentacion}`]: undefined }));
      velMatriz.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  return (
    <div>
      <div className="flex items-start justify-between gap-4 mb-5">
        <Label caja="normal" className="text-sig-dim">
          Qué marca, presentación y fragancia produce cada máquina —se sincroniza con las
          tablets al reconectar— y a qué velocidad debería trabajar, que solo se usa en la
          web
        </Label>
        <Button onClick={recargarTodo} className="shrink-0">Recargar</Button>
      </div>

      <Aviso aviso={aviso} className="mb-4" />

      <div className="space-y-5">
        <Card titulo="Gestionar catálogos" meta="crea nuevas máquinas, marcas y presentaciones">
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-3">
              <Input
                value={nuevaMaquina}
                onChange={(e) => setNuevaMaquina(e.target.value)}
                placeholder="Nueva máquina (ej. Máquina 20)"
                aria-label="Nueva máquina"
                className="flex-1 min-w-[220px]"
              />
              <Select
                value={tipoMaquina}
                onChange={(e) => setTipoMaquina(e.target.value)}
                opciones={TIPOS}
                vacio={null} /* obligatorio: sin opción vacía */
                aria-label="Tipo de línea"
                className="w-[130px]"
              />
              <Button
                variante="primary"
                className="px-5 py-2.5"
                onClick={() =>
                  crear('/maquinas', { nombre: nuevaMaquina.trim(), tipo: tipoMaquina }, 'Máquina',
                    () => setNuevaMaquina(''))
                }
              >
                Agregar máquina
              </Button>
            </div>

            <div className="flex flex-wrap items-center gap-3">
              <Input
                value={nuevaMarca}
                onChange={(e) => setNuevaMarca(e.target.value)}
                placeholder="Nueva marca (ej. ARIEL)"
                aria-label="Nueva marca"
                className="flex-1 min-w-[220px]"
              />
              <Button
                variante="primary"
                className="px-5 py-2.5"
                onClick={() =>
                  crear('/marcas', { nombre: nuevaMarca.trim() }, 'Marca', () => setNuevaMarca(''))
                }
              >
                Agregar marca
              </Button>
            </div>

            <div className="flex flex-wrap items-center gap-3">
              <Input
                value={nuevaPresentacion}
                onChange={(e) => setNuevaPresentacion(e.target.value)}
                placeholder="Nueva presentación (ej. 750 GR)"
                aria-label="Nueva presentación"
                className="flex-1 min-w-[220px]"
              />
              <Button
                variante="primary"
                className="px-5 py-2.5"
                onClick={() =>
                  crear('/presentaciones', { nombre: nuevaPresentacion.trim() }, 'Presentación',
                    () => setNuevaPresentacion(''))
                }
              >
                Agregar presentación
              </Button>
            </div>

            <div className="flex flex-wrap items-center gap-3">
              <Input
                value={nuevaFragancia}
                onChange={(e) => setNuevaFragancia(e.target.value)}
                placeholder="Nueva fragancia (ej. Lavanda)"
                aria-label="Nueva fragancia"
                className="flex-1 min-w-[220px]"
              />
              <Button
                variante="primary"
                className="px-5 py-2.5"
                onClick={() =>
                  crear('/fragancias', { nombre: nuevaFragancia.trim() }, 'Fragancia',
                    () => setNuevaFragancia(''))
                }
              >
                Agregar fragancia
              </Button>
            </div>

            {/* Productos: SOLIDO solo hace detergente en polvo, LIQUIDO hace varios
                y el operario elige cual al abrir turno. */}
            <div className="flex flex-wrap items-center gap-3">
              <Input
                value={nuevoProducto}
                onChange={(e) => setNuevoProducto(e.target.value)}
                placeholder="Nuevo producto (ej. DESINFECTANTE)"
                aria-label="Nuevo producto"
                className="flex-1 min-w-[220px]"
              />
              <Select
                value={tipoProducto}
                onChange={(e) => setTipoProducto(e.target.value)}
                opciones={['Sólido', 'Líquido']}
                aria-label="Línea del producto"
                className="w-[150px]"
              />
              <Button
                variante="primary"
                className="px-5 py-2.5"
                onClick={() =>
                  crear('/productos', { nombre: nuevoProducto.trim(), tipo: tipoProducto },
                    'Producto', () => setNuevoProducto(''))
                }
              >
                Agregar producto
              </Button>
            </div>
          </div>
        </Card>

        {maquinas.length === 0 ? (
          <Card>
            <Estado
              cargando={matriz.cargando}
              error={matriz.error}
              vacio="Sin máquinas registradas"
            />
          </Card>
        ) : (
          maquinas.map((maquina) => {
            const eleccion = combo[maquina.maquina_id] || {};
            const grupos = agruparPorProducto(maquina.productos);
            /* La cabecera de PRODUCTO solo aparece cuando hay más de uno: con uno solo
               no distinguiría nada y sería ruido en cada máquina SÓLIDO, que todas hacen
               DETERGENTE EN POLVO y nada más. Por marca, en cambio, se agrupa siempre:
               es de quien cuelgan las fragancias. */
            const agrupar = grupos.length > 1;
            const marcaEnProductos = productosDeCadaMarca(maquina.productos);

            /* marca -> lo que la API sabe de sus fragancias en esta máquina. */
            const fragDeMarca = {};
            (fragPorMaquina[maquina.maquina_id] || []).forEach((mf) => {
              fragDeMarca[mf.marca] = mf;
            });
            /* Marcas con fragancias configuradas que ya no producen nada aquí: se
               quedaron sin sitio en la jerarquía, pero sus fragancias siguen vivas y
               tienen que poder verse y quitarse. */
            const huerfanas = (fragPorMaquina[maquina.maquina_id] || []).filter(
              (mf) => !marcaEnProductos.has(mf.marca),
            );

            /* Chips de fragancia de una marca. Se usa dentro de la jerarquía y en el
               bloque de las huérfanas, para que los dos no puedan divergir. */
            const fraganciasDe = (marca) => {
              const mf = fragDeMarca[marca];
              const clave = `${maquina.maquina_id}|${marca}`;
              const contexto = `${marca} · ${maquina.maquina}`;
              const todas = mf ? mf.fragancias : [];
              const activas = todas.filter((f) => f.activo);
              const inactivas = todas.filter((f) => !f.activo);
              return (
                <>
                  {activas.map((f) => (
                    <button
                      key={f.id}
                      type="button"
                      onClick={() => cambiarFraganciaActiva(f, false, contexto)}
                      aria-label={`Quitar ${f.fragancia} de ${contexto}`}
                      title={`Quitar ${f.fragancia} de ${marca} en ${maquina.maquina}`}
                      className="inline-flex items-center gap-1.5 rounded-full border
                                 border-sig-amber/25 bg-sig-amber/[0.12] px-2 py-[3px]
                                 font-mono text-[10px] uppercase tracking-label leading-none
                                 text-sig-amber transition-colors hover:bg-sig-amber/20"
                    >
                      {f.fragancia}
                      <X size={10} aria-hidden="true" />
                    </button>
                  ))}

                  {/* Sin ninguna activa la API cae al catálogo completo, así que la
                      tablet las ofrece TODAS. Decirlo evita leer el hueco como «esta
                      marca no lleva fragancia». */}
                  {activas.length === 0 && (
                    <Label caja="normal" className="text-sig-dim">
                      sin configurar · se ofrecen todas
                    </Label>
                  )}

                  {inactivas.map((f) => (
                    <button
                      key={f.id}
                      type="button"
                      onClick={() => cambiarFraganciaActiva(f, true, contexto)}
                      aria-label={`Reactivar ${f.fragancia} en ${contexto}`}
                      title={`Quitada. Pulsa para volver a ofrecer ${f.fragancia} en ${marca}`}
                      className="inline-flex items-center gap-1.5 rounded-full border
                                 border-sig-line bg-white/[0.04] px-2 py-[3px] font-mono
                                 text-[10px] uppercase tracking-label leading-none
                                 text-sig-dim line-through transition-colors
                                 hover:text-sig-text"
                    >
                      {f.fragancia}
                    </button>
                  ))}

                  <div className="ml-auto flex items-center gap-2">
                    <Select
                      value={fragElegida[clave] || ''}
                      onChange={(e) =>
                        setFragElegida((prev) => ({ ...prev, [clave]: e.target.value }))
                      }
                      opciones={fragancias.filter(
                        (nombre) => !activas.some((f) => f.fragancia === nombre),
                      )}
                      vacio="Fragancia…"
                      aria-label={`Fragancia para ${marca} en ${maquina.maquina}`}
                      className="w-[160px]"
                    />
                    <Button tamano="sm" onClick={() => agregarFragancia(maquina, marca)}>
                      Añadir
                    </Button>
                  </div>
                </>
              );
            };

            /* Una fila por presentación. La marca ya la dice su bloque, así que aquí
               solo va el gramaje: repetirla en cada línea era justo lo que hacía
               ilegible la lista. */
            const filaCombo = (p) => (
              <li
                key={p.id}
                /* La fila se nombra entera, con producto y marca: en pantalla los dicen
                   las cabeceras, pero quien la escucha oye la fila suelta y dos
                   presentaciones iguales sonarían idénticas. */
                aria-label={etiquetaCombo(p)}
                className="flex items-center gap-3 py-2.5 pl-11 pr-5"
              >
                <Dot tono={p.activo ? 'ok' : 'off'} />
                <span className="flex-1 min-w-0 truncate text-[13px] font-bold text-sig-text">
                  {p.presentacion}
                </span>

                {/* Reasignar el producto es excepcional —se hace al clasificar— así que
                    el desplegable aparece solo al pedirlo. Fijo en cada fila repetía el
                    nombre del producto tantas veces como presentaciones tuviera el
                    grupo, que es lo que ya dice la cabecera.
                    Solo en LIQUIDO: en SOLIDO es siempre detergente en polvo. */}
                {(maquina.tipo || '').toUpperCase().startsWith('L') &&
                  (moviendo === p.id ? (
                    <Select
                      value={p.producto || ''}
                      onChange={(e) => {
                        cambiarComboProducto(p, e.target.value);
                        setMoviendo(null);
                      }}
                      opciones={productosDeLinea(maquina.tipo)}
                      vacio="cualquier producto"
                      aria-label={`Mover ${etiquetaCombo(p)} a otro producto`}
                      className="w-[185px]"
                    />
                  ) : (
                    <Button
                      tamano="sm"
                      onClick={() => setMoviendo(p.id)}
                      title={`Mover ${etiquetaCombo(p)} a otro producto`}
                    >
                      Mover
                    </Button>
                  ))}

                {p.activo ? (
                  <>
                    <Button tamano="sm" onClick={() => cambiarComboActivo(p, false)}>
                      Desactivar
                    </Button>
                    <Button
                      tamano="sm"
                      onClick={() =>
                        cambiarComboActivo(
                          p,
                          false,
                          `¿Eliminar ${etiquetaCombo(p)} de ${maquina.maquina}?\n\nSe da de baja para las tablets, pero se conserva el histórico.`
                        )
                      }
                    >
                      Eliminar
                    </Button>
                  </>
                ) : (
                  <Button tamano="sm" onClick={() => cambiarComboActivo(p, true)}>
                    Reactivar
                  </Button>
                )}
              </li>
            );

            /* Bloque de una marca dentro de un producto: su nombre, sus fragancias y
               sus presentaciones. */
            const bloqueMarca = (bm) => {
              const otros = [...(marcaEnProductos.get(bm.marca) || [])].filter(
                (prod) => prod !== (bm.items[0].producto || ''),
              );
              return (
                <div key={bm.marca} className="border-b border-sig-line last:border-b-0">
                  <div className="flex flex-wrap items-center gap-2 px-5 py-2.5">
                    <h4 className="w-[124px] shrink-0 font-mono text-[11px] uppercase
                                   tracking-label text-sig-text">
                      {bm.marca}
                    </h4>
                    {fraganciasDe(bm.marca)}

                    {/* Las fragancias cuelgan de (máquina, marca) y NO del producto, así
                        que quitar una aquí la quita también en los demás productos de
                        esta marca. Sin decirlo, el cambio parecería local. */}
                    {otros.length > 0 && (
                      <Label caja="normal" className="basis-full text-sig-dim">
                        las mismas fragancias en {otros.map(etiquetaProducto).join(' · ')}
                      </Label>
                    )}
                  </div>
                  <ul className="divide-y divide-sig-line border-t border-sig-line">
                    {bm.items.map(filaCombo)}
                  </ul>
                </div>
              );
            };

            return (
              <Card
                key={maquina.maquina_id}
                sinPad
                className={maquina.activa ? '' : 'opacity-60'}
              >
                <header className="flex flex-wrap items-center gap-3 px-5 pt-4 pb-3">
                  <h2 className="text-[15px] font-bold text-sig-text">{maquina.maquina}</h2>
                  <Badge tono="gray">{tipoBonito(maquina.tipo)}</Badge>
                  <Label caja="normal" className="flex-1">
                    {plural(maquina.productos.length, 'combinación', 'combinaciones')}
                    {agrupar && ` · ${plural(grupos.length, 'producto', 'productos')}`}
                  </Label>
                  <Button tamano="sm" onClick={() => alternarTipo(maquina)}>
                    → {tipoContrario(maquina.tipo)}
                  </Button>
                  <Button
                    tamano="sm"
                    onClick={() => cambiarMaquinaActiva(maquina, !maquina.activa)}
                  >
                    {maquina.activa ? 'Desactivar' : 'Reactivar'}
                  </Button>
                </header>

                {maquina.productos.length > 0 && (
                  <div className="divide-y divide-sig-line border-t border-sig-line">
                    {/* El prefijo hace única la clave del grupo comodín: ningún producto
                        real es cadena vacía. */}
                    {grupos.map((g) => (
                      <section key={`p:${g.producto}`}>
                        {agrupar && (
                          <header className="flex items-center gap-3 border-b border-sig-line
                                             bg-white/[0.02] px-5 py-2">
                            {/* Encabezado de verdad y no un span: es lo que separa un
                                producto de otro, y un lector de pantalla debe poder
                                saltar de grupo en grupo. */}
                            <Label
                              as="h3"
                              className={g.producto ? 'text-sig-text' : 'text-sig-dim'}
                            >
                              {etiquetaProducto(g.producto)}
                            </Label>
                            <Label caja="normal" className="ml-auto text-sig-dim">
                              {plural(g.total, 'combinación', 'combinaciones')}
                            </Label>
                          </header>
                        )}
                        {g.marcas.map(bloqueMarca)}
                      </section>
                    ))}
                  </div>
                )}

                {/* Alta de combinación para esta máquina */}
                <div className="flex flex-wrap items-center gap-2.5 border-t border-sig-line px-5 py-3">
                  {(maquina.tipo || '').toUpperCase().startsWith('L') && (
                    <Select
                      value={eleccion.producto || ''}
                      onChange={(e) =>
                        setCombo((prev) => ({
                          ...prev,
                          [maquina.maquina_id]: { ...eleccion, producto: e.target.value },
                        }))
                      }
                      opciones={productosDeLinea(maquina.tipo)}
                      vacio="cualquier producto"
                      aria-label={`Producto para ${maquina.maquina}`}
                      className="w-[185px]"
                    />
                  )}
                  <Select
                    value={eleccion.marca || ''}
                    onChange={(e) =>
                      setCombo((prev) => ({
                        ...prev,
                        [maquina.maquina_id]: { ...eleccion, marca: e.target.value },
                      }))
                    }
                    opciones={marcas}
                    vacio="Marca…"
                    aria-label={`Marca para ${maquina.maquina}`}
                    className="w-[190px]"
                  />
                  <Select
                    value={eleccion.presentacion || ''}
                    onChange={(e) =>
                      setCombo((prev) => ({
                        ...prev,
                        [maquina.maquina_id]: { ...eleccion, presentacion: e.target.value },
                      }))
                    }
                    opciones={presentaciones}
                    vacio="Presentación…"
                    aria-label={`Presentación para ${maquina.maquina}`}
                    className="w-[170px]"
                  />
                  <Button tamano="sm" onClick={() => agregarCombinacion(maquina)}>
                    Agregar combinación
                  </Button>
                </div>

                {/* Las fragancias viven ahora en su marca, dentro de la jerarquía. Aquí
                    abajo solo quedan las de marcas que esta máquina ya no produce: sin
                    combinación activa no tienen dónde salir arriba, pero siguen
                    configuradas y hay que poder quitarlas. */}
                {huerfanas.length > 0 && (
                  <div className="border-t border-sig-line px-5 py-3">
                    <Label caja="normal" className="mb-2.5 block text-sig-muted">
                      Fragancias de marcas que esta máquina ya no produce
                    </Label>
                    <div className="space-y-2">
                      {huerfanas.map((mf) => (
                        <div key={mf.marca} className="flex flex-wrap items-center gap-2">
                          <span className="w-[124px] shrink-0 font-mono text-[11px] uppercase
                                           tracking-label text-sig-dim">
                            {mf.marca}
                          </span>
                          {fraganciasDe(mf.marca)}
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* Velocidad estándar: cuelga de (máquina, presentación), sin marca —
                    lo que manda el ritmo es el gramaje y la máquina que lo llena. Es la
                    referencia contra la que el dashboard lee la velocidad que manda la
                    tablet; sin ella, la lectura se enseña pero no se juzga. */}
                <div className="border-t border-sig-line px-5 py-3">
                  <Label caja="normal" className="mb-2.5 block text-sig-muted">
                    Velocidad estándar por presentación · unidades/min
                  </Label>

                  {(velPorMaquina[maquina.maquina_id] || []).length === 0 ? (
                    <Label caja="normal" className="text-sig-dim">
                      {velMatriz.cargando
                        ? 'Cargando…'
                        : 'Sin presentaciones todavía — agrega una combinación arriba'}
                    </Label>
                  ) : (
                    <div className="space-y-2">
                      {(velPorMaquina[maquina.maquina_id] || []).map((item) => {
                        const clave = `${maquina.maquina_id}|${item.presentacion}`;
                        const min = valorVel(clave, item, 'min');
                        const max = valorVel(clave, item, 'max');
                        const activo = Boolean(item.estandar?.activo);
                        const hora = porHora(min, max);
                        return (
                          <div key={item.presentacion} className="flex flex-wrap items-center gap-2">
                            <span className="w-[112px] shrink-0 font-mono text-[11px] uppercase
                                             tracking-label text-sig-muted">
                              {item.presentacion}
                            </span>

                            <Input
                              type="number"
                              inputMode="decimal"
                              min={VEL_MIN}
                              max={VEL_MAX}
                              step="0.1"
                              value={min}
                              onChange={(e) => escribirVel(clave, 'min', e.target.value)}
                              placeholder="mín"
                              aria-label={`Velocidad mínima de ${item.presentacion} en ${maquina.maquina}`}
                              className="w-[78px]"
                            />
                            <span className="text-sig-dim" aria-hidden="true">–</span>
                            <Input
                              type="number"
                              inputMode="decimal"
                              min={VEL_MIN}
                              max={VEL_MAX}
                              step="0.1"
                              value={max}
                              onChange={(e) => escribirVel(clave, 'max', e.target.value)}
                              placeholder="máx"
                              aria-label={`Velocidad máxima de ${item.presentacion} en ${maquina.maquina}`}
                              className="w-[78px]"
                            />

                            {/* El equivalente por hora, calculado: es como se habla del
                                objetivo en planta, pero lo que se compara con la tablet
                                es el minuto, así que se teclea el minuto y se muestra la
                                hora, nunca al revés. */}
                            {hora ? (
                              <Label caja="normal" className="text-sig-dim">≈ {hora} /hora</Label>
                            ) : (
                              <Label caja="normal" className="text-sig-dim">sin configurar</Label>
                            )}

                            {/* Dos avisos distintos que no hay que confundir: la fila
                                dada de baja sigue guardada pero no compara nada, y la
                                presentación que la máquina ya no produce solo está aquí
                                para poder quitarle el estándar que se le puso antes. */}
                            {item.estandar && !activo && (
                              <Label caja="normal" className="text-sig-dim line-through">
                                quitada
                              </Label>
                            )}
                            {!item.produce && (
                              <Label
                                caja="normal"
                                className="text-sig-dim"
                                title="Esta máquina ya no tiene esa presentación en su jerarquía"
                              >
                                ya no se produce
                              </Label>
                            )}

                            {/* Los botones se nombran con su presentación: hay uno por
                                fila y «Guardar» a secas no diría cuál. */}
                            <div className="ml-auto flex items-center gap-2">
                              <Button
                                tamano="sm"
                                onClick={() => guardarVelocidad(maquina, item)}
                                aria-label={`${activo ? 'Guardar' : 'Aplicar'} velocidad de ${item.presentacion} en ${maquina.maquina}`}
                              >
                                {activo ? 'Guardar' : 'Aplicar'}
                              </Button>
                              {activo && (
                                <Button
                                  tamano="sm"
                                  onClick={() => quitarVelocidad(maquina, item)}
                                  aria-label={`Quitar velocidad de ${item.presentacion} en ${maquina.maquina}`}
                                >
                                  Quitar
                                </Button>
                              )}
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              </Card>
            );
          })
        )}
      </div>
    </div>
  );
}
