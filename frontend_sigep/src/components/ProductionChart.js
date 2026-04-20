import React from 'react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer,
} from 'recharts';
import { BarChart3, TrendingUp } from 'lucide-react';

const MOCK_DATA = [
  { hora: '06:00', pallets: 2 },
  { hora: '07:00', pallets: 5 },
  { hora: '08:00', pallets: 4 },
  { hora: '09:00', pallets: 7 },
  { hora: '10:00', pallets: 9 },
  { hora: '11:00', pallets: 6 },
  { hora: '12:00', pallets: 3 },
  { hora: '13:00', pallets: 8 },
  { hora: '14:00', pallets: 10 },
  { hora: '15:00', pallets: 7 },
  { hora: '16:00', pallets: 5 },
  { hora: '17:00', pallets: 4 },
];

function CustomTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="px-3.5 py-2.5 rounded-lg text-xs font-medium bg-[#1a2540] border border-sigep-border shadow-lg">
      <p className="text-slate-400 mb-0.5">{label}</p>
      <p className="text-white">
        <span className="text-sigep-neon font-bold text-sm">{payload[0].value}</span>
        <span className="text-slate-400 ml-1">pacas</span>
      </p>
    </div>
  );
}

export default function ProductionChart({ liveData }) {
  const chartData = liveData?.length > 0 ? liveData : MOCK_DATA;
  const isLive = liveData?.length > 0;
  const total = chartData.reduce((s, d) => s + d.pallets, 0);

  return (
    <div className="bg-sigep-card border border-sigep-border rounded-2xl p-5 shadow-[0_1px_3px_rgba(0,0,0,0.5)] hover:border-sigep-border2 hover:shadow-[0_8px_25px_rgba(0,0,0,0.4)] transition-all duration-300 animate-fade-in" style={{ animationDelay: '240ms' }}>
      {/* Header */}
      <div className="flex items-center justify-between mb-5">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center p-2 rounded-lg bg-sigep-neon/10 text-sigep-neon">
            <BarChart3 size={17} />
          </div>
          <div>
            <h2 className="text-[15px] font-semibold text-white leading-tight">Producción por Hora</h2>
            <p className="text-[11px] text-slate-500 mt-0.5">
              {isLive ? 'Datos en vivo del servidor' : 'Datos de demostración'}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <div className="text-right hidden sm:block">
            <p className="text-lg font-bold text-white tabular-nums leading-tight">{total}</p>
            <p className="text-[10px] text-slate-500">total hoy</p>
          </div>
          <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[10px] font-semibold uppercase tracking-wider ${
            isLive
              ? 'bg-sigep-neon/10 text-sigep-neon border border-sigep-neon/15'
              : 'bg-sigep-warning/10 text-sigep-warning border border-sigep-warning/15'
          }`}>
            {isLive ? <><TrendingUp size={10} /> Live</> : 'Demo'}
          </span>
        </div>
      </div>

      {/* Chart */}
      <div className="w-full h-[280px]">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={chartData} margin={{ top: 5, right: 8, left: -20, bottom: 5 }}>
            <defs>
              <linearGradient id="barGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#00E887" stopOpacity={0.95} />
                <stop offset="100%" stopColor="#00E887" stopOpacity={0.4} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" vertical={false} />
            <XAxis
              dataKey="hora"
              tick={{ fill: '#64748b', fontSize: 11, fontFamily: 'Inter' }}
              axisLine={{ stroke: '#1e293b' }}
              tickLine={false}
            />
            <YAxis
              tick={{ fill: '#64748b', fontSize: 11, fontFamily: 'Inter' }}
              axisLine={false}
              tickLine={false}
              allowDecimals={false}
            />
            <Tooltip content={<CustomTooltip />} cursor={{ fill: 'rgba(0,232,135,0.04)', radius: 4 }} />
            <Bar dataKey="pallets" fill="url(#barGrad)" radius={[6, 6, 0, 0]} maxBarSize={32} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
