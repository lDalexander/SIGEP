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

  /* Cerrar un paro EN CURSO y cerrar uno SIN CIERRE se parecen en la pantalla y no son
     lo mismo, así que el aviso tampoco lo es. «SIN CIERRE» es para lo que se hizo este
     botón: el turno ya acabó y el paro se quedó colgado. «EN CURSO» significa que hay
     una tablet con el paro abierto AHORA, y su estado de paro es local: no se enterará
     de este cierre y se quedará sin poder reanudar. Pasó el 2026-08-19 con la Máquina 7
     —una hora sin registrar producción— y es la razón de que este aviso exista. */
  const cerrar = async (paro) => {
    const enVivo = paro.estado === 'EN CURSO';
    const texto = enVivo
      ? `El paro #${paro.id} está EN CURSO en una tablet.\n\n` +
        `${paro.categoria} · ${paro.maquina} · ${paro.operador}\n\n` +
        'Lo normal es que lo cierre el operario desde la tablet. Si lo cierras aquí, la ' +
        'tablet no se entera: su botón se queda en verde y al pulsar «reanudar» le dirá ' +
        'que no hay paro activo.\n\n' +
        'Ciérralo desde aquí solo si esa tablet ya no va a hacerlo. Si el operario sigue ' +
        'trabajando, avísale de que cierre la app y la vuelva a abrir, o reabre el paro ' +
        'después para que pueda reanudar él.\n\n' +
        `¿Cerrar el paro #${paro.id} de todas formas?`
      : `¿Cerrar el paro #${paro.id} (${paro.categoria}) ahora mismo?\n\n` +
        'Se registrará la hora actual como fin y se calculará su duración. ' +
        'Si conoces la hora real, edítala en su lugar.';
    if (!window.confirm(texto)) return;
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

  /* Reabrir = `PUT {fin_paro: ''}`, que el backend entiende como «déjalo abierto otra
     vez». Ya se podía hacer vaciando el campo «Fin» y guardando, pero eso hay que
     adivinarlo, y es justo la maniobra que hace falta con prisa: es la contingencia
     cuando una tablet se quedó atascada porque su paro se cerró por detrás. */
  const reabrir = async (paro) => {
    if (!window.confirm(
      `¿Reabrir el paro #${paro.id} (${paro.categoria} · ${paro.maquina})?\n\n` +
      'Vuelve a quedar sin hora de fin y sin duración, como si siguiera en curso.\n\n' +
      'Sirve para desatascar una tablet que se quedó con el botón verde de reanudar y ' +
      'responde «no hay paro activo»: al reabrirlo, el operario reanuda desde la tablet ' +
      'y la hora de fin queda registrada con la real.'
    )) return;
    setOcupado(paro.id);
    try {
      await admin.put(`/paros/${paro.id}`, { fin_paro: '' });
      ok(`Paro #${paro.id} reabierto · que el operario reanude desde la tablet`);
      setAbierto(null);
      recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  /* La foto la toma una persona con prisa: sale movida, sale la pared, o sale alguien que
     no tiene por qué salir. Borrar el paro entero para arreglar eso perdería el registro
     del tiempo parado, que es el dato que importa. */
  const quitarFoto = async (paro) => {
    if (!window.confirm(
      `¿Quitar la foto del paro #${paro.id}?\n\n` +
      'El paro y su duración se quedan como están; solo desaparece la imagen, también del ' +
      'servidor. No se puede recuperar.'
    )) return;
    setOcupado(paro.id);
    try {
      await admin.delete(`/paros/${paro.id}/foto`);
      ok(`Foto del paro #${paro.id} eliminada`);
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
          paro de día y de semana en los KPIs y en el reporte · si una tablet dice «no hay
          paro activo» al reanudar, reabre aquí su paro o pídele que reinicie la app
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
                    {paro.estado === 'EN CURSO' && (
                      <Label className="block mb-3 text-sig-amber">
                        La tablet tiene este paro abierto ahora mismo y su botón de
                        reanudar es local: no se entera si lo cierras aquí. Deja que lo
                        cierre el operario salvo que esa tablet ya no vaya a hacerlo.
                      </Label>
                    )}

                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                      <Campo etiqueta="Categoría">
                        {/* `Select` recibe `opciones` (no children) y antepone solo el
                            valor actual si no está en la lista, así que una categoría
                            que la app empiece a mandar no se pierde al editar. */}
                        <Select
                          value={borrador.categoria}
                          opciones={CATEGORIAS}
                          vacio={null}
                          onChange={(e) => setBorrador((b) => ({ ...b, categoria: e.target.value }))}
                          disabled={!editable}
                          aria-label={`Categoría del paro ${paro.id}`}
                        />
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

                    {paro.foto_url && (
                      <div className="mt-4">
                        <Label className="block mb-1.5">Evidencia fotográfica</Label>
                        <a
                          href={paro.foto_url}
                          target="_blank"
                          rel="noreferrer"
                          className="inline-block rounded-sig border border-sig-line overflow-hidden hover:border-sig-amber/60 transition-colors"
                          title="Abrir la foto a tamaño completo"
                        >
                          <img
                            src={paro.foto_url}
                            alt={`Evidencia del paro ${paro.id}`}
                            className="block h-28 w-auto max-w-full object-cover"
                            loading="lazy"
                          />
                        </a>
                      </div>
                    )}

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
                        {paro.estado !== 'CERRADO' ? (
                          <Button onClick={() => cerrar(paro)} disabled={ocupado === paro.id}>
                            Cerrar ahora
                          </Button>
                        ) : (
                          <Button onClick={() => reabrir(paro)} disabled={ocupado === paro.id}>
                            Reabrir
                          </Button>
                        )}
                        {paro.foto_url && (
                          <Button onClick={() => quitarFoto(paro)} disabled={ocupado === paro.id}>
                            Quitar foto
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
