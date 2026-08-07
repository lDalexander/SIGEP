import React, { useState } from 'react';
import { Card, Badge, Button, Estado, Input, Aviso, useAviso, Label, Campo } from '../ui';
import { num, plural } from '../../lib/format';
import useApi from '../../lib/useApi';
import { admin, mensajeDeError, esSuperadmin, puedeEditar } from '../../lib/adminApi';
import FiltroRango from './FiltroRango';

/* Lo que la web lee es el endpoint PÚBLICO del dashboard de insumos: es el único que
   arma pedidos y entregas con máquina, operador y tiempos. Las correcciones sí van por
   el cliente admin, que es donde se exige el nivel. Mismo patrón que las fragancias de
   la pestaña Producción. */
const RUTA_LECTURA = '/api/insumos/dashboard';

/** Un pedido con cantidades que no cuadran es lo que hay que revisar primero. */
function hayDiscrepancia(p) {
  return [p.solicitada, p.entregada, p.recibida]
    .filter((v) => v !== null && v !== undefined)
    .some((v, _, todos) => v !== todos[0]);
}

/**
 * Pestaña «Insumos» — corregir cantidades de pedidos y entregas proactivas.
 *
 * Los endpoints (`PUT`/`DELETE` de `/admin/pedidos` y `/admin/entregas`) existían desde
 * antes y nunca tuvieron pantalla: la única forma de corregir una cantidad mal tecleada
 * por el insumista era entrar a MySQL.
 *
 * Solo se editan CANTIDADES. Las horas del pedido las escribe el flujo de bodega
 * (solicitud → aceptación → entrega → confirmación) y tocarlas a mano dejaría tiempos
 * de respuesta imposibles en el dashboard.
 */
