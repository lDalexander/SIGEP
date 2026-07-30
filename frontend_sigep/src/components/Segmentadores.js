import React, { useState, useRef, useEffect } from 'react';
import { Filter, ChevronDown, Check, X, Cpu, User, Tag, Package, Droplet } from 'lucide-react';

/* Dimensiones segmentables — coinciden con las columnas de SesionTrabajoDB
   y con los query-params que acepta el backend en /api/dashboard/*. */
const DIMS = [
  { key: 'maquina',      label: 'Máquina',      Icon: Cpu },
  { key: 'operador',     label: 'Operador',     Icon: User },
  { key: 'marca',        label: 'Marca',        Icon: Tag },
  { key: 'presentacion', label: 'Presentación', Icon: Package },
  { key: 'fragancia',    label: 'Fragancia',    Icon: Droplet },
];

/**
 * Barra de segmentadores multi-selección.
 * Props:
 *   opciones : { maquina:[], operador:[], marca:[], presentacion:[], fragancia:[] }
 *   filtros  : mismos keys, cada uno con los valores seleccionados
 *   onChange : (dim, valores[]) => void
 *   onClear  : () => void
 */
export default function Segmentadores({ opciones, filtros, onChange, onClear }) {
  const [openKey, setOpenKey] = useState(null);
  const rootRef = useRef(null);

  // Cerrar el menú abierto al hacer click fuera del componente.
  useEffect(() => {
    function handleClickOutside(e) {
      if (rootRef.current && !rootRef.current.contains(e.target)) setOpenKey(null);
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const totalSeleccionados = DIMS.reduce((s, d) => s + (filtros[d.key]?.length || 0), 0);

  const toggleValor = (key, valor) => {
    const actuales = filtros[key] || [];
    const nuevos = actuales.includes(valor)
      ? actuales.filter((v) => v !== valor)
      : [...actuales, valor];
    onChange(key, nuevos);
  };

  return (
    <div
      ref={rootRef}
      className="bg-sigep-card border border-sigep-border rounded-2xl p-4 mt-5 shadow-[0_1px_3px_rgba(0,0,0,0.5)] animate-fade-in"
      style={{ animationDelay: '360ms' }}
    >
      {/* Header */}
      <div className="flex items-center justify-between mb-3.5">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center p-2 rounded-lg bg-sigep-neon/10 text-sigep-neon">
            <Filter size={17} />
          </div>
          <div>
            <h2 className="text-[15px] font-semibold text-white leading-tight">Segmentadores</h2>
            <p className="text-[11px] text-slate-500 mt-0.5">
              Filtra los indicadores por máquina, operador, marca, presentación o fragancia
            </p>
          </div>
        </div>
        {totalSeleccionados > 0 && (
          <button
            type="button"
            onClick={onClear}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[12px] font-medium text-slate-400 bg-[#162032] border border-sigep-border hover:text-white hover:border-sigep-border2 transition-colors"
          >
            <X size={12} />
            Limpiar ({totalSeleccionados})
          </button>
        )}
      </div>

      {/* Menús desplegables */}
      <div className="flex flex-wrap gap-2.5">
        {DIMS.map(({ key, label, Icon }) => {
          const valores = opciones?.[key] || [];
          const seleccionados = filtros[key] || [];
          const abierto = openKey === key;
          return (
            <div key={key} className="relative">
              <button
                type="button"
                onClick={() => setOpenKey(abierto ? null : key)}
                className={`inline-flex items-center gap-2 px-3.5 py-2 rounded-xl text-[13px] font-medium border transition-all duration-200 ${
                  seleccionados.length > 0
                    ? 'bg-sigep-neon/10 text-sigep-neon border-sigep-neon/30'
                    : 'bg-[#0d1424] text-slate-300 border-sigep-border hover:border-sigep-border2 hover:text-white'
                }`}
              >
                <Icon size={14} />
                <span>{label}</span>
                {seleccionados.length > 0 && (
                  <span className="inline-flex items-center justify-center min-w-[18px] h-[18px] px-1 rounded-full text-[10px] font-bold bg-sigep-neon text-[#0a0f1a]">
                    {seleccionados.length}
                  </span>
                )}
                <ChevronDown size={14} className={`transition-transform duration-200 ${abierto ? 'rotate-180' : ''}`} />
              </button>

              {abierto && (
                <div className="absolute left-0 z-30 mt-2 w-60 max-h-72 overflow-y-auto rounded-xl border border-sigep-border bg-[#111a2e] shadow-[0_12px_40px_rgba(0,0,0,0.6)] p-1.5">
                  {valores.length === 0 ? (
                    <div className="px-3 py-4 text-center text-[12px] text-slate-500">
                      Sin valores disponibles
                    </div>
                  ) : (
                    valores.map((valor) => {
                      const activo = seleccionados.includes(valor);
                      return (
                        <button
                          key={valor}
                          type="button"
                          onClick={() => toggleValor(key, valor)}
                          className="w-full flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-[13px] text-left hover:bg-white/[0.04] transition-colors"
                        >
                          <span
                            className={`flex items-center justify-center w-4 h-4 shrink-0 rounded border transition-colors ${
                              activo ? 'bg-sigep-neon border-sigep-neon' : 'border-slate-600'
                            }`}
                          >
                            {activo && <Check size={11} className="text-[#0a0f1a]" strokeWidth={3} />}
                          </span>
                          <span className={`truncate ${activo ? 'text-white' : 'text-slate-300'}`}>{valor}</span>
                        </button>
                      );
                    })
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
