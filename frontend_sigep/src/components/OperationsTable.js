import React from 'react';
import { Activity } from 'lucide-react';

export default function OperationsTable({ data, loading }) {
  return (
    <div className="bg-sigep-card border border-sigep-border rounded-2xl shadow-[0_1px_3px_rgba(0,0,0,0.5)] overflow-hidden mt-5 animate-fade-in flex flex-col" style={{ animationDelay: '400ms' }}>
      {/* Header */}
      <div className="px-6 py-5 border-b border-sigep-border bg-[#0d1424] flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center p-2 rounded-lg bg-sigep-neon/10 text-sigep-neon">
            <Activity size={17} />
          </div>
          <div>
            <h2 className="text-[15px] font-semibold text-white leading-tight">Operación en Vivo</h2>
            <p className="text-[11px] text-slate-500 mt-0.5">Estado actual de las líneas de llenado</p>
          </div>
        </div>
        <div className="text-[11px] font-medium text-slate-400 bg-[#162032] px-3 py-1 rounded-full border border-sigep-border">
          {data?.length || 0} turnos hoy
        </div>
      </div>

      {/* Table Container */}
      <div className="overflow-x-auto flex-1">
        <table className="w-full text-left text-sm text-slate-300">
          <thead className="text-[11px] uppercase tracking-wider text-slate-500 bg-[#0d1424]/50 border-b border-sigep-border sticky top-0 z-10">
            <tr>
              <th className="px-6 py-4 font-medium border-b border-sigep-border">Estado</th>
              <th className="px-6 py-4 font-medium border-b border-sigep-border">Máquina</th>
              <th className="px-6 py-4 font-medium border-b border-sigep-border">Operador</th>
              <th className="px-6 py-4 font-medium border-b border-sigep-border">Producto</th>
              <th className="px-6 py-4 font-medium border-b border-sigep-border">Tiempo Transcurrido</th>
              <th className="px-6 py-4 font-medium text-right border-b border-sigep-border">Pacas Producidas</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-sigep-border/50 bg-transparent">
            {loading ? (
               <tr>
                 <td colSpan="6" className="px-6 py-12 text-center text-slate-500 text-sm">
                   <div className="inline-block w-4 h-4 rounded-full border-2 border-slate-500 border-t-transparent animate-spin mb-2" />
                   <br/>
                   Cargando operaciones...
                 </td>
               </tr>
            ) : data && data.length > 0 ? (
              data.map((row) => {
                const isActive = row.estado === 'Activo';
                return (
                  <tr key={row.sesion_id} className="hover:bg-white/[0.02] transition-colors">
                    <td className="px-6 py-4 whitespace-nowrap">
                      <span className={`inline-flex items-center gap-2 px-2.5 py-1 rounded-full text-[11px] font-medium border ${
                        isActive 
                        ? 'bg-sigep-neon/10 text-sigep-neon border-sigep-neon/20' 
                        : 'bg-slate-800 text-slate-400 border-slate-700'
                      }`}>
                        {isActive && <span className="w-1.5 h-1.5 rounded-full bg-sigep-neon animate-pulse" />}
                        {row.estado}
                      </span>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap font-semibold text-white">
                      {row.maquina}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-slate-400">
                      {row.operador}
                    </td>
                    <td className="px-6 py-4">
                      {row.producto}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap font-medium">
                      {formatTime(row.tiempo_transcurrido)}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-right font-bold text-white tabular-nums text-base">
                      {row.total_pacas}
                    </td>
                  </tr>
                );
              })
            ) : (
               <tr>
                 <td colSpan="6" className="px-6 py-12 text-center text-slate-500 text-sm">
                   No hay operaciones registradas el día de hoy.
                 </td>
               </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function formatTime(minutes) {
  if (!minutes) return '0m';
  if (minutes < 60) return `${minutes}m`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `${h}h ${m}m`;
}
