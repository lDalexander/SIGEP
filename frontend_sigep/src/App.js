import React, { useState, useEffect, useCallback, useRef } from 'react';
import axios from 'axios';

import Header from './components/Header';
import BarraTitulo from './components/BarraTitulo';
import KPICards from './components/KPICards';
import ProductionChart from './components/ProductionChart';
import OperationsTable from './components/OperationsTable';
import EstadisticasProduccion from './components/EstadisticasProduccion';
import TerminalLog from './components/TerminalLog';
import ChecklistMantenimiento from './components/ChecklistMantenimiento';
import TabletsSyncPanel from './components/TabletsSyncPanel';
import TopProductionChart from './components/TopProductionChart';
import SolicitudesInsumos from './components/SolicitudesInsumos';
import DetalleChecklist from './components/DetalleChecklist';
import Footer from './components/Footer';
import AdminApp from './components/admin/AdminApp';
import { Card, Label } from './components/ui';
import { fechaISO } from './lib/format';

/* Ruta relativa: en producción la resuelve el proxy de nginx (`/api/` ->
   127.0.0.1:8000) y en desarrollo la clave "proxy" de package.json. Así no hay
   CORS ni IPs incrustadas en el bundle. */
const API_BASE = '/api';
const POLL_INTERVAL = 15000;

