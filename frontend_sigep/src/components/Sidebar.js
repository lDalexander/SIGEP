import React from 'react';
import {
  LayoutDashboard, Terminal, Settings, Factory,
  ChevronLeft, ChevronRight,
} from 'lucide-react';

const NAV_ITEMS = [
  { id: 'dashboard', icon: LayoutDashboard, label: 'Dashboard' },
  { id: 'terminal',  icon: Terminal,        label: 'Terminal' },
  { id: 'settings',  icon: Settings,        label: 'Ajustes' },
];

export default function Sidebar({ activeView, onNavigate, collapsed, onToggleCollapse }) {
  return (
    <aside
      className={`
        fixed top-0 left-0 h-screen z-50
        flex flex-col
        bg-[#0c1322] border-r border-sigep-border
        transition-all duration-300 ease-out
        ${collapsed ? 'w-[68px]' : 'w-[230px]'}
      `}
    >
      {/* ── Brand ── */}
      <div className="flex items-center gap-3 px-4 h-16 border-b border-sigep-border shrink-0">
        <div className="w-9 h-9 rounded-lg flex items-center justify-center shrink-0 bg-sigep-neon/10 border border-sigep-neon/20 animate-glow-pulse">
          <Factory size={17} className="text-sigep-neon" />
        </div>
        {!collapsed && (
          <div className="animate-slide-in overflow-hidden">
            <p className="text-sm font-bold tracking-wide text-white leading-tight">SIGEP</p>
            <p className="text-[10px] font-medium tracking-[0.15em] uppercase text-slate-500">Portal v2</p>
          </div>
        )}
      </div>

      {/* ── Navigation ── */}
      <nav className="flex-1 flex flex-col gap-1 px-3 py-5" role="navigation" aria-label="Navegación principal">
        {NAV_ITEMS.map((item) => {
          const Icon = item.icon;
          const active = activeView === item.id;
          return (
            <button
              key={item.id}
              id={`nav-${item.id}`}
              onClick={() => onNavigate(item.id)}
              aria-label={item.label}
              aria-current={active ? 'page' : undefined}
              title={collapsed ? item.label : undefined}
              className={`
                flex items-center gap-3 px-3 py-2.5 rounded-xl
                transition-colors duration-200 cursor-pointer relative
                ${collapsed ? 'justify-center' : ''}
                ${active
                  ? 'bg-sigep-neon/[0.08] text-sigep-neon border border-sigep-neon/10'
                  : 'text-slate-500 border border-transparent hover:bg-white/[0.03] hover:text-slate-200'
                }
              `}
            >
              <Icon size={19} strokeWidth={active ? 2.2 : 1.7} className="shrink-0" />
              {!collapsed && <span className="text-[13px] font-medium truncate">{item.label}</span>}
              {active && (
                <span className="absolute left-0 top-1/2 -translate-y-1/2 w-[3px] h-5 rounded-r-full bg-sigep-neon" />
              )}
            </button>
          );
        })}
      </nav>

      {/* ── Collapse ── */}
      <div className="px-3 pb-4">
        <button
          id="sidebar-toggle"
          onClick={onToggleCollapse}
          aria-label={collapsed ? 'Expandir menú' : 'Colapsar menú'}
          className="w-full flex items-center justify-center py-2 rounded-lg transition-colors duration-200 cursor-pointer text-slate-500 border border-sigep-border hover:border-sigep-border2 hover:text-slate-300"
        >
          {collapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
        </button>
      </div>
    </aside>
  );
}
