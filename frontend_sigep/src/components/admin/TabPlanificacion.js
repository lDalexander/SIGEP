import React, { useMemo, useState } from 'react';
import { Card, Badge, Button, Estado, Input, Select, Aviso, useAviso, Label, ProgressBar } from '../ui';
import useApi from '../../lib/useApi';
import { admin, planificacion, mensajeDeError, tieneAcceso, NIVELES_PLANTA } from '../../lib/adminApi';

/**
 * Pestaña «Planificación» — el plan de producción del día y su cobertura de insumos.
 *
 * Hasta ahora el insumista solo se movía cuando un operario pedía algo: el flujo entero
 * era reactivo. Producción planifica el día de antemano, así que con el plan cargado el
 * material se puede dejar preparado ANTES de que la máquina arranque.
 *
 * LA MÁQUINA ES OPCIONAL y es el caso del que nace todo esto: hay producción planificada
 * que todavía no tiene máquina asignada y el material se deja para que después
 * cualquiera lo tome. Por eso el selector trae «(sin asignar)» como primera opción y no
 * como un hueco que parezca un olvido.
 *
 * Los insumos de cada plan los genera el BOM al crearlo, y quedan editables: la receta
 * dice qué lleva el producto, pero la cantidad del día la ajusta quien planifica.
 */

const SIN_MAQUINA = '(sin asignar)';
const ESTADOS = ['Planificado', 'En Curso', 'Completado', 'Cancelado'];
const PRIORIDADES = ['Normal', 'Alta', 'Urgente'];

const hoyISO = () => new Date().toISOString().slice(0, 10);

function tonoCobertura(pct) {
  if (pct === null || pct === undefined) return 'gray';
  if (pct >= 100) return 'ok';
  if (pct >= 50) return 'amber';
  return 'bad';
}

