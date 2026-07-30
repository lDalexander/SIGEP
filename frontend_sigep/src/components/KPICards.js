import React from 'react';
import { StatCard, Cifra, Label, Dot } from './ui';
import { Esqueleto } from './ui/Estado';
import { num } from '../lib/format';

/**
 * Las tres tarjetas KPI del dashboard.
 *
 * La tercera («LÍNEAS CON TURNO HOY») no tiene endpoint propio: se deriva de
 * /dashboard/estado_operativo, contando sesiones por estado.
 *
 * Props:
 *   kpis        : respuesta de /dashboard/kpis
 *   operaciones : respuesta de /dashboard/estado_operativo
 *   cargando, error
 */
export default function KPICards({ kpis, operaciones = [], cargando, error }) {
  const sesiones = operaciones.length;
  const activas = operaciones.filter((o) => o.estado === 'Activo').length;
  const finalizadas = sesiones - activas;

  const esperando = cargando && !kpis;

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 wide:grid-cols-3 gap-5">
      {/* 1 — Producción de hoy: pacas y sacos son magnitudes distintas y van separadas
             (el backend cuenta como sacos las presentaciones de 15/25 KG). */}
      <StatCard etiqueta="Producción de hoy" acento="ok">
        {esperando ? (
          <Esqueleto className="h-11 w-40" />
        ) : (
          <div className="flex items-stretch gap-6">
            <Cifra valor={num(kpis?.pacas_hoy)} unidad="Pacas" />
            <span aria-hidden="true" className="w-px bg-sig-line" />
            <Cifra valor={num(kpis?.sacos_hoy)} unidad="Sacos · 15/25 KG" />
          </div>
        )}
      </StatCard>

      {/* 2 — Turnos activos */}
      <StatCard
        etiqueta="Turnos activos"
        acento="ok"
        pie={
          <span className="inline-flex items-center gap-2">
            <Dot tono="ok" />
            <Label caja="normal">líneas en marcha</Label>
          </span>
        }
      >
        {esperando ? (
          <Esqueleto className="h-11 w-20" />
        ) : (
          <div className="flex items-baseline gap-2.5">
            <p className="text-[42px] leading-none font-extrabold tracking-tight text-white tabular-nums">
              {num(kpis?.turnos_activos)}
            </p>
            <span className="text-[13px] text-sig-muted">en curso</span>
          </div>
        )}
      </StatCard>

      {/* 3 — Líneas con turno hoy (derivado del estado operativo) */}
      <StatCard
        etiqueta="Líneas con turno hoy"
        acento="amber"
        pie={
          <Label caja="normal">
            {num(activas)} activas · {num(finalizadas)} finalizada(s)
          </Label>
        }
      >
        {cargando && !sesiones ? (
          <Esqueleto className="h-11 w-20" />
        ) : (
          <div className="flex items-baseline gap-2.5">
            <p className="text-[42px] leading-none font-extrabold tracking-tight text-white tabular-nums">
              {num(sesiones)}
            </p>
            <span className="text-[13px] text-sig-muted">sesiones</span>
          </div>
        )}
      </StatCard>

      {error && (
        <p className="sm:col-span-2 wide:col-span-3 sig-meta text-sig-amber/70">
          Sin conexión con el servidor — se muestran los últimos datos recibidos
        </p>
      )}
    </div>
  );
}
