import React from 'react';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';
import { PieChart as PieChartIcon } from 'lucide-react';

const COLORS = ['#00E887', '#38bdf8', '#fbbf24', '#f43f5e', '#a855f7', '#64748b'];

function CustomTooltip({ active, payload }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="px-3.5 py-2.5 rounded-lg text-xs font-medium bg-[#1a2540] border border-sigep-border shadow-lg">
      <p className="text-white mb-0.5 font-semibold">{payload[0].name}</p>
      <p className="text-slate-400">
        <span className="text-sigep-neon font-bold text-sm tabular-nums">{payload[0].value}</span>
        <span className="ml-1">pacas</span>
      </p>
    </div>
  );
}

export default function TopProductionChart({ data }) {
  const chartData = data?.length > 0 ? data : [{ name: 'Sin datos', value: 1 }];
  const isLive = data?.length > 0;
  
  return (
    <div className="bg-sigep-card border border-sigep-border rounded-2xl p-5 shadow-[0_1px_3px_rgba(0,0,0,0.5)] hover:border-sigep-border2 hover:shadow-[0_8px_25px_rgba(0,0,0,0.4)] transition-all duration-300 animate-fade-in" style={{ animationDelay: '300ms' }}>
      {/* Header */}
      <div className="flex items-center justify-between mb-5">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center p-2 rounded-lg bg-sigep-neon/10 text-sigep-neon">
            <PieChartIcon size={17} />
          </div>
          <div>
            <h2 className="text-[15px] font-semibold text-white leading-tight">Distribución por Marca</h2>
            <p className="text-[11px] text-slate-500 mt-0.5">Top producción del día</p>
          </div>
        </div>
      </div>

      {/* Chart */}
      <div className="w-full h-[280px] flex items-center justify-center relative">
        {!isLive ? (
          <div className="text-slate-500 text-sm">Esperando datos...</div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie
                data={chartData}
                cx="50%"
                cy="50%"
                innerRadius={70}
                outerRadius={100}
                paddingAngle={5}
                dataKey="value"
                stroke="none"
              >
                {chartData.map((entry, index) => (
                  <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                ))}
              </Pie>
              <Tooltip content={<CustomTooltip />} />
            </PieChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}
