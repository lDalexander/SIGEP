import React, { useCallback, useEffect, useState } from 'react';
import { Label, Button, Logo, Tabs } from '../ui';
import AdminLogin from './AdminLogin';
import TabOperarios from './TabOperarios';
import TabProduccion from './TabProduccion';
import TabChecklists from './TabChecklists';
import TabJerarquia from './TabJerarquia';
import TabMensajes from './TabMensajes';
import TabUsuarios from './TabUsuarios';
import {
  leerSesion, registrarCaducidad, salir, esSuperadmin,
  msDeInactividad, AVISO_INACTIVIDAD,
} from '../../lib/adminApi';
import useInactividad from '../../lib/useInactividad';

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
  /* Por qué se volvió al login, si no fue el propio usuario: se enseña ahí para no
     dejar la impresión de que la web ha perdido la sesión sola. */
  const [aviso, setAviso] = useState(null);

  /* Cualquier 401 del backend significa sesión caducada: puede ser un reinicio del
     servicio (los tokens viven en la memoria del proceso) o los 15 minutos de
     inactividad, y el motivo lo dice el propio backend en `detail`. */
  const caducar = useCallback((motivo) => {
    setSesion(null);
    setAviso(motivo || null);
  }, []);
  useEffect(() => {
    registrarCaducidad(caducar);
    return () => registrarCaducidad(null);
  }, [caducar]);

  const cerrarSesion = async () => {
    await salir();
    setSesion(null);
    setAviso(null);
  };

  /* Cierre por inactividad en el propio navegador. No sustituye al del backend —que
     es el que manda— sino que evita el caso en que no hay ninguna petición en curso
     (todas las pestañas menos Mensajes cargan una vez) y el panel se quedaría
     abierto y con aspecto de operativo aunque el token ya estuviera muerto. */
  const cerrarPorInactividad = useCallback(async () => {
    await salir();
    setSesion(null);
    setAviso(AVISO_INACTIVIDAD);
  }, []);
  useInactividad({
    activo: !!sesion,
    limiteMs: msDeInactividad(sesion),
    alVencer: cerrarPorInactividad,
  });

  if (!sesion) {
    const entrar = (nueva) => { setAviso(null); setSesion(nueva); };
    return <AdminLogin onEntrar={entrar} onVolver={onVolver} aviso={aviso} />;
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
