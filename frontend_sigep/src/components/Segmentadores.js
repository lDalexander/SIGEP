import React, { useEffect, useRef, useState } from 'react';
import { ChevronDown, Check, X, Search } from 'lucide-react';
import { Button, Label } from './ui';
import { DIMENSIONES, contarFiltros } from '../lib/filtros';

/* A partir de este número de valores el desplegable saca un buscador: la lista de
   operarios de la planta no se recorre a ojo. */
const UMBRAL_BUSCADOR = 8;

/**
 * Barra de segmentadores multi-selección — máquina, operario, marca, presentación y
 * fragancia. Va justo debajo del título del dashboard.
 *
 * Se aplica **al instante**, sin pasar por «Cargar»: seleccionar un operario es acotar
 * lo que ya se está viendo, no lanzar una consulta nueva que haya que confirmar (mismo
 * criterio que el botón «Todo el día» de la franja horaria).
 *
 * Ninguna dimensión seleccionada significa «todas», que es el arranque: por eso no hay
 * un valor «Todos» en la lista, sino un botón que vacía la selección de esa dimensión.
 *
 * Props:
 *   opciones : { maquina:[], operador:[], marca:[], presentacion:[], fragancia:[] }
 *              — valores presentes en el rango consultado (`/dashboard/opciones_filtros`)
 *   filtros  : mismas claves, con los valores seleccionados
 *   onChange : (dim, valores[]) => void
 *   onLimpiar: () => void
 *   cargando : primera carga de las opciones
 *   error    : las opciones no se pudieron leer
 *   alcance  : texto que explica a qué tarjetas afecta la segmentación
 */
