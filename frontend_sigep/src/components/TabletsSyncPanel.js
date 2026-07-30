import React, { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import { Tablet, RefreshCw, Wifi, WifiOff, AlertCircle, CheckCircle2 } from 'lucide-react';

/**
 * Panel de sincronización de tablets.
 *
 * Muestra el estado en tiempo real de cada tablet (online/offline + pendientes
 * en cache local) y permite forzar una sincronización individual o masiva.
 *
 * Props:
 *  - apiBase: string  -> base URL de la API (ej. http://150.36.200.252:8000/api)
 *  - pollInterval?: number (ms) -> intervalo de refresco (default 5000)
 */
export default function TabletsSyncPanel({ apiBase, pollInterval = 5000 }) {
  const [tablets, setTablets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [syncing, setSyncing] = useState({}); // { device_id: bool }
  const [syncingAll, setSyncingAll] = useState(false);
  const [feedback, setFeedback] = useState(null); // { tipo: 'ok'|'err', mensaje: string }

  const fetchEstado = useCallback(async () => {
    try {
      const { data } = await axios.get(`${apiBase}/tablets/estado`, { timeout: 8000 });
      setTablets(Array.isArray(data) ? data : []);
      setError(false);
    } catch (err) {
      console.error('[SIGEP] ❌ Error fetching tablets:', err.message);
      setError(true);
    } finally {
      setLoading(false);
    }
  }, [apiBase]);

  useEffect(() => {
    fetchEstado();
    const id = setInterval(fetchEstado, pollInterval);
    return () => clearInterval(id);
  }, [fetchEstado, pollInterval]);

  const mostrarFeedback = (tipo, mensaje) => {
    setFeedback({ tipo, mensaje });
    setTimeout(() => setFeedback(null), 4000);
  };

  const sincronizarUna = async (device_id) => {
    setSyncing((s) => ({ ...s, [device_id]: true }));
    try {
      const { data } = await axios.post(`${apiBase}/tablets/sincronizar/${encodeURIComponent(device_id)}`);
      mostrarFeedback('ok', `Tablet ${device_id}: ${data.motivo || 'señal enviada'}`);
      fetchEstado();
    } catch (err) {
      console.error('[SIGEP] ❌ Error sync tablet:', err.message);
      mostrarFeedback('err', `No se pudo sincronizar la tablet ${device_id}`);
    } finally {
      setSyncing((s) => ({ ...s, [device_id]: false }));
    }
  };

  const sincronizarTodas = async () => {
    setSyncingAll(true);
    try {
      const { data } = await axios.post(`${apiBase}/tablets/sincronizar_todas`);
      mostrarFeedback('ok', `Sincronización masiva: ${data.enviadas}/${data.total} tablets notificadas`);
      fetchEstado();
    } catch (err) {
      console.error('[SIGEP] ❌ Error sync todas:', err.message);
      mostrarFeedback('err', 'No se pudo iniciar la sincronización masiva');
    } finally {
      setSyncingAll(false);
    }
  };

  const totalPendientes = tablets.reduce((acc, t) => acc + (t.pendientes || 0), 0);
  const onlineCount = tablets.filter((t) => t.en_linea).length;

  return (
    <div className="bg-sigep-card border border-sigep-border rounded-2xl shadow-[0_1px_3px_rgba(0,0,0,0.5)] overflow-hidden animate-fade-in flex flex-col">
      {/* Header */}
      <div className="px-6 py-5 border-b border-sigep-border bg-[#0d1424] flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center p-2 rounded-lg bg-sigep-neon/10 text-sigep-neon">
            <Tablet size={17} />
          </div>
          <div>
            <h2 className="text-[15px] font-semibold text-white leading-tight">Sincronización de Tablets</h2>
            <p className="text-[11px] text-slate-500 mt-0.5">
              Estado en vivo del cache local de cada tablet en planta
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-[11px] font-medium text-slate-400 bg-[#162032] px-3 py-1 rounded-full border border-sigep-border">
            {onlineCount}/{tablets.length} en línea
          </span>
          <span className={`text-[11px] font-medium px-3 py-1 rounded-full border ${
            totalPendientes > 0
              ? 'bg-amber-500/10 text-amber-400 border-amber-500/30'
              : 'bg-sigep-neon/10 text-sigep-neon border-sigep-neon/20'
          }`}>
            {totalPendientes} pendientes
          </span>
          <button
            onClick={sincronizarTodas}
            disabled={syncingAll || tablets.length === 0}
            className={`
              inline-flex items-center gap-2 px-4 py-2 rounded-xl text-[12px] font-semibold
              transition-all duration-200 border
              ${syncingAll || tablets.length === 0
                ? 'bg-slate-800 text-slate-500 border-slate-700 cursor-not-allowed'
                : 'bg-sigep-neon/10 text-sigep-neon border-sigep-neon/30 hover:bg-sigep-neon/20 cursor-pointer'
              }
            `}
          >
            <RefreshCw size={14} className={syncingAll ? 'animate-spin' : ''} />
            {syncingAll ? 'Sincronizando...' : 'Sincronizar todas'}
          </button>
        </div>
      </div>

      {/* Feedback banner */}
      {feedback && (
        <div className={`px-6 py-2 text-[12px] flex items-center gap-2 border-b border-sigep-border ${
          feedback.tipo === 'ok'
            ? 'bg-sigep-neon/[0.06] text-sigep-neon'
            : 'bg-red-500/10 text-red-400'
        }`}>
          {feedback.tipo === 'ok' ? <CheckCircle2 size={14} /> : <AlertCircle size={14} />}
          {feedback.mensaje}
        </div>
      )}

      {/* Tabla */}
      <div className="overflow-x-auto flex-1">
        <table className="w-full text-left text-sm text-slate-300">
          <thead className="text-[11px] uppercase tracking-wider text-slate-500 bg-[#0d1424]/50 border-b border-sigep-border sticky top-0 z-10">
            <tr>
              <th className="px-6 py-4 font-medium border-b border-sigep-border">Estado</th>
              <th className="px-6 py-4 font-medium border-b border-sigep-border">Tablet</th>
              <th className="px-6 py-4 font-medium border-b border-sigep-border">Máquina</th>
              <th className="px-6 py-4 font-medium text-right border-b border-sigep-border">Pendientes</th>
              <th className="px-6 py-4 font-medium border-b border-sigep-border">Último Heartbeat</th>
              <th className="px-6 py-4 font-medium border-b border-sigep-border">Última Sync</th>
              <th className="px-6 py-4 font-medium text-right border-b border-sigep-border">Acción</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-sigep-border/50 bg-transparent">
            {loading ? (
              <tr>
                <td colSpan="7" className="px-6 py-12 text-center text-slate-500 text-sm">
                  <div className="inline-block w-4 h-4 rounded-full border-2 border-slate-500 border-t-transparent animate-spin mb-2" />
                  <br />
                  Cargando estado de tablets...
                </td>
              </tr>
            ) : error ? (
              <tr>
                <td colSpan="7" className="px-6 py-12 text-center text-red-400 text-sm">
                  <AlertCircle size={18} className="inline mb-2" />
                  <br />
                  No se pudo obtener el estado de las tablets.
                </td>
              </tr>
            ) : tablets.length === 0 ? (
              <tr>
                <td colSpan="7" className="px-6 py-12 text-center text-slate-500 text-sm">
                  No hay tablets registradas todavía.
                  <br />
                  <span className="text-[11px] text-slate-600">
                    Una tablet aparece aquí en cuanto envía su primer heartbeat.
                  </span>
                </td>
              </tr>
            ) : (
              tablets.map((t) => {
                const enLinea = t.en_linea;
                const tienePendientes = (t.pendientes || 0) > 0;
                return (
                  <tr key={t.device_id} className="hover:bg-white/[0.02] transition-colors">
                    <td className="px-6 py-4 whitespace-nowrap">
                      <span className={`inline-flex items-center gap-2 px-2.5 py-1 rounded-full text-[11px] font-medium border ${
                        enLinea
                          ? 'bg-sigep-neon/10 text-sigep-neon border-sigep-neon/20'
                          : 'bg-slate-800 text-slate-400 border-slate-700'
                      }`}>
                        {enLinea
                          ? <><Wifi size={11} /> En línea</>
                          : <><WifiOff size={11} /> Offline</>}
                      </span>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap font-semibold text-white">
                      {t.nombre || t.device_id}
                      {t.nombre && (
                        <div className="text-[10px] text-slate-500 font-normal">{t.device_id}</div>
                      )}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-slate-400">
                      {t.maquina || '—'}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-right">
                      <span className={`tabular-nums font-bold text-base ${
                        tienePendientes ? 'text-amber-400' : 'text-white'
                      }`}>
                        {t.pendientes || 0}
                      </span>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-slate-400 text-[12px]">
                      {formatHace(t.segundos_desde_heartbeat)}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-slate-400 text-[12px]">
                      {formatHora(t.ultima_sincronizacion)}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-right">
                      <button
                        onClick={() => sincronizarUna(t.device_id)}
                        disabled={syncing[t.device_id]}
                        title={enLinea
                          ? 'Enviar señal de sincronización ahora'
                          : 'La tablet está offline; recibirá la orden en su próximo heartbeat'}
                        className={`
                          inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-medium
                          transition-all duration-200 border
                          ${syncing[t.device_id]
                            ? 'bg-slate-800 text-slate-500 border-slate-700 cursor-not-allowed'
                            : 'bg-white/[0.03] text-slate-300 border-sigep-border hover:border-sigep-neon/40 hover:text-sigep-neon cursor-pointer'
                          }
                        `}
                      >
                        <RefreshCw size={12} className={syncing[t.device_id] ? 'animate-spin' : ''} />
                        Sincronizar
                      </button>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function formatHace(segundos) {
  if (segundos === null || segundos === undefined) return '—';
  if (segundos < 60) return `hace ${segundos}s`;
  const min = Math.floor(segundos / 60);
  if (min < 60) return `hace ${min}m`;
  const h = Math.floor(min / 60);
  return `hace ${h}h`;
}

function formatHora(iso) {
  if (!iso) return 'Nunca';
  try {
    const d = new Date(iso);
    return d.toLocaleString('es-EC', {
      day: '2-digit', month: '2-digit',
      hour: '2-digit', minute: '2-digit',
    });
  } catch {
    return iso;
  }
}
