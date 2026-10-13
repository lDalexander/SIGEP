import React, { useMemo, useState } from 'react';
import { Card, Badge, Label, Estado, StatCard, Cifra, ProgressBar, FotoEvidencia, Input, Button } from './ui';
import useApi from '../lib/useApi';

/**
 * Vista `/insumos` — toda la actividad de bodega en una página.
 *
 * Hasta ahora esta ruta existía en la cabecera y no mostraba nada: un recuadro que
 * decía «Vista sin especificación de referencia». Lo de insumos estaba repartido entre
 * una tarjeta del dashboard (solo las solicitudes) y la pestaña de administración (solo
 * corregir cantidades), así que para saber si la planta estaba abastecida había que
 * mirar en dos sitios y ninguno contestaba del todo.
 *
 * El orden de la página responde a esa pregunta de arriba abajo: ¿está cubierto lo que
 * vamos a producir? → ¿hay alguien en bodega? → ¿qué se pidió y cuánto tardó? → ¿qué se
 * dejó preparado y con qué evidencia?
 *
 * ES PÚBLICA, como el resto del centro de control. Por eso NO trae el chat: los
 * mensajes exigen identificarse, y aquí no hay sesión. El chat vive donde están sus dos
 * extremos — la tablet del supervisor y la del insumista.
 */

const hoyISO = () => new Date().toISOString().slice(0, 10);

/** Segundos → «2m 15s». Un número crudo de segundos no se lee de un vistazo. */
function duracion(seg) {
  if (seg === null || seg === undefined) return '—';
  if (seg < 60) return `${seg}s`;
  const m = Math.floor(seg / 60);
  const s = seg % 60;
  if (m < 60) return s ? `${m}m ${s}s` : `${m}m`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
}

function tonoEstadoPedido(estado) {
  const e = String(estado || '').toUpperCase();
  if (e === 'ENTREGADO') return 'ok';
  if (e === 'CERRADO_FIN_TURNO') return 'gray';
  return 'amber';
}

function tonoCobertura(pct) {
  if (pct === null || pct === undefined) return 'gray';
  if (pct >= 100) return 'ok';
  if (pct >= 50) return 'amber';
  return 'bad';
}

/** Una cantidad que no cuadra es lo que hay que mirar primero. */
function hayDiscrepancia(p) {
  return [p.solicitada, p.entregada, p.recibida]
    .filter((v) => v !== null && v !== undefined)
    .some((v, _, todos) => v !== todos[0]);
}

/* ── Plan del día ──────────────────────────────────────────────────────────── */

