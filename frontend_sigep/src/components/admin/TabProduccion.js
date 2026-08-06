import React, { useEffect, useState } from 'react';
import axios from 'axios';
import { Card, Badge, Button, Campo, Select, Estado, Aviso, useAviso, Label } from '../ui';
import { num, fechaISO, plural } from '../../lib/format';
import FiltroRango from './FiltroRango';
import useApi from '../../lib/useApi';
import { admin, mensajeDeError, esSuperadmin, puedeEditar } from '../../lib/adminApi';

const CAMPOS = ['maquina', 'operador', 'marca', 'presentacion', 'fragancia'];

/**
 * Pestaña «Producción» — corrige los datos de las sesiones que llegan de las tablets.
 *
 * Los campos son selects poblados con los catálogos reales, no texto libre, para que
 * una corrección no introduzca variantes nuevas de un mismo nombre (las capturas
 * muestran inputs, pero el brief pide selects).
 *
 * No hay tabla maestra de fragancias, así que esa lista sale de los valores históricos
 * distintos que devuelve /dashboard/opciones_filtros.
 */
export default function TabProduccion() {
  const hoy = fechaISO();
  const [rango, setRango] = useState({ desde: hoy, hasta: hoy });
  const [aplicado, setAplicado] = useState({ desde: hoy, hasta: hoy });
  const { aviso, ok, fallo } = useAviso();

  const sesiones = useApi('/sesiones', { params: aplicado, cliente: admin });
  const catalogos = useApi('/catalogos', { cliente: admin });
  const operarios = useApi('/operadores', { cliente: admin });
  /* Para saber si la tablet de una sesión activa sigue reportando: cerrar un turno
     que alguien está usando de verdad deja a esa tablet en un estado inconsistente,
     así que la confirmación tiene que poder advertirlo. */
  const activas = useApi('/sesiones_activas', { cliente: admin });
  const [fragancias, setFragancias] = useState([]);

  /* Borradores por sesión: { [id]: {maquina, operador, ...} } */
  const [borrador, setBorrador] = useState({});
  const [guardando, setGuardando] = useState(null);
  const [ocupado, setOcupado] = useState(null);   // { id, accion } mientras se cierra/elimina

  const editable = puedeEditar();
  const puedeEliminar = esSuperadmin();

  useEffect(() => {
    axios
      .get('/api/dashboard/opciones_filtros', { timeout: 8000 })
      .then(({ data }) => setFragancias(data?.fragancia || []))
      .catch((err) => console.error('[SIGEP] Error en opciones_filtros:', err.message));
  }, []);

  const lista = Array.isArray(sesiones.datos) ? sesiones.datos : [];
  const nombresMaquina = (catalogos.datos?.maquinas || []).map((m) => m.nombre);
  const nombresOperario = (Array.isArray(operarios.datos) ? operarios.datos : [])
    .filter((o) => o.activo)
    .map((o) => o.nombre);

  const opcionesDe = {
    maquina: nombresMaquina,
    operador: nombresOperario,
    marca: catalogos.datos?.marcas || [],
    presentacion: catalogos.datos?.presentaciones || [],
    fragancia: fragancias,
  };

  const valorDe = (sesion, campo) =>
    borrador[sesion.id]?.[campo] ?? sesion[campo] ?? '';

  const editar = (sesion, campo, valor) =>
    setBorrador((prev) => ({
      ...prev,
      [sesion.id]: { ...prev[sesion.id], [campo]: valor },
    }));

  const guardar = async (sesion) => {
    const cambios = borrador[sesion.id];
    if (!cambios || Object.keys(cambios).length === 0) {
      fallo('No hay cambios en esta sesión');
      return;
    }
    setGuardando(sesion.id);
    try {
      await admin.put(`/sesiones/${sesion.id}`, cambios);
      ok(`Sesión #${sesion.id} guardada`);
      setBorrador((prev) => {
        const resto = { ...prev };
        delete resto[sesion.id];
        return resto;
      });
      sesiones.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setGuardando(null);
    }
  };

  /* ¿La tablet de esta sesión sigue en línea? Solo lo sabemos de las activas. */
  const tabletEnLinea = (sesion) =>
    Boolean(
      (Array.isArray(activas.datos) ? activas.datos : []).find(
        (a) => a.sesion_id === sesion.id,
      )?.tablet_online,
    );

  const cerrarTurno = async (sesion) => {
    const enLinea = tabletEnLinea(sesion);
    const aviso = enLinea
      ? '\n\n⚠ LA TABLET DE ESTA MÁQUINA SIGUE CONECTADA.\nSi hay alguien trabajando, sus pacas se seguirán registrando en un turno ya cerrado y su botón de finalizar dará error. Ciérralo solo si el turno quedó abandonado.'
      : '';
    if (!window.confirm(
      `¿Cerrar el turno de ${sesion.operador} en ${sesion.maquina}?\n\n` +
      'Se cerrarán también el paro abierto y los pedidos de insumo pendientes, y ' +
      'quedará registrado que lo cerraste tú.' + aviso
    )) return;

    setOcupado({ id: sesion.id, accion: 'cerrar' });
    try {
      const { data } = await admin.post(`/sesiones/${sesion.id}/cerrar`);
      const extras = [
        data.paro_cerrado ? 'paro cerrado' : null,
        data.pedidos_cerrados ? `${data.pedidos_cerrados} pedido(s) cerrado(s)` : null,
      ].filter(Boolean);
      ok(`Turno #${sesion.id} cerrado${extras.length ? ` · ${extras.join(' · ')}` : ''}`);
      sesiones.recargar();
      activas.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const eliminar = async (sesion) => {
    const conProduccion = Number(sesion.total_pacas) > 0 || Number(sesion.n_registros) > 0;
    if (!window.confirm(
      `¿ELIMINAR la sesión #${sesion.id}?\n\n` +
      `${sesion.maquina} · ${sesion.operador} · ${sesion.inicio}\n` +
      `${num(sesion.total_pacas)} pacas en ${num(sesion.n_registros)} registro(s).\n\n` +
      'Se borrará también TODO lo que cuelga de ella: pacas, paros, pedidos de ' +
      'insumo, comentarios y reportes. No se puede deshacer.'
    )) return;

    /* Con producción registrada se pide teclear el número: el clic de más en un
       confirm es demasiado fácil para algo irreversible que mueve los KPIs. */
    if (conProduccion) {
      const tecleado = window.prompt(
        `Esta sesión tiene ${num(sesion.total_pacas)} pacas registradas.\n` +
        `Escribe ${sesion.id} para confirmar que quieres borrarla con toda su producción:`
      );
      if (String(tecleado || '').trim() !== String(sesion.id)) {
        fallo('Eliminación cancelada');
        return;
      }
    }

    setOcupado({ id: sesion.id, accion: 'eliminar' });
    try {
      const { data } = await admin.delete(`/sesiones/${sesion.id}`);
      const b = data?.borrado || {};
      ok(`Sesión #${sesion.id} eliminada · ${num(b.pallets || 0)} registro(s) de pacas, ` +
         `${num(b.paros || 0)} paro(s)`);
      sesiones.recargar();
      activas.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  return (
    <div>
      <FiltroRango
        desde={rango.desde}
        hasta={rango.hasta}
        onChange={(campo, valor) => setRango((prev) => ({ ...prev, [campo]: valor }))}
        onCargar={() => setAplicado({ ...rango })}
        contador={plural(lista.length, 'sesión', 'sesiones')}
      />

      <Aviso aviso={aviso} className="mb-4" />

      {lista.length === 0 ? (
        <Card>
          <Estado
            cargando={sesiones.cargando}
            error={sesiones.error}
            vacio="Sin sesiones en el rango"
          />
        </Card>
      ) : (
        <div className="space-y-5">
          {lista.map((sesion) => {
            const tocada = Boolean(borrador[sesion.id]);
            return (
              <Card key={sesion.id} sinPad etiqueta={`Sesión #${sesion.id}`}>
                <header className="flex items-center justify-between gap-3 border-b border-sig-line px-5 py-3.5">
                  <h2 className="text-[14px] font-bold text-sig-text">
                    Sesión #{sesion.id} · {sesion.inicio}
                  </h2>
                  <Badge tono={sesion.estado === 'Activo' ? 'ok' : 'gray'}>{sesion.estado}</Badge>
                </header>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 px-5 py-4">
                  {CAMPOS.map((campo) => (
                    <Campo
                      key={campo}
                      etiqueta={campo === 'presentacion' ? 'Presentación' : campo}
                      className={campo === 'fragancia' ? 'sm:col-span-1' : ''}
                    >
                      <Select
                        value={valorDe(sesion, campo)}
                        onChange={(e) => editar(sesion, campo, e.target.value)}
                        opciones={opcionesDe[campo]}
                      />
                    </Campo>
                  ))}
                </div>

                <div className="flex flex-wrap items-center justify-end gap-3 border-t border-sig-line px-5 py-3">
                  <Label caja="normal" className="rounded-lg bg-sig-input px-3 py-1.5">
                    Pacas: {num(sesion.total_pacas)} ({num(sesion.n_registros)} reg.)
                  </Label>
                  {/* Habilitado siempre, como en las capturas: si no hay cambios el
                      propio `guardar` lo avisa en lugar de mandar un PUT vacío. */}
                  {editable && (
                    <Button
                      variante="primary"
                      onClick={() => guardar(sesion)}
                      disabled={guardando === sesion.id}
                      title={tocada ? undefined : 'Sin cambios pendientes'}
                    >
                      {guardando === sesion.id ? 'Guardando…' : 'Guardar sesión'}
                    </Button>
                  )}

                  {/* Solo tiene sentido en un turno abierto: es la salida para los que
                      quedan sin finalizar y bloquean a la máquina para el grupo
                      siguiente («Esta máquina ya tiene un turno activo»). */}
                  {editable && sesion.estado === 'Activo' && (
                    <Button
                      onClick={() => cerrarTurno(sesion)}
                      disabled={ocupado?.id === sesion.id}
                      title={
                        tabletEnLinea(sesion)
                          ? 'La tablet de esta máquina sigue conectada'
                          : 'Cierra el turno y deja constancia de quién lo cerró'
                      }
                    >
                      {ocupado?.id === sesion.id && ocupado.accion === 'cerrar'
                        ? 'Cerrando…'
                        : 'CERRAR TURNO'}
                    </Button>
                  )}

                  {/* Irreversible y borra en cascada, por eso solo SUPERADMIN. Estilo
                      secundario sin rojo, como el resto de «Eliminar» del sistema. */}
                  {puedeEliminar && (
                    <Button
                      onClick={() => eliminar(sesion)}
                      disabled={ocupado?.id === sesion.id}
                      title="Borra la sesión y toda su producción. No se puede deshacer"
                    >
                      {ocupado?.id === sesion.id && ocupado.accion === 'eliminar'
                        ? 'Eliminando…'
                        : 'Eliminar'}
                    </Button>
                  )}
                </div>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