export default function Segmentadores({
  opciones, filtros, onChange, onLimpiar, cargando = false, error = false, alcance,
}) {
  const [abierta, setAbierta] = useState(null);
  const [busqueda, setBusqueda] = useState('');
  const raizRef = useRef(null);

  /* Un solo desplegable abierto a la vez, y se cierra al pinchar fuera o con Escape:
     es un panel flotante sobre las tarjetas y taparía datos si se quedara abierto. */
  useEffect(() => {
    if (!abierta) return undefined;
    const fuera = (e) => {
      if (raizRef.current && !raizRef.current.contains(e.target)) setAbierta(null);
    };
    const tecla = (e) => { if (e.key === 'Escape') setAbierta(null); };
    document.addEventListener('mousedown', fuera);
    document.addEventListener('keydown', tecla);
    return () => {
      document.removeEventListener('mousedown', fuera);
      document.removeEventListener('keydown', tecla);
    };
  }, [abierta]);

  const alternarPanel = (key) => {
    setAbierta((actual) => (actual === key ? null : key));
    setBusqueda('');
  };

  const alternarValor = (key, valor) => {
    const actuales = filtros?.[key] || [];
    onChange(key, actuales.includes(valor)
      ? actuales.filter((v) => v !== valor)
      : [...actuales, valor]);
  };

  const total = contarFiltros(filtros);

  return (
    <div ref={raizRef} className="sig-card px-4 py-3.5 animate-fade-in">
      <div className="flex flex-wrap items-center gap-2.5">
        <Label className="mr-1">Segmentar por</Label>

        {DIMENSIONES.map(({ key, label }) => {
          const seleccionados = filtros?.[key] || [];
          const disponibles = opciones?.[key] || [];
          /* Un valor seleccionado que ya no aparece en las opciones se sigue mostrando,
             al final y atenuado: el filtro se está aplicando de verdad y hay que poder
             verlo y quitarlo, no dejarlo actuando desde un menú donde no figura. Pasa al
             cambiar de rango y también al combinar dimensiones — si filtras por un
             operario, las máquinas donde no trabajó dejan de tener datos aunque sigan
             marcadas, y entonces esa parte de la selección no está aportando nada. */
          const huerfanos = seleccionados.filter((v) => !disponibles.includes(v));
          const valores = [...disponibles, ...huerfanos];
          const activo = seleccionados.length > 0;
          const estaAbierta = abierta === key;

          const visibles = busqueda
            ? valores.filter((v) => String(v).toLowerCase().includes(busqueda.toLowerCase()))
            : valores;

          return (
            <div key={key} className="relative">
              <button
                type="button"
                onClick={() => alternarPanel(key)}
                aria-expanded={estaAbierta}
                aria-label={`Segmentar por ${label}`}
                className={`inline-flex items-center gap-2 rounded-lg border px-3 py-1.5 text-[13px]
                            font-semibold transition-colors duration-150
                            ${activo
                              ? 'bg-sig-amber/[0.12] text-sig-amber border-sig-amber/30'
                              : 'bg-sig-input text-sig-text border-sig-line hover:border-white/20'}`}
              >
                {label}
                {activo && (
                  <span className="inline-flex items-center justify-center min-w-[17px] h-[17px] px-1
                                   rounded-full bg-sig-amber text-sig-bg font-mono text-[10px] font-bold leading-none">
                    {seleccionados.length}
                  </span>
                )}
                <ChevronDown
                  size={13}
                  aria-hidden="true"
                  className={`transition-transform duration-150 ${estaAbierta ? 'rotate-180' : ''}`}
                />
              </button>

              {estaAbierta && (
                <div className="absolute left-0 z-30 mt-1.5 w-64 rounded-xl border border-sig-line
                                bg-sig-card shadow-[0_12px_40px_rgba(0,0,0,0.65)]">
                  {valores.length > UMBRAL_BUSCADOR && (
                    <div className="relative border-b border-sig-line p-2">
                      <Search
                        size={13}
                        aria-hidden="true"
                        className="absolute left-4 top-1/2 -translate-y-1/2 text-sig-dim"
                      />
                      <input
                        type="text"
                        value={busqueda}
                        onChange={(e) => setBusqueda(e.target.value)}
                        placeholder="Buscar…"
                        aria-label={`Buscar ${label}`}
                        autoFocus
                        className="sig-input w-full py-1.5 pl-7 text-[13px]"
                      />
                    </div>
                  )}

                  <div className="max-h-64 overflow-y-auto p-1.5">
                    {visibles.length === 0 ? (
                      <Label caja="normal" className="block px-2.5 py-4 text-center text-sig-dim">
                        {error
                          ? 'no se pudieron cargar los valores'
                          : cargando
                            ? 'cargando…'
                            : busqueda
                              ? 'sin coincidencias'
                              : 'sin valores en el rango'}
                      </Label>
                    ) : (
                      visibles.map((valor) => {
                        const marcado = seleccionados.includes(valor);
                        const huerfano = huerfanos.includes(valor);
                        return (
                          <button
                            key={valor}
                            type="button"
                            onClick={() => alternarValor(key, valor)}
                            aria-pressed={marcado}
                            /* `title` y no texto dentro del botón: el texto entraría en el
                               nombre accesible y el valor dejaría de poder buscarse por su
                               nombre a secas. */
                            title={huerfano
                              ? 'Sigue filtrando, pero no hay producción con este valor en el resto del filtro'
                              : undefined}
                            className={`flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left
                                       text-[13px] transition-colors hover:bg-white/[0.04]
                                       ${huerfano ? 'opacity-50' : ''}`}
                          >
                            <span
                              aria-hidden="true"
                              className={`flex h-4 w-4 shrink-0 items-center justify-center rounded border
                                          ${marcado
                                            ? 'bg-sig-amber border-sig-amber'
                                            : 'border-sig-dim'}`}
                            >
                              {marcado && <Check size={11} strokeWidth={3} className="text-sig-bg" />}
                            </span>
                            <span className={`truncate ${marcado ? 'text-sig-text font-semibold' : 'text-sig-muted'}`}>
                              {valor}
                            </span>
                          </button>
                        );
                      })
                    )}
                  </div>

                  {/* «Todos» es vaciar la selección de esta dimensión, no marcar todos los
                      valores uno a uno: el resultado es el mismo y la petición sale sin el
                      parámetro, como antes de existir el filtro. */}
                  <div className="flex items-center justify-between gap-2 border-t border-sig-line px-2 py-2">
                    <Label caja="normal" className="text-sig-dim">
                      {seleccionados.length === 0 ? 'todos' : `${seleccionados.length} de ${valores.length}`}
                    </Label>
                    <Button
                      tamano="sm"
                      onClick={() => onChange(key, [])}
                      disabled={seleccionados.length === 0}
                    >
                      Todos
                    </Button>
                  </div>
                </div>
              )}
            </div>
          );
        })}

        {total > 0 && (
          <Button tamano="sm" onClick={onLimpiar} className="ml-auto">
            <X size={12} />
            Limpiar ({total})
          </Button>
        )}
      </div>

      {/* Los valores activos, con su X: hay que poder ver qué se está filtrando sin
          abrir los cinco desplegables uno a uno. */}
      {total > 0 && (
        <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-sig-line pt-3">
          {DIMENSIONES.map(({ key, label }) =>
            (filtros?.[key] || []).map((valor) => (
              <button
                key={`${key}-${valor}`}
                type="button"
                onClick={() => alternarValor(key, valor)}
                aria-label={`Quitar ${label} ${valor}`}
                className="inline-flex items-center gap-1.5 rounded-full border border-sig-amber/25
                           bg-sig-amber/[0.12] px-2 py-[3px] font-mono text-[10px] uppercase
                           tracking-label leading-none text-sig-amber transition-colors
                           hover:bg-sig-amber/20"
              >
                {valor}
                <X size={10} aria-hidden="true" />
              </button>
            )),
          )}

          {alcance && (
            <Label caja="normal" className="ml-auto text-sig-dim">{alcance}</Label>
          )}
        </div>
      )}
    </div>
  );
}
