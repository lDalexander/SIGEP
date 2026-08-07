import React, { useState } from 'react';
import { Card, Badge, Button, Estado, Input, Select, Aviso, useAviso, Label, Campo } from '../ui';
import { duracionSeg, plural } from '../../lib/format';
import useApi from '../../lib/useApi';
import { admin, mensajeDeError, esSuperadmin, puedeEditar } from '../../lib/adminApi';
import FiltroRango from './FiltroRango';

/* Categorías que la tablet manda hoy, para ofrecerlas en el desplegable. NO es una
   lista cerrada: el campo acepta escribir una nueva, porque la app puede empezar a
   enviar otra cualquier día y el editor no debe impedir corregir un paro por eso. */
const CATEGORIAS = [
  'MANTENIMIENTO', 'BODEGA', 'PLANEACIÓN', 'ASEO/LIMPIEZA', 'ALMUERZO', 'OTRO',
];

const TONO_ESTADO = { CERRADO: 'gray', 'EN CURSO': 'amber', 'SIN CIERRE': 'amber' };

/** "2026-08-07 11:52:21" -> "2026-08-07T11:52", que es lo que espera datetime-local. */
function paraInput(valor) {
  if (!valor) return '';
  return valor.replace(' ', 'T').slice(0, 16);
}

/**
 * Pestaña «Paros» — corregir lo que las tablets registraron.
 *
 * Existe porque hasta el 2026-08-07 esto solo se podía hacer entrando a MySQL: así se
 * borró el paro 105 sin ver de qué sesión colgaba. Aquí cada acción deja traza en el
 * log del servicio y enseña a qué turno pertenece antes de tocar nada.
 *
 * Ojo con las horas: los KPIs, la vista /paros y el reporte semanal cuentan por
 * `inicio_paro`, así que **cambiar el inicio mueve el paro de día y de semana**.
 */