/** Un plan con su lista de insumos y los controles para ajustarla. */
function FichaPlan({ plan, editable, onCambio, avisar }) {
  const [ocupado, setOcupado] = useState(null);
  const [borrador, setBorrador] = useState({});
  const [anadiendo, setAnadiendo] = useState(false);

  const { datos: catalogo } = useApi('/api/insumos/catalogo');
  const insumosDisponibles = useMemo(() => {
    const yaEstan = new Set(plan.insumos.map((i) => i.insumo_id));
    return (Array.isArray(catalogo) ? catalogo : []).filter((i) => !yaEstan.has(i.id));
  }, [catalogo, plan.insumos]);

  const llamar = async (accion, etiqueta) => {
    setOcupado(etiqueta);
    try {
      await accion();
      await onCambio();
      avisar.ok('Listo');
    } catch (e) {
      avisar.fallo(mensajeDeError(e));
    } finally {
      setOcupado(null);
    }
  };

  const guardarLinea = (linea) => {
    const cantidad = borrador[`c${linea.id}`];
    const prioridad = borrador[`p${linea.id}`];
    const cuerpo = {};
    if (cantidad !== undefined && cantidad !== '') cuerpo.cantidad_requerida = Number(cantidad);
    if (prioridad) cuerpo.prioridad = prioridad;
    if (!Object.keys(cuerpo).length) return;
    return llamar(
      () => planificacion.put(`/planes/${plan.id}/insumos/${linea.id}`, cuerpo),
      `linea-${linea.id}`
    );
  };

  const titulo = [plan.producto, plan.marca, plan.presentacion, plan.fragancia]
    .filter(Boolean).join(' · ') || 'Sin producto definido';

  return (
    <Card
      titulo={titulo}
      meta={`${plan.fecha}${plan.turno ? ` · ${plan.turno}` : ''} · ${plan.maquina || SIN_MAQUINA}`}
    >
      <div className="flex flex-wrap items-center gap-3 pb-3">
        {editable ? (
          <Select
            value={plan.estado}
            opciones={ESTADOS}
            vacio={null}
            onChange={(e) => llamar(
              () => planificacion.put(`/planes/${plan.id}`, { fecha: plan.fecha, estado: e.target.value }),
              'estado'
            )}
            disabled={ocupado === 'estado'}
          />
        ) : (
          <Badge tono={plan.estado === 'Cancelado' ? 'gray' : 'amber'}>{plan.estado}</Badge>
        )}
        {plan.cantidad_planificada != null && (
          <Label>Plan: {plan.cantidad_planificada} {plan.unidad || ''}</Label>
        )}
        {/* `hay_urgentes` solo se enciende si lo urgente ADEMÁS está incompleto: un
            insumo marcado urgente que ya llegó no es una alarma. */}
        {plan.hay_urgentes && <Badge tono="bad">Falta material urgente</Badge>}
        {plan.creado_por && <Label className="ml-auto">por {plan.creado_por}</Label>}
      </div>

      {plan.cobertura === null ? (
        <p className="sig-meta text-sig-amber pb-3">
          Este plan no tiene insumos: el BOM no tiene recetas para esta combinación.
          Cárgalas en Jerarquía y pulsa «Recalcular BOM», o añádelos a mano aquí abajo.
        </p>
      ) : (
        <div className="pb-3">
          <div className="flex items-center justify-between">
            <Label>Cobertura de insumos</Label>
            <Badge tono={tonoCobertura(plan.cobertura)}>{plan.cobertura}%</Badge>
          </div>
          <ProgressBar valor={plan.cobertura} max={100} />
          {plan.insumos_incompletos > 0 && (
            <Label className="block mt-1">
              {plan.insumos_incompletos} insumo(s) sin completar
            </Label>
          )}
        </div>
      )}

      <div className="overflow-x-auto">
        <table className="w-full text-[13px]">
          <thead>
            <tr className="text-left text-sig-dim">
              <th className="py-1">Insumo</th>
              <th className="py-1">Bodega</th>
              <th className="py-1 text-right">Requerido</th>
              <th className="py-1 text-right">Entregado</th>
              <th className="py-1 text-right">Falta</th>
              <th className="py-1">Prioridad</th>
              {editable && <th className="py-1" />}
            </tr>
          </thead>
          <tbody>
            {plan.insumos.map((linea) => (
              <tr key={linea.id} className="border-t border-sig-line">
                <td className="py-1.5">
                  {linea.insumo}
                  {linea.unidad && <Label className="ml-1">({linea.unidad})</Label>}
                </td>
                <td className="py-1.5"><Label>{linea.bodega}</Label></td>
                <td className="py-1.5 text-right">
                  {editable ? (
                    <Input
                      type="number"
                      className="w-20 text-right"
                      defaultValue={linea.requerido}
                      onChange={(e) => setBorrador((b) => ({ ...b, [`c${linea.id}`]: e.target.value }))}
                    />
                  ) : linea.requerido}
                </td>
                <td className="py-1.5 text-right tabular-nums">{linea.entregado}</td>
                <td className="py-1.5 text-right tabular-nums">
                  {linea.completo
                    ? <Badge tono="ok">completo</Badge>
                    : <span className="text-sig-amber">{linea.falta}</span>}
                </td>
                <td className="py-1.5">
                  {editable ? (
                    <Select
                      value={borrador[`p${linea.id}`] ?? linea.prioridad}
                      opciones={PRIORIDADES}
                      vacio={null}
                      onChange={(e) => setBorrador((b) => ({ ...b, [`p${linea.id}`]: e.target.value }))}
                    />
                  ) : <Badge tono={linea.prioridad === 'Normal' ? 'gray' : 'amber'}>{linea.prioridad}</Badge>}
                </td>
                {editable && (
                  <td className="py-1.5 text-right whitespace-nowrap">
                    <Button
                      onClick={() => guardarLinea(linea)}
                      disabled={ocupado === `linea-${linea.id}`}
                    >
                      Guardar
                    </Button>
                    <Button
                      className="ml-1"
                      onClick={() => llamar(
                        () => planificacion.delete(`/planes/${plan.id}/insumos/${linea.id}`),
                        `del-${linea.id}`
                      )}
                      disabled={ocupado === `del-${linea.id}`}
                    >
                      Quitar
                    </Button>
                  </td>
                )}
              </tr>
            ))}
            {plan.insumos.length === 0 && (
              <tr><td colSpan={7} className="py-3 text-center text-sig-dim">Sin insumos</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {editable && (
        <div className="flex flex-wrap items-center gap-2 pt-3 border-t border-sig-line mt-3">
          <Button
            onClick={() => llamar(() => planificacion.post(`/planes/${plan.id}/recalcular`), 'recalc')}
            disabled={ocupado === 'recalc'}
          >
            Recalcular BOM
          </Button>
          {/* Sin sobrescribir solo añade lo que falte: recalcular no puede borrarle las
              cantidades a quien ya las ajustó a mano. */}
          <Button
            onClick={() => llamar(
              () => planificacion.post(`/planes/${plan.id}/recalcular?sobrescribir=true`),
              'recalc2'
            )}
            disabled={ocupado === 'recalc2'}
          >
            Recalcular y reponer cantidades
          </Button>

          {anadiendo ? (
            <select
              autoFocus
              defaultValue=""
              className="sig-input"
              onChange={(e) => {
                const id = Number(e.target.value);
                if (!id) return;
                setAnadiendo(false);
                llamar(
                  () => planificacion.post(`/planes/${plan.id}/insumos`, { insumo_id: id, cantidad_requerida: 1 }),
                  'add'
                );
              }}
            >
              <option value="">— elige un insumo —</option>
              {insumosDisponibles.map((i) => (
                <option key={i.id} value={i.id}>{i.nombre}</option>
              ))}
            </select>
          ) : (
            <Button onClick={() => setAnadiendo(true)}>Añadir insumo</Button>
          )}

          <Button
            className="ml-auto border-red-500/50 text-red-400"
            onClick={() => {
              if (!window.confirm(`¿Eliminar el plan del ${plan.fecha}?`)) return;
              llamar(() => planificacion.delete(`/planes/${plan.id}`), 'borrar');
            }}
            disabled={ocupado === 'borrar'}
          >
            Eliminar plan
          </Button>
        </div>
      )}
    </Card>
  );
}

/** Formulario de alta. Se despliega solo cuando se pide, para no robar la vista. */
function NuevoPlan({ catalogos, productos, onCreado, avisar }) {
  const [abierto, setAbierto] = useState(false);
  const [guardando, setGuardando] = useState(false);
  const [f, setF] = useState({ fecha: hoyISO() });

  const cambiar = (k) => (e) => setF((v) => ({ ...v, [k]: e.target.value }));

  const crear = async () => {
    if (!f.fecha) { avisar.fallo('La fecha es obligatoria'); return; }
    setGuardando(true);
    try {
      await planificacion.post('/planes', {
        fecha: f.fecha,
        turno: f.turno || null,
        // En la API «sin máquina» es la ausencia del dato, no un texto que luego
        // habría que reconocer en cada consulta.
        maquina: f.maquina || null,
        producto: f.producto || null,
        marca: f.marca || null,
        presentacion: f.presentacion || null,
        fragancia: f.fragancia || null,
        cantidad_planificada: f.cantidad ? Number(f.cantidad) : null,
        unidad: f.unidad || null,
        observaciones: f.observaciones || null,
      });
      setF({ fecha: hoyISO() });
      setAbierto(false);
      await onCreado();
      avisar.ok('Plan creado con los insumos de su receta');
    } catch (e) {
      avisar.fallo(mensajeDeError(e));
    } finally {
      setGuardando(false);
    }
  };

  if (!abierto) {
    return <Button onClick={() => setAbierto(true)}>+ Nuevo plan</Button>;
  }

  /** `catalogos.maquinas` son objetos y el resto cadenas: `Select` quiere cadenas. */
  const nombres = (lista) => (lista || []).map((x) => (typeof x === 'string' ? x : x.nombre));

  return (
    <Card titulo="Nuevo plan de producción">
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div><Label>Fecha</Label><Input type="date" value={f.fecha || ''} onChange={cambiar('fecha')} /></div>
        <div><Label>Turno</Label><Input placeholder="Mañana / Tarde…" value={f.turno || ''} onChange={cambiar('turno')} /></div>
        <div>
          <Label>Máquina</Label>
          {/* «sin asignar» es la opción vacía, no una máquina más: así el centinela
              nunca viaja como texto y la ausencia de máquina es lo que debe ser. */}
          <Select
            value={f.maquina || ''}
            opciones={nombres(catalogos?.maquinas)}
            vacio={SIN_MAQUINA}
            onChange={cambiar('maquina')}
          />
        </div>
        <div>
          <Label>Producto</Label>
          <Select value={f.producto || ''} opciones={nombres(productos)} onChange={cambiar('producto')} />
        </div>
        <div>
          <Label>Marca</Label>
          <Select value={f.marca || ''} opciones={nombres(catalogos?.marcas)} onChange={cambiar('marca')} />
        </div>
        <div>
          <Label>Presentación</Label>
          <Select value={f.presentacion || ''} opciones={nombres(catalogos?.presentaciones)} onChange={cambiar('presentacion')} />
        </div>
        <div>
          <Label>Fragancia</Label>
          <Select value={f.fragancia || ''} opciones={nombres(catalogos?.fragancias)} onChange={cambiar('fragancia')} />
        </div>
        <div><Label>Cantidad</Label><Input type="number" value={f.cantidad || ''} onChange={cambiar('cantidad')} /></div>
        <div><Label>Unidad</Label><Input placeholder="unidades / kg" value={f.unidad || ''} onChange={cambiar('unidad')} /></div>
        <div className="col-span-2 md:col-span-3">
          <Label>Observaciones</Label>
          <Input value={f.observaciones || ''} onChange={cambiar('observaciones')} />
        </div>
      </div>
      <div className="flex gap-2 pt-3">
        <Button onClick={crear} disabled={guardando}>{guardando ? 'Creando…' : 'Crear plan'}</Button>
        <Button onClick={() => setAbierto(false)}>Cancelar</Button>
      </div>
      <Label className="block pt-2">
        Al crear el plan se generan sus insumos a partir de la receta. Cuanto más
        concreto sea (producto, marca, presentación), más ajustada sale la lista.
      </Label>
    </Card>
  );
}

export default function TabPlanificacion() {
  const [rango, setRango] = useState({ desde: hoyISO(), hasta: hoyISO() });
  const [aplicado, setAplicado] = useState({ desde: hoyISO(), hasta: hoyISO() });
  const { aviso, ok, fallo } = useAviso();

  const { datos, cargando, error, recargar } = useApi('/planes', {
    params: aplicado, cliente: planificacion,
  });
  const { datos: catalogos } = useApi('/catalogos', { cliente: admin });
  const { datos: productos } = useApi('/api/productos');

  const planes = Array.isArray(datos?.planes) ? datos.planes : [];
  const editable = tieneAcceso(NIVELES_PLANTA);

  return (
    <div className="space-y-4">
      <Aviso aviso={aviso} />

      <div className="flex flex-wrap items-end gap-3">
        <div><Label>Desde</Label>
          <Input type="date" value={rango.desde}
                 onChange={(e) => setRango((r) => ({ ...r, desde: e.target.value }))} /></div>
        <div><Label>Hasta</Label>
          <Input type="date" value={rango.hasta}
                 onChange={(e) => setRango((r) => ({ ...r, hasta: e.target.value }))} /></div>
        <Button onClick={() => setAplicado(rango)}>Aplicar</Button>
        {editable && (
          <div className="ml-auto">
            <NuevoPlan catalogos={catalogos} productos={productos}
                       onCreado={recargar} avisar={{ ok, fallo }} />
          </div>
        )}
      </div>

      {planes.length === 0 ? (
        <Card titulo="Planificación">
          <Estado cargando={cargando} error={error}
                  vacio="No hay planes en este rango. Crea uno para que el insumista pueda dejar el material preparado." />
        </Card>
      ) : (
        planes.map((plan) => (
          <FichaPlan key={plan.id} plan={plan} editable={editable}
                     onCambio={recargar} avisar={{ ok, fallo }} />
        ))
      )}
    </div>
  );
}