function App() {
  /* ── Navegación ─────────────────────────────────────── */
  const [vista, setVista] = useState(() =>
    window.location.pathname.startsWith('/admin') ? 'admin' : 'dashboard'
  );

  const navegar = useCallback((destino) => {
    setVista(destino);
    const ruta = destino === 'dashboard' ? '/' : `/${destino}`;
    if (window.location.pathname !== ruta) window.history.pushState({ vista: destino }, '', ruta);
  }, []);

  useEffect(() => {
    const alVolver = () =>
      setVista(window.location.pathname.startsWith('/admin') ? 'admin' : 'dashboard');
    window.addEventListener('popstate', alVolver);
    return () => window.removeEventListener('popstate', alVolver);
  }, []);

  /* ── Rango de fechas ────────────────────────────────────
     `rango` son los inputs; `aplicado` es lo que se está consultando. Se separan
     para que el dashboard no recargue con cada pulsación de tecla en la fecha. */
  const hoy = fechaISO();
  const [rango, setRango] = useState({ desde: hoy, hasta: hoy });
  const [aplicado, setAplicado] = useState({ desde: hoy, hasta: hoy });

  /* ── Agrupación del gráfico de producción ───────────────
     `null` = automático: por hora cuando se mira un solo día, por día en cuanto el
     rango abarca varios. Es lo que se espera al pedir una semana, y evita el error de
     leer como línea de tiempo un eje que en realidad suma la misma hora de cada día.
     El toggle de la tarjeta guarda aquí la elección manual, que solo dura hasta el
     siguiente «Cargar»: al cambiar de rango vuelve a mandar el automático. */
  const [agrupacionManual, setAgrupacionManual] = useState(null);
  const rangoMultiDia = aplicado.desde !== aplicado.hasta;
  const agrupacion = rangoMultiDia ? (agrupacionManual ?? 'dia') : 'hora';

  /* ── Datos ──────────────────────────────────────────── */
  const [kpis, setKpis] = useState(null);
  const [logs, setLogs] = useState([]);
  const [produccionHora, setProduccionHora] = useState([]);
  const [operaciones, setOperaciones] = useState([]);
  const [topMarcas, setTopMarcas] = useState([]);

  const [cargando, setCargando] = useState(true);
  /* Errores por endpoint, no uno global: si solo falla `logs`, no tiene sentido
     marcar como caídas las tarjetas que sí recibieron datos. */
  const [errores, setErrores] = useState({});
  const [ultimoRefresco, setUltimoRefresco] = useState(null);

  const timerRef = useRef(null);

  /* La zona de administración tiene su propia cabecera, ancho y autenticación. */
  const irAlDashboard = useCallback(() => navegar('dashboard'), [navegar]);

  /**
   * Un solo refresco para todo el dashboard. Se usa allSettled a propósito: si un
   * endpoint falla, los demás sí actualizan, y el que falla conserva su último dato
   * visible en lugar de vaciarse.
   */
  const refrescar = useCallback(async () => {
    const params = { desde: aplicado.desde, hasta: aplicado.hasta };
    const opciones = { params, timeout: 8000 };

    const peticiones = [
      ['kpis',        () => axios.get(`${API_BASE}/dashboard/kpis`, opciones),            setKpis],
      ['logs',        () => axios.get(`${API_BASE}/dashboard/logs`, { timeout: 8000 }),   setLogs],
      /* `agrupar` solo se envía en modo día: sin el parámetro la respuesta es la de
         siempre, que es justo lo que consume la app Android. */
      ['hora',        () => axios.get(`${API_BASE}/dashboard/produccion_hora`, {
                        ...opciones,
                        params: agrupacion === 'dia' ? { ...params, agrupar: 'dia' } : params,
                      }), setProduccionHora],
      ['operativo',   () => axios.get(`${API_BASE}/dashboard/estado_operativo`, opciones), setOperaciones],
      ['top',         () => axios.get(`${API_BASE}/dashboard/top_produccion`, opciones),  setTopMarcas],
    ];

    const resultados = await Promise.allSettled(peticiones.map(([, ejecutar]) => ejecutar()));

    const fallos = {};
    resultados.forEach((resultado, i) => {
      const [nombre, , aplicar] = peticiones[i];
      if (resultado.status === 'fulfilled') {
        aplicar(resultado.value.data);
      } else {
        fallos[nombre] = true;
        console.error(`[SIGEP] Error en ${nombre}:`, resultado.reason?.message);
      }
    });

    setErrores(fallos);
    /* La hora del footer refleja cuándo se refrescaron datos: basta con que uno
       haya respondido, si no se quedaría congelada por un solo endpoint caído. */
    if (resultados.some((r) => r.status === 'fulfilled')) setUltimoRefresco(new Date());
    setCargando(false);
  }, [aplicado, agrupacion]);

  useEffect(() => {
    if (vista !== 'dashboard') return undefined;
    setCargando(true);
    refrescar();
    timerRef.current = setInterval(refrescar, POLL_INTERVAL);
    return () => clearInterval(timerRef.current);
  }, [refrescar, vista]);

  /* ── Handlers ───────────────────────────────────────── */
  const cambiarFecha = (campo, valor) => setRango((prev) => ({ ...prev, [campo]: valor }));
  const cargarRango = () => {
    setAplicado({ ...rango });
    setAgrupacionManual(null);   // el rango nuevo vuelve a decidir la agrupación
  };

  const descargar = (ruta) => {
    const qs = new URLSearchParams({ desde: aplicado.desde, hasta: aplicado.hasta });
    window.open(`${API_BASE}/reportes/${ruta}?${qs}`, '_blank', 'noopener');
  };

  const sinFallos = Object.keys(errores).length === 0;

  /* Texto del metadato de las tarjetas: «hoy» cuando el rango es el día actual. */
  const esHoy = aplicado.desde === hoy && aplicado.hasta === hoy;
  const periodo = esHoy
    ? 'hoy'
    : aplicado.desde === aplicado.hasta
      ? aplicado.desde
      : `${aplicado.desde} → ${aplicado.hasta}`;

  /* Administración sustituye toda la página: lleva cabecera y ancho propios. */
  if (vista === 'admin') {
    return <AdminApp onVolver={irAlDashboard} />;
  }

  return (
    <div className="min-h-screen flex flex-col">
      <Header enVivo={sinFallos} onNavegar={navegar} />

      <main className="flex-1 mx-auto w-full max-w-[1400px] px-6">
        {vista === 'dashboard' ? (
          <>
            <BarraTitulo
              desde={rango.desde}
              hasta={rango.hasta}
              onChange={cambiarFecha}
              onCargar={cargarRango}
              onDescargar={descargar}
            />

            <KPICards
              kpis={kpis}
              operaciones={operaciones}
              cargando={cargando}
              error={errores.kpis || errores.operativo}
            />

            {/* Dos columnas: 62% / 38%. Colapsan a una sola bajo 1100px. */}
            <div className="mt-5 grid grid-cols-1 wide:grid-cols-[62fr_38fr] gap-5 items-start">
              <div className="space-y-5 min-w-0">
                <ProductionChart
                  datos={produccionHora}
                  periodo={periodo}
                  agrupacion={agrupacion}
                  onAgrupacion={setAgrupacionManual}
                  diaHabilitado={rangoMultiDia}
                  cargando={cargando}
                  error={errores.hora}
                />
                <OperationsTable
                  datos={operaciones}
                  periodo={periodo}
                  cargando={cargando}
                  error={errores.operativo}
                />
                <EstadisticasProduccion apiBase={API_BASE} intervalo={POLL_INTERVAL} />
              </div>

              <div className="space-y-5 min-w-0">
                <TerminalLog logs={logs} cargando={cargando} error={errores.logs} />
                <ChecklistMantenimiento apiBase={API_BASE} intervalo={POLL_INTERVAL} />
                <TabletsSyncPanel apiBase={API_BASE} intervalo={POLL_INTERVAL} />
                <TopProductionChart
                  datos={topMarcas}
                  periodo={periodo}
                  cargando={cargando}
                  error={errores.top}
                />
                <SolicitudesInsumos
                  apiBase={API_BASE}
                  desde={aplicado.desde}
                  hasta={aplicado.hasta}
                  esHoy={esHoy}
                  intervalo={POLL_INTERVAL}
                />
              </div>
            </div>

            {/* Ancho completo, al final */}
            <div className="mt-5">
              <DetalleChecklist
                apiBase={API_BASE}
                desde={aplicado.desde}
                hasta={aplicado.hasta}
                periodo={periodo}
                intervalo={POLL_INTERVAL}
              />
            </div>
          </>
        ) : (
          /* La cabecera de las capturas lleva un botón «Insumos», pero no hay
             ninguna captura de esa vista ni especificación de su contenido. El
             dashboard ya muestra las solicitudes en su propia tarjeta. */
          <div className="py-10">
            <Card titulo="Insumos">
              <Label caja="normal" className="block py-8 text-center text-sig-dim">
                Vista sin especificación de referencia
              </Label>
            </Card>
          </div>
        )}

        <Footer actualizado={ultimoRefresco} />
      </main>
    </div>
  );
}

export default App;