export default function TabParos() {
  const [rango, setRango] = useState(() => {
    const hoy = new Date().toISOString().slice(0, 10);
    return { desde: hoy, hasta: hoy };
  });
  const [aplicado, setAplicado] = useState(rango);
  const { datos, cargando, error, recargar } = useApi('/paros', {
    params: aplicado, cliente: admin,
  });
  const { aviso, ok, fallo } = useAviso();

  const [abierto, setAbierto] = useState(null);   // id del paro desplegado
  const [borrador, setBorrador] = useState({});
  const [ocupado, setOcupado] = useState(null);

  const editable = puedeEditar();
  const puedeBorrar = esSuperadmin();
  const lista = Array.isArray(datos) ? datos : [];

  const desplegar = (paro) => {
    if (abierto === paro.id) {
      setAbierto(null);
      return;
    }
    setAbierto(paro.id);
    /* El borrador se rellena al abrir y no se vuelve a tocar: si se recargara con cada
       refresco, borraría lo que se esté escribiendo. */
    setBorrador({
      categoria: paro.categoria || '',
      comentario: paro.comentario || '',
      inicio_paro: paraInput(paro.inicio_paro),
      fin_paro: paraInput(paro.fin_paro),
    });
  };

  const guardar = async (paro) => {
    /* Solo viaja lo que cambió. Reenviar una hora intacta la volvería a parsear y le
       pondría los segundos a cero — el mismo cuidado que en el historial de pacas. */
    const cambios = {};
    if (borrador.categoria !== (paro.categoria || '')) cambios.categoria = borrador.categoria;
    if (borrador.comentario !== (paro.comentario || '')) cambios.comentario = borrador.comentario;
    if (borrador.inicio_paro !== paraInput(paro.inicio_paro)) cambios.inicio_paro = borrador.inicio_paro;
    if (borrador.fin_paro !== paraInput(paro.fin_paro)) cambios.fin_paro = borrador.fin_paro;

    if (Object.keys(cambios).length === 0) {
      fallo('No has cambiado nada');
      return;
    }
    if (cambios.inicio_paro && !window.confirm(
      `¿Mover el inicio del paro #${paro.id}?\n\n` +
      'Los KPIs, la vista de paros y el reporte semanal cuentan por la hora de inicio, ' +
      'así que el paro puede cambiar de día y de semana.'
    )) return;

    setOcupado(paro.id);
    try {
      await admin.put(`/paros/${paro.id}`, cambios);
      ok(`Paro #${paro.id} corregido`);
      setAbierto(null);
      recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const cerrar = async (paro) => {
    if (!window.confirm(
      `¿Cerrar el paro #${paro.id} (${paro.categoria}) ahora mismo?\n\n` +
      'Se registrará la hora actual como fin y se calculará su duración. ' +
      'Si conoces la hora real, edítala en su lugar.'
    )) return;
    setOcupado(paro.id);
    try {
      await admin.post(`/paros/${paro.id}/cerrar`, {});
      ok(`Paro #${paro.id} cerrado`);
      recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const eliminar = async (paro) => {
    if (!window.confirm(
      `¿ELIMINAR el paro #${paro.id}?\n\n` +
      `${paro.categoria} · ${paro.maquina} · ${paro.inicio_paro}\n\n` +
      'Es un borrado definitivo: desaparece de los KPIs, de la vista de paros y del ' +
      'reporte semanal. Para anularlo sin destruirlo, corrige sus horas.'
    )) return;
    setOcupado(paro.id);
    try {
      await admin.delete(`/paros/${paro.id}`);
      ok(`Paro #${paro.id} eliminado`);
      recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const abiertos = lista.filter((p) => p.estado !== 'CERRADO').length;
  const totalSegundos = lista.reduce((s, p) => s + (p.duracion_segundos || 0), 0);

  return (
    <div>
      <div className="flex items-start justify-between gap-4 mb-5">
        <Label caja="normal" className="text-sig-dim">
          Corregir lo que registraron las tablets · cambiar la hora de inicio mueve el
          paro de día y de semana en los KPIs y en el reporte
        </Label>
      </div>

      <FiltroRango
        desde={rango.desde}
        hasta={rango.hasta}
        onChange={(campo, valor) => setRango((prev) => ({ ...prev, [campo]: valor }))}
        onCargar={() => setAplicado({ ...rango })}
        contador={plural(lista.length, 'paro', 'paros')}
      />

      <Aviso aviso={aviso} className="mb-4" />

      <Card
        titulo="Paros del rango"
        meta={
          <Label className="text-sig-dim">
            {duracionSeg(totalSegundos)} en total
            {abiertos > 0 ? ` · ${abiertos} sin cerrar` : ''}
          </Label>
        }
        sinPad
      >
        {lista.length === 0 ? (
          <Estado cargando={cargando} error={error} vacio="Sin paros en este rango" />
        ) : (
          <ul className="divide-y divide-sig-line">
            {lista.map((paro) => (
              <li key={paro.id}>
                <div className="flex items-center gap-4 px-5 py-3.5">
                  <button
                    type="button"
                    onClick={() => desplegar(paro)}
                    className="min-w-0 flex-1 text-left"
                    aria-expanded={abierto === paro.id}
                    aria-label={`Paro ${paro.id}`}
                  >
                    <Label className="block truncate">
                      <span className="text-sig-dim mr-2">#{paro.id}</span>
                      {paro.categoria} · {paro.maquina}
                    </Label>
                    <Label className="block mt-1 truncate text-sig-dim">
                      {paro.inicio_paro} → {paro.fin_paro || 'sin cerrar'}
                      {paro.comentario ? ` · ${paro.comentario}` : ''}
                    </Label>
                  </button>

                  <div className="shrink-0 text-right">
                    <span className="text-[13px] font-semibold text-sig-text">
                      {duracionSeg(paro.duracion_segundos)}
                    </span>
                    <Badge tono={TONO_ESTADO[paro.estado] || 'gray'} className="ml-2">
                      {paro.estado}
                    </Badge>
                  </div>
                </div>

                {abierto === paro.id && (
                  <div className="border-t border-sig-line bg-sig-input/40 px-5 py-4">
                    {!paro.sesion_existe && (
                      <Label className="block mb-3 text-sig-amber">
                        Este paro no tiene turno asociado: su sesión ya no existe.
                      </Label>
                    )}
                    {paro.duracion_estimada && (
                      <Label className="block mb-3 text-sig-amber">
                        Duración estimada: el paro no tiene hora de fin y se acota al fin
                        del turno. Ciérralo para dejarla registrada.
                      </Label>
                    )}

                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                      <Campo etiqueta="Categoría">
                        <Select
                          value={borrador.categoria}
                          onChange={(e) => setBorrador((b) => ({ ...b, categoria: e.target.value }))}
                          disabled={!editable}
                          aria-label={`Categoría del paro ${paro.id}`}
                        >
                          {/* La categoría actual va primero aunque no esté en la lista:
                              si no, editar el comentario de un paro con una categoría
                              nueva la cambiaría sin querer. */}
                          {[paro.categoria, ...CATEGORIAS.filter((c) => c !== paro.categoria)]
                            .filter(Boolean)
                            .map((c) => <option key={c} value={c}>{c}</option>)}
                        </Select>
                      </Campo>
                      <Campo etiqueta="Comentario del operario">
                        <Input
                          value={borrador.comentario}
                          onChange={(e) => setBorrador((b) => ({ ...b, comentario: e.target.value }))}
                          disabled={!editable}
                          placeholder="sin comentario"
                          aria-label={`Comentario del paro ${paro.id}`}
                        />
                      </Campo>
                      <Campo etiqueta="Inicio">
                        <Input
                          type="datetime-local"
                          value={borrador.inicio_paro}
                          onChange={(e) => setBorrador((b) => ({ ...b, inicio_paro: e.target.value }))}
                          disabled={!editable}
                          aria-label={`Inicio del paro ${paro.id}`}
                        />
                      </Campo>
                      <Campo etiqueta="Fin (vacío = sigue abierto)">
                        <Input
                          type="datetime-local"
                          value={borrador.fin_paro}
                          onChange={(e) => setBorrador((b) => ({ ...b, fin_paro: e.target.value }))}
                          disabled={!editable}
                          aria-label={`Fin del paro ${paro.id}`}
                        />
                      </Campo>
                    </div>

                    <Label className="block mt-3 text-sig-dim">
                      Turno {paro.sesion_id ?? '—'} · {paro.operador} · motivo original:{' '}
                      {paro.motivo || '—'}
                    </Label>

                    {editable && (
                      <div className="flex flex-wrap gap-2.5 mt-4">
                        <Button
                          variante="primary"
                          onClick={() => guardar(paro)}
                          disabled={ocupado === paro.id}
                          className="px-5 py-2.5"
                        >
                          Guardar cambios
                        </Button>
                        {paro.estado !== 'CERRADO' && (
                          <Button onClick={() => cerrar(paro)} disabled={ocupado === paro.id}>
                            Cerrar ahora
                          </Button>
                        )}
                        {puedeBorrar && (
                          <Button onClick={() => eliminar(paro)} disabled={ocupado === paro.id}>
                            Eliminar
                          </Button>
                        )}
                      </div>
                    )}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
