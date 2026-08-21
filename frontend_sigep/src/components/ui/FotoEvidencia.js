import React, { useCallback, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { X, ExternalLink, ImageOff, Maximize2 } from 'lucide-react';
import Label from './Label';
import { urlFoto } from '../../lib/fotos';

/**
 * Evidencia fotográfica de un paro: miniatura + visor a tamaño completo.
 *
 * Vive en `ui/` y no en `paros/` porque lo usan las dos pantallas que enseñan paros
 * —la vista `/paros` y `/admin` → Paros— y son las dos que tienen que verse igual. Si
 * cada una montara su propia miniatura, arreglar la URL en una y no en la otra dejaría
 * la mitad de la planta mirando un icono roto (mismo criterio que `services/velocidad.py`
 * en el backend: el emparejamiento vive en un solo sitio para que no pueda divergir).
 *
 * Tres decisiones que no son evidentes:
 *
 * - **La foto se abre en un visor dentro de la propia página**, no en otra pestaña. La
 *   URL real apunta al puerto 8000 (ver `lib/fotos.js`), así que «abrir en otra pestaña»
 *   saca al supervisor del centro de control y encima a otro puerto; el enlace sigue
 *   estando, dentro del visor, para quien quiera la imagen a pantalla completa o
 *   descargarla.
 * - **Si la imagen no carga se dice, con la ruta delante.** Es el fallo que de verdad va
 *   a pasar: el archivo borrado a mano de `static/paros/`, o el 8000 inalcanzable desde
 *   ese equipo. Un icono de imagen rota se lee como «la web está mal hecha» y no deja
 *   ninguna pista de qué mirar.
 * - **Sin foto no se deja hueco**: devuelve `null`. Lo normal es que un paro no tenga
 *   foto —es opcional— y un recuadro vacío en cada fila sugeriría que falta algo.
 * - **El visor se monta en `document.body` con un portal, y eso no es opcional.** `Card`
 *   lleva `animate-fade-in`, cuya animación está declarada con `both`, así que el
 *   `transform: translateY(0)` del fotograma final **se queda aplicado para siempre**; un
 *   `transform` convierte al elemento en el bloque contenedor de sus descendientes
 *   `fixed`. Sin portal, el visor se posicionaba respecto a la tarjeta en vez del
 *   viewport y encima lo recortaba su `overflow-hidden`: en «Estado de máquinas», que es
 *   una tarjeta alta, el botón de cerrar quedaba fuera de la pantalla, y como el visor
 *   bloquea el scroll de la página **no había forma de llegar a él ni de cerrarlo**
 *   (reportado en planta el 2026-08-21). Es el mismo transform del que ya avisa el
 *   comentario del `z-40` de la barra de segmentadores en `App.js`.
 *
 * Props:
 *   ruta     : `foto_url` tal como la manda la API (`/static/paros/…`) o `null`
 *   etiqueta : de qué es la foto, para el texto alternativo (p. ej. «paro #130 de
 *              Máquina 7»); se compone como «Evidencia fotográfica · <etiqueta>»
 *   rotulo   : título del bloque; `null` para no ponerlo (dentro de una tarjeta apretada)
 *   alto     : clase de alto de la miniatura
 */
export default function FotoEvidencia({
  ruta,
  etiqueta = 'la incidencia',
  rotulo = 'Evidencia fotográfica',
  alto = 'h-28',
  className = '',
}) {
  const url = urlFoto(ruta);
  const [abierto, setAbierto] = useState(false);
  const [fallo, setFallo] = useState(false);

  /* La ruta puede cambiar bajo el mismo componente (el polling de 15 s trae otra foto,
     o se quita desde el admin): entonces el fallo anterior ya no describe a esta. */
  useEffect(() => { setFallo(false); }, [url]);

  const cerrar = useCallback(() => setAbierto(false), []);

  // Escape cierra el visor, y mientras está abierto la página de detrás no hace scroll.
  useEffect(() => {
    if (!abierto) return undefined;
    const alPulsar = (e) => { if (e.key === 'Escape') cerrar(); };
    document.addEventListener('keydown', alPulsar);
    const overflowPrevio = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', alPulsar);
      document.body.style.overflow = overflowPrevio;
    };
  }, [abierto, cerrar]);

  if (!url) return null;

  const alt = `Evidencia fotográfica · ${etiqueta}`;

  return (
    <div className={className}>
      {rotulo && <Label className="block mb-1.5">{rotulo}</Label>}

      {fallo ? (
        /* Se nombra la ruta que falló: es lo único que permite ir a buscar el archivo. */
        <div className="flex items-start gap-2 rounded-xl border border-sig-amber/30
                        bg-sig-amber/[0.04] px-3 py-2">
          <ImageOff size={14} className="mt-0.5 shrink-0 text-sig-amber/80" />
          <div className="min-w-0">
            <p className="text-[12px] leading-snug text-sig-amber/90">
              La foto está registrada pero no se pudo cargar.
            </p>
            <p className="mt-0.5 break-all font-mono text-[11px] leading-snug text-sig-dim">
              {ruta}
            </p>
          </div>
        </div>
      ) : (
        <button
          type="button"
          onClick={() => setAbierto(true)}
          title="Ver la foto a tamaño completo"
          aria-label={`Ver a tamaño completo la evidencia · ${etiqueta}`}
          className="group relative block rounded-xl border border-sig-line overflow-hidden
                     hover:border-sig-amber/60 transition-colors"
        >
          <img
            src={url}
            alt={alt}
            onError={() => setFallo(true)}
            className={`block ${alto} w-auto max-w-full object-cover`}
            loading="lazy"
          />
          <span className="absolute inset-0 flex items-center justify-center
                           bg-sig-bg/0 group-hover:bg-sig-bg/45 transition-colors">
            <Maximize2
              size={16}
              className="text-sig-text opacity-0 group-hover:opacity-100 transition-opacity"
            />
          </span>
        </button>
      )}

      {abierto && createPortal((
        <div
          role="dialog"
          aria-modal="true"
          aria-label={alt}
          onClick={cerrar}
          className="fixed inset-0 z-50 flex items-center justify-center overflow-auto
                     bg-sig-bg/90 p-4 sm:p-8"
        >
          {/* El clic en la imagen no cierra: al ampliar una pieza rota se pincha encima. */}
          <div
            onClick={(e) => e.stopPropagation()}
            className="flex max-h-full w-full max-w-4xl flex-col gap-3"
          >
            <div className="flex items-center justify-between gap-3">
              <Label className="truncate">{alt}</Label>
              <div className="flex shrink-0 items-center gap-2">
                <a
                  href={url}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1.5 rounded-lg border border-sig-line
                             px-3 py-1.5 text-[12px] font-semibold text-sig-text
                             hover:border-white/20 hover:bg-white/[0.03] transition-colors"
                >
                  <ExternalLink size={13} /> Abrir aparte
                </a>
                <button
                  type="button"
                  onClick={cerrar}
                  aria-label="Cerrar el visor"
                  className="inline-flex items-center gap-1.5 rounded-lg border border-sig-line
                             px-3 py-1.5 text-[12px] font-semibold text-sig-text
                             hover:border-white/20 hover:bg-white/[0.03] transition-colors"
                >
                  <X size={13} /> Cerrar
                </button>
              </div>
            </div>

            <img
              src={url}
              alt={alt}
              onError={() => { setFallo(true); cerrar(); }}
              className="min-h-0 flex-1 rounded-xl border border-sig-line
                         object-contain bg-sig-card"
            />

            <p className="break-all text-center font-mono text-[11px] text-sig-dim">
              {ruta}
            </p>
          </div>
        </div>
      ), document.body)}
    </div>
  );
}