export default function TabInsumos() {
  const hoy = new Date().toISOString().slice(0, 10);
  const [rango, setRango] = useState({ desde: hoy, hasta: hoy });
  const [aplicado, setAplicado] = useState({ desde: hoy, hasta: hoy });
  const { datos, cargando, error, recargar } = useApi(RUTA_LECTURA, { params: aplicado });
  const { aviso, ok, fallo } = useAviso();

  const [borrador, setBorrador] = useState({});
  const [ocupado, setOcupado] = useState(null);

  const editable = puedeEditar();
  const puedeBorrar = esSuperadmin();
  const pedidos = Array.isArray(datos?.pedidos) ? datos.pedidos : [];
  const entregas = Array.isArray(datos?.entregas) ? datos.entregas : [];

  const valor = (clave, actual) =>
    borrador[clave] !== undefined ? borrador[clave] : (actual ?? '');

  const corregirPedido = async (pedido) => {
    const cambios = {};
    for (const [campo, actual] of [
      ['cantidad_entregada', pedido.entregada],
      ['cantidad_recibida', pedido.recibida],
    ]) {
      const escrito = borrador[`p${pedido.id}_${campo}`];
      if (escrito === undefined) continue;
      if (escrito === '') continue;            // vacío = no tocar, no «cero»
      const n = Number(escrito);
      if (!Number.isInteger(n) || n < 0) {
        fallo('Las cantidades deben ser números enteros de cero en adelante');
        return;
      }
      if (n !== actual) cambios[campo] = n;
    }
    if (Object.keys(cambios).length === 0) {
      fallo('No has cambiado ninguna cantidad');
      return;
    }
    setOcupado(`p${pedido.id}`);
    try {
      await admin.put(`/pedidos/${pedido.id}`, cambios);
      ok(`Pedido #${pedido.id} corregido`);
      setBorrador({});
      recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const eliminarPedido = async (pedido) => {
    if (!window.confirm(
      `¿ELIMINAR el pedido #${pedido.id}?\n\n` +
      `${pedido.insumo} · ${pedido.maquina || 'sin máquina'} · ${pedido.hora_solicitud || ''}\n\n` +
      'Es un borrado definitivo y desaparece de los KPIs de insumos y del Excel. ' +
      'Para anularlo sin destruirlo, pon las cantidades a cero.'
    )) return;
    setOcupado(`p${pedido.id}`);
    try {
      await admin.delete(`/pedidos/${pedido.id}`);
      ok(`Pedido #${pedido.id} eliminado`);
      recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const corregirEntrega = async (entrega) => {
    const escrito = borrador[`e${entrega.id}`];
    if (escrito === undefined || escrito === '') {
      fallo('Escribe la cantidad corregida');
      return;
    }
    const n = Number(escrito);
    if (!Number.isInteger(n) || n < 0) {
      fallo('La cantidad debe ser un número entero de cero en adelante');
      return;
    }
    setOcupado(`e${entrega.id}`);
    try {
      await admin.put(`/entregas/${entrega.id}`, { cantidad: n });
      ok(`Entrega #${entrega.id} corregida`);
      setBorrador({});
      recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const eliminarEntrega = async (entrega) => {
    if (!window.confirm(
      `¿ELIMINAR la entrega #${entrega.id}?\n\n` +
      `${entrega.insumo} · ${entrega.maquina || 'sin máquina'} · ${entrega.fecha_hora || ''}\n\n` +
      'Es un borrado definitivo. Si solo está mal la cantidad, corrígela.'
    )) return;
    setOcupado(`e${entrega.id}`);
    try {
      await admin.delete(`/entregas/${entrega.id}`);
      ok(`Entrega #${entrega.id} eliminada`);
      recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const descuadrados = pedidos.filter(hayDiscrepancia).length;

  return (
    <div>
      <div className="flex items-start justify-between gap-4 mb-5">
        <Label caja="normal" className="text-sig-dim">
          Corregir cantidades mal registradas por bodega · las horas no se tocan: las
          escribe el flujo de pedido y cambiarlas daría tiempos de respuesta imposibles
        </Label>
      </div>

      <FiltroRango
        desde={rango.desde}
        hasta={rango.hasta}
        onChange={(campo, v) => setRango((prev) => ({ ...prev, [campo]: v }))}
        onCargar={() => setAplicado({ ...rango })}
        contador={`${plural(pedidos.length, 'pedido', 'pedidos')} · ${plural(entregas.length, 'entrega', 'entregas')}`}
      />

      <Aviso aviso={aviso} className="mb-4" />

      <div className="space-y-5">
        <Card
          titulo="Pedidos de insumos"
          meta={
            <Label className="text-sig-dim">
              {descuadrados > 0 ? `${descuadrados} con cantidades distintas` : 'sin descuadres'}
            </Label>
          }
          sinPad
        >
          {pedidos.length === 0 ? (
            <Estado cargando={cargando} error={error} vacio="Sin pedidos en este rango" />
          ) : (
            <ul className="divide-y divide-sig-line">
              {pedidos.map((p) => (
                <li key={p.id} className="px-5 py-3.5">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div className="min-w-0">
                      <Label className="block truncate">
                        <span className="text-sig-dim mr-2">#{p.id}</span>
                        {p.insumo} · {p.maquina || '—'}
                      </Label>
                      <Label className="block mt-1 truncate text-sig-dim">
                        {p.operador || '—'} · {p.hora_solicitud || '—'}
                        {p.insumista ? ` · entregó ${p.insumista}` : ''}
                      </Label>
                    </div>
                    <div className="flex items-center gap-2 shrink-0">
                      {hayDiscrepancia(p) && <Badge tono="amber">descuadre</Badge>}
                      <Badge tono={p.estado === 'Entregado' ? 'ok' : 'gray'}>{p.estado}</Badge>
                    </div>
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-4 gap-3 mt-3 items-end">
                    <div>
                      <Label className="block text-sig-dim">Solicitada</Label>
                      {/* La cantidad solicitada la escribió el operario en la tablet y
                          no se corrige aquí: es lo que pidió, no lo que pasó después. */}
                      <p className="mt-1 text-[13px] text-sig-text">{num(p.solicitada)}</p>
                    </div>
                    <Campo etiqueta="Entregada">
                      <Input
                        value={valor(`p${p.id}_cantidad_entregada`, p.entregada)}
                        onChange={(e) => setBorrador((b) => ({ ...b, [`p${p.id}_cantidad_entregada`]: e.target.value }))}
                        disabled={!editable}
                        inputMode="numeric"
                        aria-label={`Cantidad entregada del pedido ${p.id}`}
                      />
                    </Campo>
                    <Campo etiqueta="Recibida">
                      <Input
                        value={valor(`p${p.id}_cantidad_recibida`, p.recibida)}
                        onChange={(e) => setBorrador((b) => ({ ...b, [`p${p.id}_cantidad_recibida`]: e.target.value }))}
                        disabled={!editable}
                        inputMode="numeric"
                        aria-label={`Cantidad recibida del pedido ${p.id}`}
                      />
                    </Campo>
                    {editable && (
                      <div className="flex flex-wrap gap-2.5">
                        <Button
                          variante="primary"
                          onClick={() => corregirPedido(p)}
                          disabled={ocupado === `p${p.id}`}
                        >
                          Guardar
                        </Button>
                        {puedeBorrar && (
                          <Button onClick={() => eliminarPedido(p)} disabled={ocupado === `p${p.id}`}>
                            Eliminar
                          </Button>
                        )}
                      </div>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card
          titulo="Entregas sin pedido"
          meta={<Label className="text-sig-dim">entregas proactivas del insumista</Label>}
          sinPad
        >
          {entregas.length === 0 ? (
            <Estado cargando={cargando} error={error} vacio="Sin entregas proactivas en este rango" />
          ) : (
            <ul className="divide-y divide-sig-line">
              {entregas.map((e) => (
                <li key={e.id} className="flex flex-wrap items-end justify-between gap-3 px-5 py-3.5">
                  <div className="min-w-0">
                    <Label className="block truncate">
                      <span className="text-sig-dim mr-2">#{e.id}</span>
                      {e.insumo} · {e.maquina || '—'}
                    </Label>
                    <Label className="block mt-1 truncate text-sig-dim">
                      {e.fecha_hora || '—'}{e.insumista ? ` · ${e.insumista}` : ''}
                      {e.observaciones ? ` · ${e.observaciones}` : ''}
                    </Label>
                  </div>

                  <div className="flex flex-wrap items-end gap-2.5">
                    <Campo etiqueta="Cantidad">
                      <Input
                        value={valor(`e${e.id}`, e.cantidad)}
                        onChange={(ev) => setBorrador((b) => ({ ...b, [`e${e.id}`]: ev.target.value }))}
                        disabled={!editable}
                        inputMode="numeric"
                        className="max-w-[110px]"
                        aria-label={`Cantidad de la entrega ${e.id}`}
                      />
                    </Campo>
                    {editable && (
                      <>
                        <Button
                          variante="primary"
                          onClick={() => corregirEntrega(e)}
                          disabled={ocupado === `e${e.id}`}
                        >
                          Guardar
                        </Button>
                        {puedeBorrar && (
                          <Button onClick={() => eliminarEntrega(e)} disabled={ocupado === `e${e.id}`}>
                            Eliminar
                          </Button>
                        )}
                      </>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </div>
  );
}
