import React, { useState } from 'react';
import axios from 'axios';
import { Card, Estado } from './ui';
import { antiguedad, etiquetaTablet } from '../lib/format';
import useApi from '../lib/useApi';

/**
 * «Tablets · sincronización» — rejilla de chips, uno por tablet registrada.
 * Punto verde en línea / rojo fuera, nombre y antigüedad del último contacto.
 *
 * Extra sobre las capturas: al pulsar un chip se pide a esa tablet que sincronice
 * (`POST /tablets/sincronizar/{device_id}`). No cambia el aspecto y conserva una
 * capacidad que el backend ya ofrece; si la tablet está offline, la orden se
 * entrega en su próximo heartbeat.
 */
export default function TabletsSyncPanel({ apiBase, intervalo }) {
  const { datos, cargando, error, recargar } = useApi(`${apiBase}/tablets/estado`, { intervalo });
  const [aviso, setAviso] = useState(null);

  const tablets = Array.isArray(datos) ? datos : [];
  const enLinea = tablets.filter((t) => t.en_linea).length;

  const sincronizar = async (tablet) => {
    try {
      const { data } = await axios.post(
        `${apiBase}/tablets/sincronizar/${encodeURIComponent(tablet.device_id)}`
      );
      setAviso(data.motivo || 'Señal enviada');
      recargar();
    } catch {
      setAviso('No se pudo enviar la señal de sincronización');
    }
    setTimeout(() => setAviso(null), 4000);
  };

  return (
    <Card titulo="Tablets · sincronización" meta={`${enLinea}/${tablets.length} en línea`}>
      {tablets.length === 0 ? (
        <Estado cargando={cargando} error={error} vacio="Sin tablets registradas" />
      ) : (
        <>
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
            {tablets.map((t) => (
              <button
                key={t.device_id}
                type="button"
                onClick={() => sincronizar(t)}
                title={`${t.nombre || t.device_id} — pulsa para pedir sincronización`}
                className="flex items-center gap-2 rounded-lg border border-sig-line bg-sig-input
                           px-2.5 py-2 text-left transition-colors hover:border-white/20"
              >
                <span
                  aria-hidden="true"
                  className={`w-2 h-2 shrink-0 rounded-full ${t.en_linea ? 'bg-sig-ok' : 'bg-red-500'}`}
                />
                <span className="flex-1 min-w-0 truncate text-[12px] font-semibold text-sig-text">
                  {etiquetaTablet(t)}
                </span>
                <span className="shrink-0 font-mono text-[10px] tracking-label text-sig-dim">
                  {antiguedad(t.segundos_desde_heartbeat)}
                </span>
              </button>
            ))}
          </div>
          {aviso && <p className="sig-meta mt-3 text-sig-amber/80">{aviso}</p>}
        </>
      )}
    </Card>
  );
}
