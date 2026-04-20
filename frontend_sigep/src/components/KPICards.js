import React from 'react';
import { Package, Users, Gauge } from 'lucide-react';

const CARDS = [
  {
    key: 'pallets_hoy',
    label: 'Pacas Hoy',
    icon: Package,
    format: (v) => (v != null ? Number(v).toLocaleString('es-EC') : '—'),
    suffix: 'unidades registradas',
    iconBg: 'bg-sigep-neon/10',
    iconColor: 'text-sigep-neon',
    glowColor: 'bg-sigep-neon',
    hoverBorder: 'hover:border-sigep-neon/20',
    hoverShadow: 'hover:shadow-[0_0_20px_rgba(0,232,135,0.08)]',
  },
  {
    key: 'turnos_activos',
    label: 'Turnos Activos',
    icon: Users,
    format: (v) => v ?? '—',
    suffix: 'operando ahora',
    iconBg: 'bg-sigep-info/10',
    iconColor: 'text-sigep-info',
    glowColor: 'bg-sigep-info',
    hoverBorder: 'hover:border-sigep-info/20',
    hoverShadow: 'hover:shadow-[0_0_20px_rgba(56,189,248,0.08)]',
  },
  {
    key: 'eficiencia',
    label: 'OEE Eficiencia',
    icon: Gauge,
    format: (v) => v ?? '—',
    suffix: 'rendimiento global',
    iconBg: 'bg-sigep-warning/10',
    iconColor: 'text-sigep-warning',
    glowColor: 'bg-sigep-warning',
    hoverBorder: 'hover:border-sigep-warning/20',
    hoverShadow: 'hover:shadow-[0_0_20px_rgba(251,191,36,0.08)]',
  },
];

export default function KPICards({ data, loading, error }) {
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5 mb-8">
      {CARDS.map((card, idx) => {
        const Icon = card.icon;
        const value = data ? card.format(data[card.key]) : '—';
        const delayMs = `${(idx + 1) * 60}ms`;

        return (
          <div
            key={card.key}
            id={`kpi-${card.key}`}
            className={`
              relative overflow-hidden rounded-2xl p-5 group
              bg-sigep-card border border-sigep-border
              shadow-[0_1px_3px_rgba(0,0,0,0.5)]
              ${card.hoverBorder} ${card.hoverShadow}
              hover:-translate-y-0.5
              transition-all duration-300 ease-out
              animate-fade-in
            `}
            style={{ animationDelay: delayMs }}
          >
            {/* Glow orb */}
            <div className={`absolute -top-10 -right-10 w-28 h-28 rounded-full opacity-[0.04] group-hover:opacity-[0.09] blur-2xl transition-opacity duration-500 ${card.glowColor}`} />

            <div className="flex items-start justify-between relative z-10">
              <div className="min-w-0">
                <p className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 mb-3">
                  {card.label}
                </p>

                {loading && !data ? (
                  <div className="w-20 h-9 rounded-md bg-gradient-to-r from-sigep-border via-[#1a2540] to-sigep-border bg-[length:200%_100%] animate-shimmer mb-1.5" />
                ) : error && !data ? (
                  <p className="text-base font-medium text-slate-500">Esperando datos…</p>
                ) : (
                  <p className="text-3xl font-extrabold tracking-tight text-white mb-1 tabular-nums">
                    {value}
                  </p>
                )}

                <p className="text-[11px] font-medium text-slate-500">{card.suffix}</p>
              </div>

              <div className={`flex items-center justify-center p-2.5 rounded-lg shrink-0 ${card.iconBg} ${card.iconColor}`}>
                <Icon size={21} strokeWidth={1.7} />
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
