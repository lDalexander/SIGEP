import React, { useState, useEffect, useCallback, useRef } from 'react';
import axios from 'axios';

import Header from './components/Header';
import BarraTitulo from './components/BarraTitulo';
import Segmentadores from './components/Segmentadores';
import KPICards from './components/KPICards';
import ProductionChart from './components/ProductionChart';
import OperationsTable from './components/OperationsTable';
import EstadisticasProduccion from './components/EstadisticasProduccion';
import TerminalLog from './components/TerminalLog';
import ChecklistMantenimiento from './components/ChecklistMantenimiento';
import TopProductionChart from './components/TopProductionChart';
import SolicitudesInsumos from './components/SolicitudesInsumos';
import ComentariosTurno from './components/ComentariosTurno';
import DetalleChecklist from './components/DetalleChecklist';
import Footer from './components/Footer';
import AdminApp from './components/admin/AdminApp';
import ParosView from './components/paros/ParosView';
import { Card, Label } from './components/ui';
import { fechaISO } from './lib/format';
import useApi from './lib/useApi';
import {
  SIN_FILTROS, paramsDeFiltros, serializarParams, contarFiltros, resumenFiltros,
} from './lib/filtros';

/* Ruta relativa: en producción la resuelve el proxy de nginx (`/api/` ->
   127.0.0.1:8000) y en desarrollo la clave "proxy" de package.json. Así no hay
   CORS ni IPs incrustadas en el bundle. */
const API_BASE = '/api';
const POLL_INTERVAL = 15000;
/* Los valores de los segmentadores solo cambian cuando arranca o acaba un turno, así
   que no necesitan el ritmo del dashboard; con un minuto un operario nuevo aparece en
   el menú sin recargar la página y sin repetir cinco DISTINCT cada 15 segundos. */
const OPCIONES_INTERVAL = 60000;

/* Qué tarjetas segmenta de verdad la barra de filtros. `estadisticas` no acepta los
   parámetros de segmentación, y `logs`, checklists, insumos y comentarios no aceptan
   ninguno: se dice explícitamente en la UI en vez de dejar que se deduzca de las
   cifras, que es como se leería un filtro por aplicado sin estarlo. */
const ALCANCE_SEGMENTACION =
  'segmenta KPI, producción, estado operativo y top de marcas; estadísticas, actividad, '
  + 'checklists, insumos y comentarios salen sin segmentar';

/* Vista según la URL. Sin react-router: la navegación es estado + History API, así que
   la traducción ruta -> vista vive en un solo sitio y la usan el arranque y el botón
   «atrás» del navegador. */
function vistaDeRuta(ruta = window.location.pathname) {
  if (ruta.startsWith('/admin')) return 'admin';
  if (ruta.startsWith('/paros')) return 'paros';
  if (ruta.startsWith('/insumos')) return 'insumos';
  return 'dashboard';
}

