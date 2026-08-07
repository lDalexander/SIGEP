import React, { useState } from 'react';
import { Card, Badge, Button, Estado, Input, Select, Aviso, useAviso, Label, Campo } from '../ui';
import { antiguedad, plural } from '../../lib/format';
import useApi from '../../lib/useApi';
import { admin, mensajeDeError, esSuperadmin, puedeEditar } from '../../lib/adminApi';

/* Más de un día sin dar señales: candidata a haber sido retirada o reinstalada. No es
   un estado del sistema, solo el criterio de esta pantalla para ordenar la limpieza. */
const OLVIDADA_S = 24 * 3600;

/**
 * Pestaña «Tablets» — limpiar y corregir el registro de dispositivos.
 *
 * `estado_tablets` la escriben las propias tablets: la fila se crea sola la primera vez
 * que un equipo manda su heartbeat. Con el tiempo se acumulan entradas de equipos
 * retirados o reinstalados (24 registradas el 2026-08-07, 17 sin señales en más de un
 * día) y hasta ahora limpiarlas obligaba a entrar a MySQL.
 *
 * Dos cosas que conviene tener claras y que la pantalla dice:
 * - **Conectada = WebSocket abierto ahora**, no heartbeat reciente. Los latidos llegan
 *   cada 20-25 minutos, así que «sin conexión» no significa apagada.
 * - **Borrar no es permanente si el equipo sigue vivo**: su próximo heartbeat vuelve a
 *   crear la fila. Sirve para retirar equipos que ya no están.
 */
export default function TabTablets() {
  const tablets = useApi('/tablets', { cliente: admin });
  const catalogos = useApi('/catalogos', { cliente: admin });
  const { aviso, ok, fallo } = useAviso();

  const [borrador, setBorrador] = useState({});
  const [ocupado, setOcupado] = useState(null);

  const editable = puedeEditar();
  const puedeBorrar = esSuperadmin();
  const lista = Array.isArray(tablets.datos) ? tablets.datos : [];
  const maquinas = (catalogos.datos?.maquinas || []).map((m) => m.nombre);

  const valor = (id, campo, actual) => {
    const v = borrador[`${id}_${campo}`];
    return v !== undefined ? v : (actual ?? '');
  };

  const guardar = async (t) => {
    const cambios = {};
    const nombre = borrador[`${t.device_id}_nombre`];
    const maquina = borrador[`${t.device_id}_maquina`];
    if (nombre !== undefined && nombre !== (t.nombre ?? '')) cambios.nombre = nombre;
    if (maquina !== undefined && maquina !== (t.maquina ?? '')) cambios.maquina = maquina;
    if (Object.keys(cambios).length === 0) {
      fallo('No has cambiado nada');
      return;
    }
    setOcupado(t.device_id);
    try {
      await admin.put(`/tablets/${t.device_id}`, cambios);
      ok('Tablet actualizada');
      setBorrador({});
      tablets.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const eliminar = async (t) => {
    if (!window.confirm(
      `¿Quitar del registro la tablet «${t.nombre || t.device_id}»?\n\n` +
      `${t.maquina || 'sin máquina'} · último contacto ${antiguedad(t.segundos_desde_heartbeat)}\n\n` +
      'No borra ninguna producción: esta tabla solo guarda el estado de sincronización. ' +
      'Y si el equipo sigue encendido, volverá a aparecer en cuanto mande su próximo ' +
      'heartbeat.'
    )) return;
    setOcupado(t.device_id);
    try {
      await admin.delete(`/tablets/${t.device_id}`);
      ok('Tablet quitada del registro');
      tablets.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const conectadas = lista.filter((t) => t.conectada).length;
  const olvidadas = lista.filter(
    (t) => t.segundos_desde_heartbeat === null || t.segundos_desde_heartbeat > OLVIDADA_S,
  ).length;

  return (
    <div>
      <div className="flex items-start justify-between gap-4 mb-5">
        <Label caja="normal" className="text-sig-dim">
          Registro de dispositivos · «conectada» es tener el WebSocket abierto ahora,
          no el heartbeat: los latidos llegan cada 20-25 minutos
        </Label>
        <Button onClick={tablets.recargar} className="shrink-0">Recargar</Button>
      </div>

      <Aviso aviso={aviso} className="mb-4" />

      <Card
        titulo="Tablets registradas"
        meta={
          <Label className="text-sig-dim">
            {plural(lista.length, 'tablet', 'tablets')} · {conectadas} conectada(s)
            {olvidadas > 0 ? ` · ${olvidadas} sin señales en más de un día` : ''}
          </Label>
        }
        sinPad
      >
        {lista.length === 0 ? (
          <Estado cargando={tablets.cargando} error={tablets.error} vacio="Sin tablets registradas" />
        ) : (
          <ul className="divide-y divide-sig-line">
            {lista.map((t) => {
              const olvidada = t.segundos_desde_heartbeat === null
                || t.segundos_desde_heartbeat > OLVIDADA_S;
              return (
                <li key={t.device_id} className="px-5 py-3.5">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <Label className="block truncate text-sig-dim">
                      {t.device_id.slice(0, 8)}… · último contacto{' '}
                      {antiguedad(t.segundos_desde_heartbeat)}
                      {t.pendientes > 0 ? ` · ${t.pendientes} pendiente(s)` : ''}
                    </Label>
                    <div className="flex items-center gap-2 shrink-0">
                      {olvidada && <Badge tono="gray">sin señales</Badge>}
                      <Badge tono={t.conectada ? 'ok' : 'gray'}>
                        {t.conectada ? 'conectada' : 'sin conexión'}
                      </Badge>
                    </div>
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mt-3 items-end">
                    <Campo etiqueta="Nombre">
                      <Input
                        value={valor(t.device_id, 'nombre', t.nombre)}
                        onChange={(e) => setBorrador((b) => ({ ...b, [`${t.device_id}_nombre`]: e.target.value }))}
                        disabled={!editable}
                        aria-label={`Nombre de la tablet ${t.device_id.slice(0, 8)}`}
                      />
                    </Campo>
                    <Campo etiqueta="Máquina">
                      {/* `Select` recibe `opciones` (no children) y él mismo antepone
                          el valor actual si no está en la lista: una máquina dada de
                          baja no desaparece del selector al editar el nombre. */}
                      <Select
                        value={valor(t.device_id, 'maquina', t.maquina)}
                        opciones={maquinas}
                        vacio="— sin máquina —"
                        onChange={(e) => setBorrador((b) => ({ ...b, [`${t.device_id}_maquina`]: e.target.value }))}
                        disabled={!editable}
                        aria-label={`Máquina de la tablet ${t.device_id.slice(0, 8)}`}
                      />
                    </Campo>
                    {editable && (
                      <div className="flex flex-wrap gap-2.5">
                        <Button
                          variante="primary"
                          onClick={() => guardar(t)}
                          disabled={ocupado === t.device_id}
                        >
                          Guardar
                        </Button>
                        {puedeBorrar && (
                          <Button onClick={() => eliminar(t)} disabled={ocupado === t.device_id}>
                            Quitar del registro
                          </Button>
                        )}
                      </div>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </Card>

      <Label caja="normal" className="block mt-4 text-sig-dim">
        El nombre y la máquina los vuelve a mandar la app en cada heartbeat, así que
        corregirlos aquí arregla la lista hasta el siguiente latido. Si el dato está mal
        en el equipo, hay que cambiarlo allí.
      </Label>
    </div>
  );
}
