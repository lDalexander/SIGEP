import React, { useEffect, useRef } from 'react';
import { Terminal, Wifi, WifiOff, AlertCircle } from 'lucide-react';

export default function TerminalLog({ logs, loading, error }) {
  const scrollRef = useRef(null);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [logs]);

  const logColorClass = (tipo) => {
    const map = {
      pallet: 'text-sigep-neon',
      error: 'text-sigep-danger',
      warning: 'text-sigep-warning',
      info: 'text-sigep-info',
    };
    return map[tipo] || 'text-slate-400';
  };

  return (
    <div className="bg-sigep-card border border-sigep-border rounded-2xl overflow-hidden shadow-[0_1px_3px_rgba(0,0,0,0.5)] hover:border-sigep-border2 hover:shadow-[0_8px_25px_rgba(0,0,0,0.4)] transition-all duration-300 animate-fade-in" style={{ animationDelay: '300ms' }}>

      {/* ── Header Bar ── */}
      <div className="flex items-center justify-between px-5 py-3 border-b border-sigep-border">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center p-2 rounded-lg bg-sigep-neon/10 text-sigep-neon">
            <Terminal size={15} />
          </div>
          <div>
            <h2 className="text-sm font-semibold text-white leading-tight">Terminal de Producción</h2>
            <p className="text-[10px] text-slate-500 mt-0.5">Actualización automática · 5s</p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <div className={`w-[7px] h-[7px] rounded-full ${
            error
              ? 'bg-sigep-danger shadow-[0_0_8px_rgba(248,113,113,0.5)]'
              : 'bg-sigep-neon shadow-[0_0_8px_rgba(0,232,135,0.5)] animate-glow-pulse'
          }`} />
          <span className={`text-[10px] font-semibold uppercase tracking-wider ${error ? 'text-sigep-danger' : 'text-sigep-neon'}`}>
            {error ? 'Sin conexión' : 'Conectado'}
          </span>
          {error
            ? <WifiOff size={12} className="text-sigep-danger" />
            : <Wifi size={12} className="text-sigep-neon" />
          }
        </div>
      </div>

      {/* ── Terminal Body ── */}
      <div
        ref={scrollRef}
        className="relative overflow-y-auto px-5 py-4 bg-[#030712] h-[280px] font-mono terminal-scanlines"
      >
        {/* Loading: boot sequence */}
        {loading && (
          <div className="space-y-1.5 text-xs text-slate-500">
            <p className="animate-fade-in" style={{ animationDelay: '60ms' }}>
              <span className="text-sigep-neon">{'>'}</span> Inicializando conexión al servidor…
            </p>
            <p className="animate-fade-in" style={{ animationDelay: '120ms' }}>
              <span className="text-sigep-neon">{'>'}</span> Verificando estado de producción…
            </p>
            <p className="animate-fade-in flex items-center gap-2" style={{ animationDelay: '180ms' }}>
              <span className="text-sigep-neon">{'>'}</span>
              <span className="flex gap-[3px]">
                <span className="w-1.5 h-1.5 bg-sigep-neon rounded-full animate-pulse" />
                <span className="w-1.5 h-1.5 bg-sigep-neon rounded-full animate-pulse" style={{ animationDelay: '200ms' }} />
                <span className="w-1.5 h-1.5 bg-sigep-neon rounded-full animate-pulse" style={{ animationDelay: '400ms' }} />
              </span>
            </p>
          </div>
        )}

        {/* Error state */}
        {error && !loading && (
          <div className="space-y-2 text-xs">
            <div className="flex items-start gap-2 text-sigep-danger">
              <AlertCircle size={14} className="mt-0.5 shrink-0" />
              <div>
                <p className="font-semibold">[ERROR] Conexión al servidor perdida</p>
                <p className="text-slate-500 mt-1">Reintentando automáticamente cada 5 segundos…</p>
              </div>
            </div>
          </div>
        )}

        {/* Empty state */}
        {!loading && !error && logs.length === 0 && (
          <div className="text-xs text-slate-500 space-y-1">
            <p><span className="text-sigep-neon">{'>'}</span> Sistema SIGEP en línea</p>
            <p><span className="text-sigep-neon">{'>'}</span> Esperando actividad de producción…</p>
          </div>
        )}

        {/* Log entries */}
        {logs.map((log, idx) => (
          <div
            key={`${log.hora}-${idx}`}
            className="flex gap-3 py-1.5 text-xs leading-relaxed border-b border-white/[0.02] last:border-0"
          >
            <span className="shrink-0 tabular-nums text-slate-500 whitespace-nowrap">[{log.hora}]</span>
            <span className={logColorClass(log.tipo)}>{log.mensaje}</span>
          </div>
        ))}

        {/* Blinking cursor */}
        {!loading && (
          <div className="flex items-center gap-1.5 mt-3 text-xs text-sigep-neon">
            <span>root@sigep:~$</span>
            <span className="inline-block w-2 h-[14px] bg-sigep-neon animate-blink" />
          </div>
        )}
      </div>
    </div>
  );
}
