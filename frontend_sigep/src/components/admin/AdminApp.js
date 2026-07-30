import React, { useCallback, useEffect, useState } from 'react';
import { Label, Button, Logo, Tabs } from '../ui';
import AdminLogin from './AdminLogin';
import TabOperarios from './TabOperarios';
import TabProduccion from './TabProduccion';
import TabChecklists from './TabChecklists';
import TabJerarquia from './TabJerarquia';
import TabMensajes from './TabMensajes';
import { leerSesion, registrarCaducidad, salir } from '../../lib/adminApi';

/* El orden de las pestañas es el de las capturas. */
const PESTANAS = [
  { value: 'operarios',  label: 'Operarios',  Componente: TabOperarios },
  { value: 'produccion', label: 'Producción', Componente: TabProduccion },
  { value: 'checklists', label: 'Checklists', Componente: TabChecklists },
  { value: 'jerarquia',  label: 'Jerarquía',  Componente: TabJerarquia },
  { value: 'mensajes',   label: 'Mensajes',   Componente: TabMensajes },
];

/**
 * Zona de administración: login, cabecera propia y las cinco pestañas.
 *
 * Props:
 *   onVolver : (opcional) navega al dashboard
 */
export default function AdminApp({ onVolver = () => {} }) {
  const [sesion, setSesion] = useState(() => leerSesion());
  const [pestana, setPestana] = useState('operarios');

  /* Cualquier 401 del backend significa sesión caducada (los tokens viven en la
     memoria del proceso y se pierden al reiniciar el servicio). */
  const caducar = useCallback(() => setSesion(null), []);
  useEffect(() => {
    registrarCaducidad(caducar);
    return () => registrarCaducidad(null);
  }, [caducar]);

  const cerrarSesion = async () => {
    await salir();
    setSesion(null);
  };

  if (!sesion) {
    return <AdminLogin onEntrar={setSesion} onVolver={onVolver} />;
  }

  const { Componente } = PESTANAS.find((p) => p.value === pestana) || PESTANAS[0];

  return (
    <div className="min-h-screen">
      <header className="border-b border-sig-line">
        <div className="mx-auto max-w-[1040px] px-6 py-4 flex items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <Logo tamano={34} />
            <div className="leading-none">
              <p className="text-[17px] font-bold tracking-tight text-sig-text">
                SIGEP · Administración
              </p>
              <Label className="block mt-1 text-sig-dim">{sesion.nivel || 'admin'}</Label>
            </div>
          </div>

          <div className="flex items-center gap-2.5 shrink-0">
            <Button onClick={onVolver} className="underline underline-offset-4">
              Dashboard
            </Button>
            <Button onClick={cerrarSesion}>Salir</Button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-[1040px] px-6 pb-14">
        <Tabs
          items={PESTANAS}
          value={pestana}
          onChange={setPestana}
          variante="underline"
          className="pt-6"
        />

        <div className="pt-6">
          <Componente />
        </div>
      </main>
    </div>
  );
}
