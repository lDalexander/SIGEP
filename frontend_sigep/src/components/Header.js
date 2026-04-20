import React from 'react';
import { Download, Radio, Clock } from 'lucide-react';

export default function Header({ onDownload }) {
  const now = new Date();
  const dateStr = now.toLocaleDateString('es-EC', {
    weekday: 'long', year: 'numeric', month: 'long', day: 'numeric',
  });
  const timeStr = now.toLocaleTimeString('es-EC', { hour: '2-digit', minute: '2-digit' });

  return (
    <header className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 mb-8 animate-fade-in">
      {/* Title */}
      <div>
        <div className="flex items-center gap-3 mb-1.5">
          <h1 className="text-2xl sm:text-[1.75rem] font-bold tracking-tight text-white">
            SIGEP Web Portal
          </h1>
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[10px] font-semibold uppercase tracking-wider bg-sigep-neon/10 text-sigep-neon border border-sigep-neon/15">
            <Radio size={9} className="animate-pulse" />
            Live
          </span>
        </div>
        <p className="text-sm text-slate-400 leading-relaxed">
          Detcuador S.A. — Sistema Integral de Gestión de Empaque y Producción
        </p>
        <div className="flex items-center gap-2 mt-1.5 text-xs text-slate-500">
          <Clock size={12} />
          <span className="capitalize">{dateStr}</span>
          <span className="text-slate-600">·</span>
          <span>{timeStr}</span>
        </div>
      </div>

      {/* Download button — uses window.open via onDownload callback */}
      <div className="flex items-center gap-3 shrink-0">
        <button
          id="btn-download-report"
          type="button"
          onClick={onDownload}
          aria-label="Descargar reporte Excel de producción"
          className="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl text-sm font-semibold no-underline cursor-pointer bg-sigep-neon text-[#0a0f1a] shadow-[0_0_16px_rgba(0,232,135,0.3)] hover:bg-sigep-neon-dim hover:shadow-[0_0_24px_rgba(0,232,135,0.5)] hover:-translate-y-0.5 active:translate-y-0 transition-all duration-200 border-0"
        >
          <Download size={15} strokeWidth={2.3} />
          Descargar Reporte
        </button>
      </div>
    </header>
  );
}
