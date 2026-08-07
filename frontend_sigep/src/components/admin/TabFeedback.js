import React, { useState } from 'react';
import { Card, Badge, Button, Estado, Textarea, Checkbox, Aviso, useAviso, Label } from '../ui';
import { plural } from '../../lib/format';
import useApi from '../../lib/useApi';
import { admin, mensajeDeError, esSuperadmin, puedeEditar } from '../../lib/adminApi';

/**
 * Pestaña «Reportes» — lo que escriben los operarios desde la tablet.
 *
 * Dos cosas distintas que comparten pantalla porque comparten origen y forma:
 * los **reportes de problemas con la app** (que además llegan por correo y hasta ahora
 * se perdían en el buzón) y los **comentarios de turno**.
 *
 * Los reportes **se atienden, no se borran**: el historial de qué falló en las tablets
 * es justo lo que no queda en ningún sitio hoy. Eliminar existe para los de prueba y
 * es solo de SUPERADMIN.
 *
 * Ninguna de las dos listas va atada a un rango de fechas: son esporádicos —uno por
 * turno como mucho— y con el rango puesto en hoy la pantalla saldría vacía casi
 * siempre. Mismo criterio que las tarjetas del dashboard.
 */
export default function TabFeedback() {
  const [soloPendientes, setSoloPendientes] = useState(false);
  const reportes = useApi('/reportes_app', {
    params: { limit: 100, solo_pendientes: soloPendientes },
    cliente: admin,
  });
  const comentarios = useApi('/comentarios', { params: { limit: 100 }, cliente: admin });
  const { aviso, ok, fallo } = useAviso();

  const [editando, setEditando] = useState(null);   // `${tipo}${id}` en edición
  const [texto, setTexto] = useState('');
  const [ocupado, setOcupado] = useState(null);

  const editable = puedeEditar();
  const puedeBorrar = esSuperadmin();
  const listaReportes = Array.isArray(reportes.datos) ? reportes.datos : [];
  const listaComentarios = Array.isArray(comentarios.datos) ? comentarios.datos : [];
  const pendientes = listaReportes.filter((r) => !r.atendido).length;

  const abrirEdicion = (tipo, item) => {
    setEditando(`${tipo}${item.id}`);
    setTexto(item.texto || '');
  };

  const guardar = async (tipo, item) => {
    const limpio = texto.trim();
    if (!limpio) {
      fallo('El texto no puede quedar vacío. Para quitarlo, elimínalo.');
      return;
    }
    if (limpio === (item.texto || '')) {
      setEditando(null);
      return;
    }
    setOcupado(`${tipo}${item.id}`);
    try {
      const ruta = tipo === 'r' ? `/reportes_app/${item.id}` : `/comentarios/${item.id}`;
      await admin.put(ruta, { texto: limpio });
      ok('Texto corregido');
      setEditando(null);
      (tipo === 'r' ? reportes : comentarios).recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const atender = async (reporte, atendido) => {
    setOcupado(`r${reporte.id}`);
    try {
      await admin.put(`/reportes_app/${reporte.id}`, { atendido });
      ok(atendido ? `Reporte #${reporte.id} marcado como atendido` : `Reporte #${reporte.id} reabierto`);
      reportes.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const eliminar = async (tipo, item) => {
    const que = tipo === 'r' ? 'reporte' : 'comentario';
    if (!window.confirm(
      `¿ELIMINAR el ${que} #${item.id}?\n\n` +
      `${item.maquina} · ${item.operador}\n«${(item.texto || '').slice(0, 120)}»\n\n` +
      (tipo === 'r'
        ? 'Es un borrado definitivo. Si el problema ya está resuelto, márcalo como atendido en vez de borrarlo: así queda el historial.'
        : 'Es un borrado definitivo.')
    )) return;
    setOcupado(`${tipo}${item.id}`);
    try {
      const ruta = tipo === 'r' ? `/reportes_app/${item.id}` : `/comentarios/${item.id}`;
      await admin.delete(ruta);
      ok(`${que.charAt(0).toUpperCase()}${que.slice(1)} #${item.id} eliminado`);
      (tipo === 'r' ? reportes : comentarios).recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  /* Una fila de cualquiera de las dos listas: mismo formato, distintas acciones. */
  const fila = (tipo, item, extra = null) => {
    const clave = `${tipo}${item.id}`;
    const enEdicion = editando === clave;
    return (
      <li key={item.id} className="px-5 py-3.5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0 flex-1">
            <Label className="block truncate">
              <span className="text-sig-dim mr-2">#{item.id}</span>
              {item.maquina} · {item.operador}
            </Label>
            <Label className="block mt-1 text-sig-dim">
              {item.creado_en}{item.sesion_id ? ` · turno ${item.sesion_id}` : ' · sin turno'}
            </Label>
          </div>
          {extra}
        </div>

        {enEdicion ? (
          <div className="mt-3">
            <Textarea
              value={texto}
              onChange={(e) => setTexto(e.target.value)}
              maxLength={1000}
              aria-label={`Texto del ${tipo === 'r' ? 'reporte' : 'comentario'} ${item.id}`}
            />
            <div className="flex flex-wrap gap-2.5 mt-2.5">
              <Button
                variante="primary"
                onClick={() => guardar(tipo, item)}
                disabled={ocupado === clave}
              >
                Guardar
              </Button>
              <Button onClick={() => setEditando(null)}>Cancelar</Button>
            </div>
          </div>
        ) : (
          <p className="mt-2 whitespace-pre-wrap text-[13px] leading-relaxed text-sig-text">
            {item.texto}
          </p>
        )}

        {editable && !enEdicion && (
          <div className="flex flex-wrap gap-2.5 mt-3">
            <Button onClick={() => abrirEdicion(tipo, item)}>Corregir texto</Button>
            {tipo === 'r' && (
              <Button onClick={() => atender(item, !item.atendido)} disabled={ocupado === clave}>
                {item.atendido ? 'Reabrir' : 'Marcar atendido'}
              </Button>
            )}
            {puedeBorrar && (
              <Button onClick={() => eliminar(tipo, item)} disabled={ocupado === clave}>
                Eliminar
              </Button>
            )}
          </div>
        )}
      </li>
    );
  };

  return (
    <div>
      <div className="flex items-start justify-between gap-4 mb-5">
        <Label caja="normal" className="text-sig-dim">
          Lo que escriben los operarios desde la tablet · los reportes de la app se
          atienden en vez de borrarse, para que quede el historial de qué falló
        </Label>
        <Button
          onClick={() => { reportes.recargar(); comentarios.recargar(); }}
          className="shrink-0"
        >
          Recargar
        </Button>
      </div>

      <Aviso aviso={aviso} className="mb-4" />

      <div className="space-y-5">
        <Card
          titulo="Reportes de problemas con la app"
          meta={
            <div className="flex items-center gap-3">
              <Badge tono={pendientes > 0 ? 'amber' : 'ok'}>
                {pendientes > 0 ? `${pendientes} sin atender` : 'todo atendido'}
              </Badge>
              <Checkbox
                checked={soloPendientes}
                onChange={(e) => setSoloPendientes(e.target.checked)}
                etiqueta={<Label>Solo pendientes</Label>}
              />
            </div>
          }
          sinPad
        >
          {listaReportes.length === 0 ? (
            <Estado
              cargando={reportes.cargando}
              error={reportes.error}
              vacio={soloPendientes ? 'No queda ningún reporte pendiente' : 'Sin reportes registrados'}
            />
          ) : (
            <ul className="divide-y divide-sig-line">
              {listaReportes.map((r) => fila('r', r,
                <Badge tono={r.atendido ? 'ok' : 'amber'}>
                  {r.atendido ? `atendido por ${r.atendido_por || '—'}` : 'pendiente'}
                </Badge>
              ))}
            </ul>
          )}
        </Card>

        <Card
          titulo="Comentarios de turno"
          meta={<Label className="text-sig-dim">{plural(listaComentarios.length, 'comentario', 'comentarios')}</Label>}
          sinPad
        >
          {listaComentarios.length === 0 ? (
            <Estado
              cargando={comentarios.cargando}
              error={comentarios.error}
              vacio="Sin comentarios registrados"
            />
          ) : (
            <ul className="divide-y divide-sig-line">
              {listaComentarios.map((c) => fila('c', c))}
            </ul>
          )}
        </Card>
      </div>
    </div>
  );
}