function CoberturaDelPlan({ planes, cargando, error }) {
  return (
    <Card titulo="Plan de producción de hoy" meta="qué hace falta y qué ya está puesto">
      {planes.length === 0 ? (
        <Estado
          cargando={cargando}
          error={error}
          vacio="No hay producción planificada para hoy. Se carga en Admin → Planificación."
        />
      ) : (
        <ul className="divide-y divide-sig-line">
          {planes.map((p) => {
            const que = [p.producto, p.marca, p.presentacion, p.fragancia]
              .filter(Boolean).join(' · ') || 'Sin producto definido';
            const faltan = (p.insumos || []).filter((i) => !i.completo);
            return (
              <li key={p.id} className="py-3 first:pt-0">
                <div className="flex flex-wrap items-center gap-2">
                  <p className="text-[13px] font-semibold text-sig-text">{que}</p>
                  {/* «Sin máquina» no es un dato que falte: es producción que se deja
                      preparada para que luego la tome cualquiera. */}
                  <Label>{p.maquina || 'sin máquina asignada'}</Label>
                  {p.hay_urgentes && <Badge tono="bad">urgente sin llegar</Badge>}
                  <span className="ml-auto">
                    <Badge tono={tonoCobertura(p.cobertura)}>
                      {p.cobertura === null ? 'sin insumos' : `${p.cobertura}%`}
                    </Badge>
                  </span>
                </div>

                {p.cobertura !== null && (
                  <div className="mt-2"><ProgressBar valor={p.cobertura} max={100} /></div>
                )}

                {faltan.length > 0 && (
                  <p className="mt-2 text-[12px] text-sig-amber">
                    Falta: {faltan.map((i) => `${i.falta} ${i.unidad || ''} de ${i.insumo}`.trim()).join(' · ')}
                  </p>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}

/* ── Quién está en bodega ──────────────────────────────────────────────────── */

function InsumistasDeTurno({ insumistas, cargando, error }) {
  return (
    <Card titulo="Bodega ahora" meta="quién está de turno">
      {insumistas.length === 0 ? (
        /* Que no haya NADIE es justo el dato que hay que ver, y una lista vacía no lo
           dice: se escribe en ámbar porque un pedido que entre ahora no lo atiende nadie. */
        <Estado
          cargando={cargando}
          error={error}
          vacio="⚠️ Ningún insumista con sesión abierta"
        />
      ) : (
        <ul className="divide-y divide-sig-line">
          {insumistas.map((i) => {
            const perdido = i.inactivo_min >= 10;
            return (
              <li key={i.usuario_id} className="flex items-center gap-3 py-2.5 first:pt-0">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[13px] font-semibold text-sig-text">{i.nombre}</p>
                  <Label className="block mt-0.5 truncate">
                    {i.bodega} · desde {i.desde || '—'}
                    {i.tablet ? ` · ${i.tablet}` : ''}
                  </Label>
                </div>
                <div className="text-right shrink-0">
                  <p className="text-[13px] tabular-nums text-sig-text">
                    {i.pedidos_activos} / {i.entregas_hoy}
                  </p>
                  <Label className="block">activos / entregas</Label>
                </div>
                <Badge tono={perdido ? 'bad' : 'ok'}>
                  {perdido ? `sin señal ${i.inactivo_min}m` : 'activo'}
                </Badge>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}

/* ── Pedidos con sus tiempos ───────────────────────────────────────────────── */

function TablaPedidos({ pedidos, cargando, error }) {
  const [soloProblemas, setSoloProblemas] = useState(false);
  const visibles = soloProblemas ? pedidos.filter(hayDiscrepancia) : pedidos;

  return (
    <Card
      titulo="Pedidos"
      meta={`${pedidos.length} en el rango`}
    >
      <label className="flex items-center gap-2 pb-3 cursor-pointer">
        <input
          type="checkbox"
          checked={soloProblemas}
          onChange={(e) => setSoloProblemas(e.target.checked)}
        />
        <Label>Solo los que no cuadran</Label>
      </label>

      {visibles.length === 0 ? (
        <Estado
          cargando={cargando}
          error={error}
          vacio={soloProblemas ? 'Todas las cantidades cuadran' : 'Sin pedidos en el rango'}
        />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-[12px]">
            <thead>
              <tr className="text-left text-sig-dim">
                <th className="py-1 pr-3">Hora</th>
                <th className="py-1 pr-3">Insumo</th>
                <th className="py-1 pr-3">Máquina</th>
                <th className="py-1 pr-3">Insumista</th>
                <th className="py-1 pr-3 text-right">Sol / Ent / Rec</th>
                {/* Los tres tramos por separado dicen DÓNDE se fue el tiempo: tardar en
                    aceptar no es lo mismo que tardar en llegar. */}
                <th className="py-1 pr-3 text-right">Acepta</th>
                <th className="py-1 pr-3 text-right">Entrega</th>
                <th className="py-1 pr-3 text-right">Total</th>
                <th className="py-1">Estado</th>
              </tr>
            </thead>
            <tbody>
              {visibles.map((p) => (
                <tr key={p.id} className="border-t border-sig-line">
                  <td className="py-1.5 pr-3 font-mono tabular-nums text-sig-dim">
                    {p.hora_solicitud || '—'}
                  </td>
                  <td className="py-1.5 pr-3 text-sig-text">{p.insumo}</td>
                  <td className="py-1.5 pr-3">{p.maquina || <Label>sin sesión</Label>}</td>
                  <td className="py-1.5 pr-3">{p.insumista || '—'}</td>
                  <td className="py-1.5 pr-3 text-right tabular-nums">
                    <span className={hayDiscrepancia(p) ? 'text-sig-amber font-semibold' : ''}>
                      {p.solicitada ?? '—'} / {p.entregada ?? '—'} / {p.recibida ?? '—'}
                    </span>
                  </td>
                  <td className="py-1.5 pr-3 text-right tabular-nums">{duracion(p.seg_aceptacion)}</td>
                  <td className="py-1.5 pr-3 text-right tabular-nums">{duracion(p.seg_entrega)}</td>
                  <td className="py-1.5 pr-3 text-right tabular-nums">{duracion(p.seg_total)}</td>
                  <td className="py-1.5"><Badge tono={tonoEstadoPedido(p.estado)}>{p.estado}</Badge></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

/* ── Entregas proactivas ───────────────────────────────────────────────────── */

function Entregas({ entregas, cargando, error }) {
  return (
    <Card titulo="Entregas sin pedido previo" meta="material dejado por adelantado">
      {entregas.length === 0 ? (
        <Estado cargando={cargando} error={error} vacio="Sin entregas en el rango" />
      ) : (
        <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {entregas.map((e) => (
            <li key={e.id} className="rounded-xl border border-sig-line p-3">
              <div className="flex items-start gap-2">
                <div className="min-w-0 flex-1">
                  <p className="text-[13px] font-semibold text-sig-text">
                    {e.cantidad} x {e.insumo}
                  </p>
                  <Label className="block mt-1">
                    {[e.maquina, e.insumista].filter(Boolean).join(' · ')}
                  </Label>
                  <Label className="block mt-0.5">{e.fecha_hora}</Label>
                </div>
              </div>

              {e.observaciones && (
                <p className="mt-2 text-[12px] italic text-sig-dim">«{e.observaciones}»</p>
              )}

              <div className="mt-2">
                {/* Sin foto no hay forma de comprobar dónde quedó el material, y desde
                    la v1.7.0 el servidor la exige: una entrega sin ella es anterior. */}
                {e.foto_url ? (
                  <FotoEvidencia
                    ruta={e.foto_url}
                    etiqueta={`entrega de ${e.insumo}`}
                    rotulo={null}
                    alto="h-24"
                  />
                ) : (
                  <Badge tono="amber">sin foto</Badge>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

/* ── Página ────────────────────────────────────────────────────────────────── */

export default function InsumosView({ apiBase = '/api', intervalo = 15000 }) {
  const [rango, setRango] = useState({ desde: hoyISO(), hasta: hoyISO() });
  const [aplicado, setAplicado] = useState({ desde: hoyISO(), hasta: hoyISO() });

  const { datos, cargando, error } = useApi(`${apiBase}/insumos/dashboard`, {
    params: aplicado, intervalo,
  });
  // Los planes y la gente de turno son SIEMPRE de hoy, no del rango: mirar la semana
  // pasada no cambia quién está en bodega ahora ni qué se produce esta tarde.
  const hoy = hoyISO();
  const { datos: planesResp, cargando: cargandoPlanes, error: errorPlanes } =
    useApi(`${apiBase}/planificacion/planes`, { params: { desde: hoy, hasta: hoy }, intervalo });
  const { datos: insumistas, cargando: cargandoIns, error: errorIns } =
    useApi(`${apiBase}/supervisor/insumistas`, { intervalo });

  const pedidos = useMemo(() => (Array.isArray(datos?.pedidos) ? datos.pedidos : []), [datos]);
  const entregas = Array.isArray(datos?.entregas) ? datos.entregas : [];
  const planes = Array.isArray(planesResp?.planes) ? planesResp.planes : [];
  const kpis = datos?.kpis || {};

  const sinAtender = useMemo(
    () => pedidos.filter((p) => !p.hora_entrega && p.estado !== 'Cerrado_Fin_Turno').length,
    [pedidos]
  );
  const esHoy = aplicado.desde === hoy && aplicado.hasta === hoy;

  return (
    <div className="py-6 space-y-5">
      <div className="flex flex-wrap items-end gap-3">
        <div>
          <Label>Desde</Label>
          <Input
            type="date" value={rango.desde}
            onChange={(e) => setRango((r) => ({ ...r, desde: e.target.value }))}
          />
        </div>
        <div>
          <Label>Hasta</Label>
          <Input
            type="date" value={rango.hasta}
            onChange={(e) => setRango((r) => ({ ...r, hasta: e.target.value }))}
          />
        </div>
        <Button onClick={() => setAplicado(rango)}>Aplicar</Button>
        <Label className="ml-auto">
          {esHoy ? 'Hoy' : `${aplicado.desde} → ${aplicado.hasta}`}
          {' · '}el plan y la gente de turno son siempre de hoy
        </Label>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard etiqueta="PEDIDOS">
          <Cifra valor={kpis.total_pedidos ?? 0} unidad="en el rango" />
        </StatCard>
        <StatCard etiqueta="RESPUESTA MEDIA" acento="amber">
          <Cifra valor={duracion(kpis.tiempo_resp_prom_seg)} unidad="solicitud → entrega" />
        </StatCard>
        {/* Sin atender es el número accionable del día: los demás son historia. */}
        <StatCard etiqueta="SIN ATENDER" acento={sinAtender > 0 ? 'amber' : 'ok'}>
          <Cifra valor={sinAtender} unidad="pedidos sin entregar" />
        </StatCard>
        <StatCard etiqueta="NO CUADRAN" acento={kpis.con_discrepancia > 0 ? 'amber' : 'ok'}>
          <Cifra valor={kpis.con_discrepancia ?? 0} unidad="pedido / entrega / recibo" />
        </StatCard>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <CoberturaDelPlan planes={planes} cargando={cargandoPlanes} error={errorPlanes} />
        <InsumistasDeTurno
          insumistas={Array.isArray(insumistas) ? insumistas : []}
          cargando={cargandoIns}
          error={errorIns}
        />
      </div>

      <TablaPedidos pedidos={pedidos} cargando={cargando} error={error} />
      <Entregas entregas={entregas} cargando={cargando} error={error} />
    </div>
  );
}
