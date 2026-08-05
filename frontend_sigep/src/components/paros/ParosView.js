import React, { useEffect, useState } from 'react';
import ParosKPIs from './ParosKPIs';
import EstadoMaquinas from './EstadoMaquinas';
import ParosPorCategoria from './ParosPorCategoria';
import TablaParos from './TablaParos';
import useApi from '../../lib/useApi';

/**
 * Vista «Paros de máquina» (/paros). Un solo endpoint la alimenta entera:
 * GET /dashboard/paros devuelve `{kpis, maquinas, paros, por_categoria}`.
 *
 * Comparte el rango y la franja de la cabecera con el dashboard, para que las dos
 * vistas no puedan contradecirse.
 *
 * Props:
 *   apiBase, desde, hasta, horaDesde, horaHasta, periodo, intervalo
 *   onEstado : (enLinea) => void — informa a App del resultado de cada refresco, que
 *              es lo que alimenta el «EN VIVO» de la cabecera mientras se está aquí
 */
export default function ParosView({
  apiBase, desde, hasta, horaDesde = '', horaHasta = '', periodo = 'hoy', intervalo = 0,
  onEstado,
}) {
  const params = {
    desde,
    hasta,
    ...(horaDesde ? { hora_desde: horaDesde } : {}),
    ...(horaHasta ? { hora_hasta: horaHasta } : {}),
  };
  const { datos, cargando, error } = useApi(`${apiBase}/dashboard/paros`, { params, intervalo });

  /* Momento de la última respuesta: los cronómetros de los paros abiertos cuentan
     desde aquí en vez de fiarse del reloj del navegador. */
  const [recibidoEn, setRecibidoEn] = useState(() => Date.now());
  useEffect(() => {
    if (datos) setRecibidoEn(Date.now());
  }, [datos]);

  /* El indicador global de la cabecera lo lleva App, que no ve este fetch. */
  useEffect(() => {
    if (!cargando && onEstado) onEstado(!error);
  }, [cargando, error, datos, onEstado]);

  return (
    <div className="pb-2">
      <ParosKPIs kpis={datos?.kpis} periodo={periodo} cargando={cargando} error={error} />

      <div className="mt-5">
        <EstadoMaquinas
          datos={datos?.maquinas}
          recibidoEn={recibidoEn}
          periodo={periodo}
          cargando={cargando}
          error={error}
        />
      </div>

      {/* Dos columnas: el detalle manda (62%), el ranking acompaña (38%). */}
      <div className="mt-5 grid grid-cols-1 wide:grid-cols-[62fr_38fr] gap-5 items-start">
        <div className="min-w-0">
          <TablaParos
            datos={datos?.paros}
            recibidoEn={recibidoEn}
            periodo={periodo}
            variosDias={desde !== hasta}
            cargando={cargando}
            error={error}
          />
        </div>
        <div className="min-w-0">
          <ParosPorCategoria
            datos={datos?.por_categoria}
            periodo={periodo}
            cargando={cargando}
            error={error}
          />
        </div>
      </div>
    </div>
  );
}
