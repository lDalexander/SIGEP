import React, { useEffect, useState } from 'react';
import { Package, Settings } from 'lucide-react';
import { Label, Button, Dot, Logo } from './ui';
import { hora, fechaCorta } from '../lib/format';

/**
 * Cabecera del centro de control.
 *   izquierda : logo + SIGEP / CENTRO DE CONTROL
 *   centro    : reloj en vivo (HORA PLANTA · GYE) y fecha, separados por filetes
 *   derecha   : ● EN VIVO, Insumos, ⚙ Admin
 *
 * Props:
 *   enVivo    : true si el último refresco fue correcto
 *   onNavegar : (vista) => void  — 'insumos' | 'admin'
 */
export default function Header({ enVivo = true, onNavegar = () => {} }) {
  const [ahora, setAhora] = useState(() => new Date());

  // El reloj de la cabecera corre por su cuenta, sin depender del polling de datos.
  useEffect(() => {
    const id = setInterval(() => setAhora(new Date()), 1000);
    return () => clearInterval(id);
  }, []);

  return (
    <header className="border-b border-sig-line">
      <div className="mx-auto max-w-[1400px] px-6 py-3.5 flex items-center gap-6">
        {/* Marca */}
        <div className="flex items-center gap-3 shrink-0">
          <Logo tamano={34} />
          <div className="leading-none">
            <p className="text-[17px] font-bold tracking-tight text-sig-text">SIGEP</p>
            <Label className="block mt-1 text-sig-dim">Centro de control</Label>
          </div>
        </div>

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

          <Button onClick={() => onNavegar('insumos')}>
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
