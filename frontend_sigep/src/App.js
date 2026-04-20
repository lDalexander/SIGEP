import React, { useState, useEffect, useCallback, useRef } from 'react';
import axios from 'axios';

import Sidebar from './components/Sidebar';
import Header from './components/Header';
import KPICards from './components/KPICards';
import ProductionChart from './components/ProductionChart';
import TerminalLog from './components/TerminalLog';

/* ══════════════════════════════════════════════
   API CONFIGURATION
   ══════════════════════════════════════════════ */
const API_BASE = 'http://150.36.200.252:8000/api';
const POLL_INTERVAL = 5000; // 5 seconds

function App() {
  /* ── Navigation State ─────────────────────── */
  const [activeView, setActiveView] = useState('dashboard');
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  /* ── KPI State ────────────────────────────── */
  const [kpis, setKpis] = useState(null);
  const [kpisLoading, setKpisLoading] = useState(true);
  const [kpisError, setKpisError] = useState(false);

  /* ── Logs State ───────────────────────────── */
  const [logs, setLogs] = useState([]);
  const [logsLoading, setLogsLoading] = useState(true);
  const [logsError, setLogsError] = useState(false);

  /* ── Chart State ──────────────────────────── */
  const [chartData, setChartData] = useState([]);

  /* ── Interval Refs for cleanup ────────────── */
  const intervalsRef = useRef({ kpi: null, log: null, chart: null });

  /* ══════════════════════════════════════════════
     DATA FETCHERS
     ══════════════════════════════════════════════ */

  /**
   * GET /dashboard/kpis
   * Expected: { pallets_hoy: number, turnos_activos: number, eficiencia: string }
   */
  const fetchKPIs = useCallback(async () => {
    try {
      const { data } = await axios.get(`${API_BASE}/dashboard/kpis`, {
        timeout: 8000,
      });
      console.log('[SIGEP] ✅ KPIs recibidos:', data);
      setKpis(data);
      setKpisError(false);
    } catch (err) {
      console.error('[SIGEP] ❌ Error fetching KPIs:', err.message);
      setKpisError(true);
      // IMPORTANT: Do NOT setKpis(null) here — keep stale data visible
    } finally {
      setKpisLoading(false);
    }
  }, []);

  /**
   * GET /dashboard/logs
   * Expected: [{ hora: string, mensaje: string, tipo: string }, ...]
   */
  const fetchLogs = useCallback(async () => {
    try {
      const { data } = await axios.get(`${API_BASE}/dashboard/logs`, {
        timeout: 8000,
      });
      console.log('[SIGEP] ✅ Logs recibidos:', data.length, 'entradas');
      setLogs(Array.isArray(data) ? data : []);
      setLogsError(false);
    } catch (err) {
      console.error('[SIGEP] ❌ Error fetching Logs:', err.message);
      setLogsError(true);
    } finally {
      setLogsLoading(false);
    }
  }, []);

  /**
   * GET /dashboard/produccion_hora
   * Expected: [{ hora: string, pallets: number }, ...]
   * Note: Chart falls back to MOCK_DATA internally if this returns empty
   */
  const fetchChartData = useCallback(async () => {
    try {
      const { data } = await axios.get(`${API_BASE}/dashboard/produccion_hora`, {
        timeout: 8000,
      });
      if (Array.isArray(data)) {
        setChartData(data);
        console.log('[SIGEP] ✅ Chart data recibido:', data.length, 'horas');
      }
    } catch {
      // Silent fail — ProductionChart will render MOCK_DATA as fallback
    }
  }, []);

  /* ══════════════════════════════════════════════
     LIFECYCLE: Initial Fetch + Polling (5s)
     ══════════════════════════════════════════════ */

  useEffect(() => {
    console.log('[SIGEP] 🚀 Iniciando conexión a', API_BASE);

    // 1. Fire initial fetches immediately
    fetchKPIs();
    fetchLogs();
    fetchChartData();

    // 2. Set up polling intervals
    const t = intervalsRef.current;
    t.kpi   = setInterval(fetchKPIs, POLL_INTERVAL);
    t.log   = setInterval(fetchLogs, POLL_INTERVAL);
    t.chart = setInterval(fetchChartData, POLL_INTERVAL * 6); // 30s for chart

    // 3. Cleanup on unmount
    return () => {
      console.log('[SIGEP] 🛑 Limpiando intervalos de polling');
      clearInterval(t.kpi);
      clearInterval(t.log);
      clearInterval(t.chart);
    };
  }, [fetchKPIs, fetchLogs, fetchChartData]);

  /* ══════════════════════════════════════════════
     DOWNLOAD HANDLER
     ══════════════════════════════════════════════ */

  const handleDownloadReport = () => {
    window.open(`${API_BASE}/reportes/excel`, '_blank');
  };

  /* ══════════════════════════════════════════════
     RENDER — ZERO CHANGES TO TAILWIND CLASSES
     ══════════════════════════════════════════════ */

  const sidebarW = sidebarCollapsed ? 68 : 230;

  return (
    <div className="min-h-screen bg-[#0a0f1a] text-slate-200 font-sans">
      <Sidebar
        activeView={activeView}
        onNavigate={setActiveView}
        collapsed={sidebarCollapsed}
        onToggleCollapse={() => setSidebarCollapsed((p) => !p)}
      />

      <main
        className="transition-all duration-300 ease-out min-h-screen"
        style={{ marginLeft: sidebarW }}
      >
        <div className="px-6 py-6 lg:px-8 lg:py-7 max-w-[1600px]">

          {/* ═══ DASHBOARD VIEW ═══ */}
          {activeView === 'dashboard' && (
            <>
              <Header onDownload={handleDownloadReport} />
              <KPICards data={kpis} loading={kpisLoading} error={kpisError} />
              <div className="grid grid-cols-1 xl:grid-cols-2 gap-5">
                <ProductionChart liveData={chartData} />
                <TerminalLog logs={logs} loading={logsLoading} error={logsError} />
              </div>
            </>
          )}

          {/* ═══ TERMINAL VIEW ═══ */}
          {activeView === 'terminal' && (
            <>
              <Header onDownload={handleDownloadReport} />
              <TerminalLog logs={logs} loading={logsLoading} error={logsError} />
            </>
          )}

          {/* ═══ SETTINGS VIEW ═══ */}
          {activeView === 'settings' && (
            <>
              <Header onDownload={handleDownloadReport} />
              <div className="bg-sigep-card border border-sigep-border rounded-2xl p-10 text-center animate-fade-in shadow-[0_1px_3px_rgba(0,0,0,0.5)]">
                <div className="w-12 h-12 mx-auto mb-4 rounded-lg bg-sigep-neon/10 text-sigep-neon flex items-center justify-center">
                  <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/>
                    <circle cx="12" cy="12" r="3"/>
                  </svg>
                </div>
                <h2 className="text-lg font-semibold text-white mb-2">Panel de Ajustes</h2>
                <p className="text-sm text-slate-400 max-w-md mx-auto leading-relaxed">
                  Módulo en construcción — próximamente: gestión de operadores,
                  máquinas, y configuración del sistema SIGEP.
                </p>
              </div>
            </>
          )}

        </div>
      </main>
    </div>
  );
}

export default App;
