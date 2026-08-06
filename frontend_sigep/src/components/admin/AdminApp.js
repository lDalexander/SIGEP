import React, { useCallback, useEffect, useState } from 'react';
import { Label, Button, Logo, Tabs } from '../ui';
import AdminLogin from './AdminLogin';
import TabOperarios from './TabOperarios';
import TabProduccion from './TabProduccion';
import TabChecklists from './TabChecklists';
import TabJerarquia from './TabJerarquia';
import TabMensajes from './TabMensajes';
import TabUsuarios from './TabUsuarios';
import { leerSesion, registrarCaducidad, salir, esSuperadmin } from '../../lib/adminApi';

/* El orden de las cinco primeras es el de las capturas; «Usuarios» se añadió
   después (2026-08-06) y solo la ve un SUPERADMIN — `soloSuperadmin`. */
const PESTANAS = [
  { value: 'operarios',  label: 'Operarios',  Componente: TabOperarios },
  { value: 'produccion', label: 'Producción', Componente: TabProduccion },
  { value: 'checklists', label: 'Checklists', Componente: TabChecklists },
  { value: 'jerarquia',  label: 'Jerarquía',  Componente: TabJerarquia },
  { value: 'mensajes',   label: 'Mensajes',   Componente: TabMensajes },
  { value: 'usuarios',   label: 'Usuarios',   Componente: TabUsuarios, soloSuperadmin: true },
];

/**
 * Zona de administración: login, cabecera propia y las pestañas.
 *
 * Que una pestaña no se muestre NO es el control de acceso: el backend exige el
 * nivel en cada endpoint y responde 403. Esto solo evita enseñar controles que el
 * servidor va a rechazar.
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

  const visibles = PESTANAS.filter((p) => !p.soloSuperadmin || esSuperadmin(sesion));
  const { Componente } = visibles.find((p) => p.value === pestana) || visibles[0];

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
          items={visibles}
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
