import React, { useEffect, useState } from 'react';
import { Package, Settings, OctagonPause, LayoutDashboard } from 'lucide-react';
import { Label, Button, Dot, Logo } from './ui';
import { hora, fechaCorta } from '../lib/format';

/**
 * Cabecera del centro de control.
 *   izquierda : logo + SIGEP / CENTRO DE CONTROL (vuelve al dashboard al pulsarlo)
 *   centro    : reloj en vivo (HORA PLANTA · GYE) y fecha, separados por filetes
 *   derecha   : ● EN VIVO, Dashboard, Paros, Insumos, ⚙ Admin
 *
 * Props:
 *   enVivo    : true si el último refresco fue correcto
 *   onNavegar : (vista) => void  — 'dashboard' | 'paros' | 'insumos' | 'admin'
 *   vista     : vista activa, para resaltar su botón
 */
export default function Header({ enVivo = true, onNavegar = () => {}, vista = 'dashboard' }) {
  const [ahora, setAhora] = useState(() => new Date());

  // El reloj de la cabecera corre por su cuenta, sin depender del polling de datos.
  useEffect(() => {
    const id = setInterval(() => setAhora(new Date()), 1000);
    return () => clearInterval(id);
  }, []);

  return (
    <header className="border-b border-sig-line">
      <div className="mx-auto max-w-[1400px] px-6 py-3.5 flex items-center gap-6">
        {/* Marca — también es la vuelta al dashboard, como en cualquier web */}
        <button
          type="button"
          onClick={() => onNavegar('dashboard')}
          aria-label="Ir al dashboard"
          className="flex items-center gap-3 shrink-0 rounded-lg -m-1 p-1
                     transition-colors hover:bg-white/[0.03]"
        >
          <Logo tamano={34} />
          <div className="leading-none text-left">
            <p className="text-[17px] font-bold tracking-tight text-sig-text">SIGEP</p>
            <Label className="block mt-1 text-sig-dim">Centro de control</Label>
          </div>
        </button>

        {/* Reloj y fecha, centrados */}
        <div className="flex-1 flex items-center justify-center gap-6">
          <div className="text-center">
            <p className="font-mono text-[19px] font-semibold tabular-nums leading-none text-sig-text">
              {hora(ahora)}
            </p>
            <Label className="block mt-1.5">Hora planta · GYE</Label>
          </div>

          <span aria-hidden="true" className="h-9 w-px bg-sig-line" />

          <div className="text-center">
            <p className="text-[19px] font-bold leading-none text-sig-text">{fechaCorta(ahora)}</p>
            <Label className="block mt-1.5">Fecha</Label>
          </div>

          <span aria-hidden="true" className="h-9 w-px bg-sig-line" />
        </div>

        {/* Estado y accesos */}
        <div className="flex items-center gap-3 shrink-0">
          <span className="inline-flex items-center gap-2">
            <Dot tono={enVivo ? 'ok' : 'bad'} pulso={enVivo} />
            <Label className={enVivo ? 'text-sig-ok' : 'text-red-400'}>
              {enVivo ? 'En vivo' : 'Sin conexión'}
            </Label>
          </span>

          <span aria-hidden="true" className="h-9 w-px bg-sig-line" />

          {/* El botón de la vista activa se marca con el borde ámbar, no con el
              primario sólido: en la cabecera destacaría más que el reloj. */}
          <Button
            onClick={() => onNavegar('dashboard')}
            className={vista === 'dashboard' ? 'border-sig-amber/60 text-sig-amber' : ''}
          >
            <LayoutDashboard size={14} className="text-sig-amber" />
            Dashboard
          </Button>

          <Button
            onClick={() => onNavegar('paros')}
            className={vista === 'paros' ? 'border-sig-amber/60 text-sig-amber' : ''}
          >
            <OctagonPause size={14} className="text-sig-amber" />
            Paros
          </Button>

          <Button
            onClick={() => onNavegar('insumos')}
            className={vista === 'insumos' ? 'border-sig-amber/60 text-sig-amber' : ''}
          >
            <Package size={14} className="text-sig-amber" />
            Insumos
          </Button>

          <Button onClick={() => onNavegar('admin')}>
            <Settings size={14} />
            Admin
          </Button>
        </div>
      </div>
    </header>
  );
}