function App() {
  /* ── Navegación ─────────────────────────────────────── */
  const [vista, setVista] = useState(() => vistaDeRuta());

  const navegar = useCallback((destino) => {
    setVista(destino);
    const ruta = destino === 'dashboard' ? '/' : `/${destino}`;
    if (window.location.pathname !== ruta) window.history.pushState({ vista: destino }, '', ruta);
  }, []);

  useEffect(() => {
    const alVolver = () => setVista(vistaDeRuta());
    window.addEventListener('popstate', alVolver);
    return () => window.removeEventListener('popstate', alVolver);
  }, []);

  /* ── Rango de fechas y franja horaria ───────────────────
     `rango` son los inputs; `aplicado` es lo que se está consultando. Se separan
     para que el dashboard no recargue con cada pulsación de tecla en la fecha.

     `horaDesde`/`horaHasta` vacíos significan el día completo, que es el arranque por
     defecto. Se permite que la de inicio sea mayor que la de fin: el backend lo lee
     como franja que cruza medianoche (19:00 → 07:00 = el turno de noche entero). */
  const hoy = fechaISO();
  const rangoInicial = { desde: hoy, hasta: hoy, horaDesde: '', horaHasta: '' };
  const [rango, setRango] = useState(rangoInicial);
  const [aplicado, setAplicado] = useState(rangoInicial);

  /* ── Agrupación del gráfico de producción ───────────────
     `null` = automático: por hora cuando se mira un solo día, por día en cuanto el
     rango abarca varios. Es lo que se espera al pedir una semana, y evita el error de
     leer como línea de tiempo un eje que en realidad suma la misma hora de cada día.
     El toggle de la tarjeta guarda aquí la elección manual, que solo dura hasta el
     siguiente «Cargar»: al cambiar de rango vuelve a mandar el automático. */
  const [agrupacionManual, setAgrupacionManual] = useState(null);
  const rangoMultiDia = aplicado.desde !== aplicado.hasta;
  const agrupacion = rangoMultiDia ? (agrupacionManual ?? 'dia') : 'hora';

  /* ── Segmentación multi-selección ───────────────────────
     A diferencia del rango, los segmentadores **no** pasan por «Cargar»: se aplican al
     seleccionarlos, porque acotan lo que ya se está viendo en vez de pedir un período
     distinto. Ninguna dimensión seleccionada = todas, y entonces la petición sale sin
     esos parámetros, exactamente como antes de existir el filtro.

     Los valores del menú se piden con el rango aplicado, así que solo se ofrece lo que
     de verdad produjo en él; no se resetean al cambiar de rango — un filtro puesto a
     mano no debe desaparecer solo, y `Segmentadores` sigue mostrando los que quedan
     fuera del rango nuevo para que se puedan quitar. */
  const [filtros, setFiltros] = useState(() => ({ ...SIN_FILTROS }));

  const { datos: opcionesFiltros, cargando: cargandoOpciones, error: errorOpciones } = useApi(
    `${API_BASE}/dashboard/opciones_filtros`,
    {
      params: { desde: aplicado.desde, hasta: aplicado.hasta },
      intervalo: vista === 'dashboard' ? OPCIONES_INTERVAL : 0,
    },
  );

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
  /* La vista de paros tiene su propio polling, así que reporta aquí su resultado: si no,
     el «EN VIVO» de la cabecera y la hora del pie se quedarían congelados con lo último
     que dijo el dashboard, que es justo lo que no hay que hacer con un indicador global. */
  const [parosEnLinea, setParosEnLinea] = useState(true);

  const timerRef = useRef(null);

  /* La zona de administración tiene su propia cabecera, ancho y autenticación. */
  const irAlDashboard = useCallback(() => navegar('dashboard'), [navegar]);

  /**
   * Un solo refresco para todo el dashboard. Se usa allSettled a propósito: si un
   * endpoint falla, los demás sí actualizan, y el que falla conserva su último dato
   * visible en lugar de vaciarse.
   */
  const refrescar = useCallback(async () => {
    /* Las horas solo se añaden si el usuario puso alguna: sin ellas la petición es
       exactamente la de antes de existir el filtro. */
    const params = {
      desde: aplicado.desde,
      hasta: aplicado.hasta,
      ...(aplicado.horaDesde ? { hora_desde: aplicado.horaDesde } : {}),
      ...(aplicado.horaHasta ? { hora_hasta: aplicado.horaHasta } : {}),
      ...paramsDeFiltros(filtros),
    };
    /* `serializarParams` en vez del serializador de axios: este manda las listas como
       claves repetidas (`maquina=A&maquina=B`), que es lo que FastAPI lee como
       `List[str]`; axios las mandaría como `maquina[]=A`, un parámetro que el backend
       no conoce, así que ignoraría el filtro y devolvería los datos sin segmentar
       como si estuvieran segmentados. */
    const opciones = { params, timeout: 8000, paramsSerializer: { serialize: serializarParams } };

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
  }, [aplicado, agrupacion, filtros]);

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
  /* Limpiar la franja se aplica de inmediato, sin pasar por «Cargar»: es volver a lo
     que ya se estaba viendo, no una consulta nueva que haya que confirmar. */
  const limpiarHoras = () => {
    setRango((prev) => ({ ...prev, horaDesde: '', horaHasta: '' }));
    setAplicado((prev) => ({ ...prev, horaDesde: '', horaHasta: '' }));
  };

  /* Los segmentadores se aplican solos: se combinan entre dimensiones (una máquina Y
     dos operarios) y dentro de cada una en OR, que es como los lee el backend con su
     `IN (...)`. Lista vacía = sin filtrar por esa dimensión. */
  const cambiarFiltro = useCallback((dim, valores) => {
    setFiltros((prev) => ({ ...prev, [dim]: valores }));
  }, []);
  const limpiarFiltros = useCallback(() => setFiltros({ ...SIN_FILTROS }), []);

  const descargar = (ruta) => {
    /* Los endpoints de reportes solo aceptan fechas; la franja horaria no se les manda
       porque la ignorarían. El Excel sale con el día completo, y se avisa en la UI. */
    const qs = new URLSearchParams({ desde: aplicado.desde, hasta: aplicado.hasta });
    window.open(`${API_BASE}/reportes/${ruta}?${qs}`, '_blank', 'noopener');
  };

  /* Cada refresco de la vista de paros actualiza el indicador global y la hora del pie. */
  const reportarParos = useCallback((enLinea) => {
    setParosEnLinea(enLinea);
    if (enLinea) setUltimoRefresco(new Date());
  }, []);

  const sinFallos = vista === 'paros' ? parosEnLinea : Object.keys(errores).length === 0;

  /* Texto del metadato de las tarjetas: «hoy» cuando el rango es el día actual. */
  const esHoy = aplicado.desde === hoy && aplicado.hasta === hoy;
  const periodo = esHoy
    ? 'hoy'
    : aplicado.desde === aplicado.hasta
      ? aplicado.desde
      : `${aplicado.desde} → ${aplicado.hasta}`;

  /* Las tarjetas de producción sí respetan la franja horaria, así que su metadato la
     nombra. Checklists e insumos no —sus endpoints no la aceptan— y siguen con
     `periodo` a secas, para no rotular un filtro que no se está aplicando. */
  const franja = aplicado.horaDesde || aplicado.horaHasta
    ? aplicado.horaDesde && aplicado.horaHasta
      ? `${aplicado.horaDesde}→${aplicado.horaHasta}`
      : aplicado.horaDesde
        ? `desde ${aplicado.horaDesde}`
        : `hasta ${aplicado.horaHasta}`
    : null;
  const periodoConHoras = franja ? `${periodo} · ${franja}` : periodo;

  /* Las tarjetas que sí se segmentan nombran la segmentación en su metadato; las que no
     la aceptan se quedan con `periodoConHoras`, para no rotular un filtro que no se
     está aplicando. */
  const segmentacion = resumenFiltros(filtros);
  const periodoSegmentado = segmentacion ? `${periodoConHoras} · ${segmentacion}` : periodoConHoras;
  const haySegmentacion = contarFiltros(filtros) > 0;

  /* Administración sustituye toda la página: lleva cabecera y ancho propios. */
  if (vista === 'admin') {
    return <AdminApp onVolver={irAlDashboard} />;
  }

  return (
    <div className="min-h-screen flex flex-col">
      <Header enVivo={sinFallos} onNavegar={navegar} vista={vista} />

      <main className="flex-1 mx-auto w-full max-w-[1400px] px-6">
        {vista === 'paros' && (
          <>
            {/* Mismo rango y misma franja que el dashboard: las dos vistas no pueden
                contradecirse. Sin descargas — no hay Excel de paros (la hoja «Paros»
                del reporte de producción se descarga desde el dashboard). */}
            <BarraTitulo
              desde={rango.desde}
              hasta={rango.hasta}
              horaDesde={rango.horaDesde}
              horaHasta={rango.horaHasta}
              onChange={cambiarFecha}
              onCargar={cargarRango}
              onLimpiarHoras={limpiarHoras}
              onDescargar={descargar}
              titulo="Monitoreo de paros"
              descargas={false}
              avisoFranja="la franja filtra por la hora de inicio del paro; el estado de las máquinas es siempre el de ahora"
            />

            <ParosView
              apiBase={API_BASE}
              desde={aplicado.desde}
              hasta={aplicado.hasta}
              horaDesde={aplicado.horaDesde}
              horaHasta={aplicado.horaHasta}
              periodo={periodoConHoras}
              intervalo={POLL_INTERVAL}
              onEstado={reportarParos}
            />
          </>
        )}

        {vista === 'dashboard' && (
          <>
            <BarraTitulo
              desde={rango.desde}
              hasta={rango.hasta}
              horaDesde={rango.horaDesde}
              horaHasta={rango.horaHasta}
              onChange={cambiarFecha}
              onCargar={cargarRango}
              onLimpiarHoras={limpiarHoras}
              onDescargar={descargar}
            />

            {/* Debajo del título: acota todo el dashboard sin cambiar de período. */}
            <div className="mb-5">
              <Segmentadores
                opciones={opcionesFiltros}
                filtros={filtros}
                onChange={cambiarFiltro}
                onLimpiar={limpiarFiltros}
                cargando={cargandoOpciones}
                error={errorOpciones}
                alcance={ALCANCE_SEGMENTACION}
              />
            </div>

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
                  periodo={periodoSegmentado}
                  agrupacion={agrupacion}
                  onAgrupacion={setAgrupacionManual}
                  diaHabilitado={rangoMultiDia}
                  cargando={cargando}
                  error={errores.hora}
                />
                <OperationsTable
                  datos={operaciones}
                  periodo={periodoSegmentado}
                  cargando={cargando}
                  error={errores.operativo}
                />
                <EstadisticasProduccion
                  apiBase={API_BASE}
                  desde={aplicado.desde}
                  hasta={aplicado.hasta}
                  horaDesde={aplicado.horaDesde}
                  horaHasta={aplicado.horaHasta}
                  periodo={periodoConHoras}
                  intervalo={POLL_INTERVAL}
                  sinSegmentar={haySegmentacion}
                />
                <ComentariosTurno apiBase={API_BASE} intervalo={POLL_INTERVAL} />
              </div>

              <div className="space-y-5 min-w-0">
                <TerminalLog logs={logs} cargando={cargando} error={errores.logs} />
                <ChecklistMantenimiento apiBase={API_BASE} intervalo={POLL_INTERVAL} />
                <TopProductionChart
                  datos={topMarcas}
                  periodo={periodoSegmentado}
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
        )}

        {/* La cabecera de las capturas lleva un botón «Insumos», pero no hay ninguna
            captura de esa vista ni especificación de su contenido. El dashboard ya
            muestra las solicitudes en su propia tarjeta. */}
        {vista === 'insumos' && (
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
