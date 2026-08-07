import React, { useCallback, useEffect, useState } from 'react';
import { Label, Button, Logo, Tabs } from '../ui';
import AdminLogin from './AdminLogin';
import TabOperarios from './TabOperarios';
import TabProduccion from './TabProduccion';
import TabChecklists from './TabChecklists';
import TabJerarquia from './TabJerarquia';
import TabMensajes from './TabMensajes';
import TabUsuarios from './TabUsuarios';
import TabCorreo from './TabCorreo';
import TabParos from './TabParos';
import TabInsumos from './TabInsumos';
import TabFeedback from './TabFeedback';
import TabTablets from './TabTablets';
import {
  leerSesion, registrarCaducidad, salir, tieneAcceso, nivelActual,
  msDeInactividad, AVISO_INACTIVIDAD,
  NIVELES_VER_PLANTA, NIVELES_BODEGA, NIVELES_SISTEMA,
} from '../../lib/adminApi';
import useInactividad from '../../lib/useInactividad';

/* El orden de las cinco primeras es el de las capturas; el resto se fue añadiendo
   después. `niveles` es quién ve cada una — reparto acordado el 2026-08-07:

     planta  (ADMINPLANTA, ADMIN, y CONSULTA en solo lectura) → las seis de operación
     bodega  (ADMINBODEGA)                                    → solo Insumos
     sistema (SUPERADMIN)                                     → además reportes,
             tablets, usuarios y correo, y todo lo anterior

   Ocultar una pestaña NO es el control de acceso: cada endpoint exige su nivel en el
   backend y responde 403. Esto solo evita enseñar lo que el servidor va a rechazar. */
const PESTANAS = [
  { value: 'operarios',  label: 'Operarios',  Componente: TabOperarios,  niveles: NIVELES_VER_PLANTA },
  { value: 'produccion', label: 'Producción', Componente: TabProduccion, niveles: NIVELES_VER_PLANTA },
  { value: 'paros',      label: 'Paros',      Componente: TabParos,      niveles: NIVELES_VER_PLANTA },
  { value: 'checklists', label: 'Checklists', Componente: TabChecklists, niveles: NIVELES_VER_PLANTA },
  { value: 'jerarquia',  label: 'Jerarquía',  Componente: TabJerarquia,  niveles: NIVELES_VER_PLANTA },
  { value: 'insumos',    label: 'Insumos',    Componente: TabInsumos,    niveles: NIVELES_BODEGA },
  { value: 'reportes',   label: 'Reportes',   Componente: TabFeedback,   niveles: NIVELES_SISTEMA },
  { value: 'mensajes',   label: 'Mensajes',   Componente: TabMensajes,   niveles: NIVELES_VER_PLANTA },
  { value: 'tablets',    label: 'Tablets',    Componente: TabTablets,    niveles: NIVELES_SISTEMA },
  { value: 'usuarios',   label: 'Usuarios',   Componente: TabUsuarios,   niveles: NIVELES_SISTEMA },
  { value: 'correo',     label: 'Correo',     Componente: TabCorreo,     niveles: NIVELES_SISTEMA },
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

  const visibles = PESTANAS.filter((p) => tieneAcceso(p.niveles, sesion));
  /* La pestaña inicial es «operarios», que un ADMINBODEGA no ve: se cae a la primera
     que le corresponda. Y si su nivel no da acceso a ninguna, se dice en vez de
     reventar al buscar el componente. */
  const actual = visibles.find((p) => p.value === pestana) || visibles[0];
  const Componente = actual?.Componente;

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
          {Componente ? (
            <Componente />
          ) : (
            <p className="sig-card p-6 text-[13px] text-sig-muted">
              Tu nivel de acceso ({nivelActual(sesion) || '—'}) no tiene ninguna sección
              asignada en la administración. Habla con un SUPERADMIN si necesitas entrar.
            </p>
          )}
        </div>
      </main>
    </div>
  );
}
