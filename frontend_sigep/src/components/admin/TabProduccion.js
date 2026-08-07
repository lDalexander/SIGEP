import React, { useEffect, useState } from 'react';
import axios from 'axios';
import { ChevronDown } from 'lucide-react';
import { Card, Badge, Button, Campo, Input, Select, Estado, Aviso, useAviso, Label } from '../ui';
import { num, fechaISO, plural } from '../../lib/format';
import FiltroRango from './FiltroRango';
import useApi from '../../lib/useApi';
import { admin, mensajeDeError, esSuperadmin, puedeEditar } from '../../lib/adminApi';

const CAMPOS = ['maquina', 'operador', 'marca', 'presentacion', 'fragancia'];

/* La API devuelve «YYYY-MM-DD HH:MM:SS»; `datetime-local` quiere «YYYY-MM-DDTHH:MM».
   Se recorta a minutos a propósito: el control no maneja segundos, y por eso la hora
   solo se manda al backend cuando el usuario la cambia (si no, los pondría a :00). */
const paraInput = (fechaHora) => String(fechaHora || '').replace(' ', 'T').slice(0, 16);
const horaDe = (fechaHora) => String(fechaHora || '').slice(11, 16) || '—';

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

  /* Historial de pacas, por sesión y bajo demanda: { [id]: {abierto, cargando, filas, error} }.
     No se piden todos al entrar — son N peticiones para algo que casi nunca se abre. */
  const [historial, setHistorial] = useState({});
  /* Ediciones en curso de un registro: { [pallet_id]: {cantidad_pacas?, fecha_hora?} } */
  const [edicion, setEdicion] = useState({});

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

  /* ── Historial de pacas de una sesión ───────────────── */

  const cargarHistorial = async (sesion) => {
    setHistorial((prev) => ({ ...prev, [sesion.id]: { ...prev[sesion.id], abierto: true, cargando: true } }));
    try {
      const { data } = await admin.get(`/sesiones/${sesion.id}/pallets`);
      setHistorial((prev) => ({
        ...prev,
        [sesion.id]: { abierto: true, cargando: false, filas: Array.isArray(data) ? data : [] },
      }));
    } catch (err) {
      setHistorial((prev) => ({
        ...prev,
        [sesion.id]: { abierto: true, cargando: false, filas: [], error: mensajeDeError(err) },
      }));
    }
  };

  const alternarHistorial = (sesion) => {
    const actual = historial[sesion.id];
    if (actual?.abierto) {
      setHistorial((prev) => ({ ...prev, [sesion.id]: { ...actual, abierto: false } }));
      return;
    }
    cargarHistorial(sesion);
  };

  const editarRegistro = (pallet, campo, valor) =>
    setEdicion((prev) => ({ ...prev, [pallet.id]: { ...prev[pallet.id], [campo]: valor } }));

  const guardarRegistro = async (sesion, pallet) => {
    const cambios = {};
    const pendiente = edicion[pallet.id] || {};
    if (pendiente.cantidad_pacas !== undefined &&
        Number(pendiente.cantidad_pacas) !== Number(pallet.cantidad_pacas)) {
      const cantidad = Number(pendiente.cantidad_pacas);
      if (!Number.isFinite(cantidad) || cantidad < 0) {
        fallo('La cantidad no puede ser negativa');
        return;
      }
      cambios.cantidad_pacas = cantidad;
    }
    /* Solo viaja la hora si se tocó: el input `datetime-local` no lleva segundos y
       reenviarla sin cambios los pondría a cero sin que nadie lo haya pedido. */
    if (pendiente.fecha_hora !== undefined &&
        pendiente.fecha_hora !== paraInput(pallet.fecha_hora)) {
      if (!pendiente.fecha_hora) {
        fallo('La fecha y hora no pueden quedar vacías');
        return;
      }
      cambios.fecha_hora = pendiente.fecha_hora.replace('T', ' ');
    }
    if (Object.keys(cambios).length === 0) {
      fallo('No hay cambios en ese registro');
      return;
    }

    setOcupado({ id: sesion.id, accion: `pallet-${pallet.id}` });
    try {
      await admin.put(`/pallets/${pallet.id}`, cambios);
      ok(`Registro #${pallet.id} actualizado`);
      setEdicion((prev) => {
        const resto = { ...prev };
        delete resto[pallet.id];
        return resto;
      });
      await cargarHistorial(sesion);
      sesiones.recargar();      // el total de la sesión lo recalcula el backend
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const eliminarRegistro = async (sesion, pallet) => {
    if (!window.confirm(
      `¿Eliminar el registro de ${num(pallet.cantidad_pacas)} pacas de las ${horaDe(pallet.fecha_hora)}?\n\n` +
      'No se puede deshacer. Si solo quieres anularlo conservando la traza, ponle 0 pacas.'
    )) return;

    setOcupado({ id: sesion.id, accion: `pallet-${pallet.id}` });
    try {
      await admin.delete(`/pallets/${pallet.id}`);
      ok(`Registro #${pallet.id} eliminado`);
      await cargarHistorial(sesion);
      sesiones.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  /* ¿Hay alguien todavía trabajando en esa tablet? Solo lo sabemos de las activas.
     Aquí NO basta con `tablet_online` (que es tener el WebSocket abierto ahora): una
     tablet puede estar produciendo con la conexión caída y sus pacas siguen llegando,
     que es justo el caso en que cerrar el turno hace daño. Por eso cuenta también el
     último contacto reciente, aunque ahora mismo no esté conectada. */
  const CONTACTO_RECIENTE_S = 30 * 60;
  const tabletEnLinea = (sesion) => {
    const activa = (Array.isArray(activas.datos) ? activas.datos : []).find(
      (a) => a.sesion_id === sesion.id,
    );
    if (!activa) return false;
    if (activa.tablet_online) return true;
    const contacto = activa.segundos_desde_contacto;
    return typeof contacto === 'number' && contacto <= CONTACTO_RECIENTE_S;
  };

  const cerrarTurno = async (sesion) => {
    const enLinea = tabletEnLinea(sesion);
    const aviso = enLinea
      ? '\n\n⚠ ESTA TABLET HA DADO SEÑALES DE VIDA HACE POCO.\nSi hay alguien trabajando, sus pacas se seguirán registrando en un turno ya cerrado y su botón de finalizar dará error. Ciérralo solo si el turno quedó abandonado.'
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
            const detalle = historial[sesion.id];
            const abierto = detalle?.abierto;
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
                  {/* El total abre el detalle: es donde uno mira cuando la cifra no
                      cuadra, así que es el sitio natural del desplegable. */}
                  <button
                    type="button"
                    onClick={() => alternarHistorial(sesion)}
                    aria-expanded={Boolean(abierto)}
                    aria-label={`Registros de pacas de la sesión ${sesion.id}`}
                    className="sig-meta flex items-center gap-2 rounded-lg bg-sig-input px-3 py-1.5
                               text-sig-muted transition-colors hover:text-sig-text"
                  >
                    <ChevronDown
                      size={12}
                      aria-hidden="true"
                      className={`transition-transform ${abierto ? 'rotate-180' : ''}`}
                    />
                    Pacas: {num(sesion.total_pacas)} ({num(sesion.n_registros)} reg.)
                  </button>
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

                {/* Historial de pacas: una fila por registro que mandó la tablet.
                    Cambiar la hora NO es cosmético — el dashboard cuenta la
                    producción por `pallets.fecha_hora`, así que mueve el registro de
                    hora y de día en KPIs, gráfico y Excel. */}
                {abierto && (
                  <div className="border-t border-sig-line bg-black/10 px-5 py-3">
                    {detalle.cargando ? (
                      <Label caja="normal" className="text-sig-dim">Cargando registros…</Label>
                    ) : detalle.error ? (
                      <Label caja="normal" className="text-sig-dim">{detalle.error}</Label>
                    ) : detalle.filas.length === 0 ? (
                      <Label caja="normal" className="text-sig-dim">
                        Esta sesión no tiene pacas registradas
                      </Label>
                    ) : (
                      <>
                        <div className="mb-2 flex items-center gap-3">
                          <Label className="text-sig-muted">Registros de pacas</Label>
                          <Label caja="normal" className="text-sig-dim">
                            cambiar la hora mueve la producción de hora y de día en el dashboard
                          </Label>
                        </div>
                        <ul className="space-y-2">
                          {detalle.filas.map((pallet) => {
                            const enCurso = ocupado?.accion === `pallet-${pallet.id}`;
                            const pendiente = edicion[pallet.id] || {};
                            return (
                              <li key={pallet.id} className="flex flex-wrap items-center gap-2.5">
                                <Label caja="normal" className="w-[64px] shrink-0 text-sig-dim">
                                  #{pallet.id}
                                </Label>
                                <Input
                                  type="number"
                                  min="0"
                                  value={pendiente.cantidad_pacas ?? pallet.cantidad_pacas}
                                  onChange={(e) => editarRegistro(pallet, 'cantidad_pacas', e.target.value)}
                                  disabled={!editable || enCurso}
                                  aria-label={`Pacas del registro ${pallet.id}`}
                                  className="w-[96px]"
                                />
                                <Label caja="normal" className="text-sig-dim">pacas</Label>
                                <Input
                                  type="datetime-local"
                                  value={pendiente.fecha_hora ?? paraInput(pallet.fecha_hora)}
                                  onChange={(e) => editarRegistro(pallet, 'fecha_hora', e.target.value)}
                                  disabled={!editable || enCurso}
                                  aria-label={`Fecha y hora del registro ${pallet.id}`}
                                  className="w-[210px]"
                                />
                                <div className="ml-auto flex items-center gap-2">
                                  {editable && (
                                    <Button
                                      tamano="sm"
                                      onClick={() => guardarRegistro(sesion, pallet)}
                                      disabled={enCurso}
                                    >
                                      {enCurso ? 'Guardando…' : 'Guardar'}
                                    </Button>
                                  )}
                                  {puedeEliminar && (
                                    <Button
                                      tamano="sm"
                                      onClick={() => eliminarRegistro(sesion, pallet)}
                                      disabled={enCurso}
                                      title="Borra el registro. Para anularlo conservando la traza, ponle 0 pacas"
                                    >
                                      Eliminar
                                    </Button>
                                  )}
                                </div>
                              </li>
                            );
                          })}
                        </ul>
                      </>
                    )}
                  </div>
                )}
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
