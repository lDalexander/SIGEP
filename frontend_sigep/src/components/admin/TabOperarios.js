import React, { useState } from 'react';
import { Card, Badge, Button, Dot, Estado, Input, Aviso, useAviso } from '../ui';
import { num } from '../../lib/format';
import useApi from '../../lib/useApi';
import { admin, mensajeDeError } from '../../lib/adminApi';

/**
 * Pestaña «Operarios» — alta, desactivación y baja lógica.
 *
 * La app Android descarga la lista de operarios ACTIVOS cuando tiene conexión y la
 * cachea, así que los cambios aquí llegan a las tablets al reconectar.
 *
 * «Eliminar» hace baja lógica (`PUT {activo:false}`), no `DELETE`: el endpoint de
 * borrado del backend es físico y dejaría huérfanos los turnos y checklists
 * históricos de ese operario.
 */
export default function TabOperarios() {
  const { datos, cargando, error, recargar } = useApi('/operadores', { cliente: admin });
  const [nombre, setNombre] = useState('');
  const [ocupado, setOcupado] = useState(false);
  const { aviso, ok, fallo } = useAviso();

  const operarios = Array.isArray(datos) ? datos : [];
  const activos = operarios.filter((o) => o.activo).length;
  const ordenados = [...operarios].sort((a, b) =>
    a.nombre.localeCompare(b.nombre, 'es')
  );

  const agregar = async (evento) => {
    evento.preventDefault();
    const limpio = nombre.trim().toUpperCase(); // los operarios se guardan en mayúsculas
    if (!limpio) {
      fallo('Escribe el nombre del operario');
      return;
    }
    setOcupado(true);
    try {
      const { data } = await admin.post('/operadores', { nombre: limpio });
      ok(data.reactivado ? `${limpio} reactivado` : `${limpio} agregado`);
      setNombre('');
      recargar();
    } catch (err) {
      fallo(mensajeDeError(err, 'No se pudo agregar el operario'));
    } finally {
      setOcupado(false);
    }
  };

  const cambiarActivo = async (operario, activo, confirmar) => {
    if (confirmar && !window.confirm(confirmar)) return;
    try {
      await admin.put(`/operadores/${operario.id}`, { activo });
      ok(`${operario.nombre} ${activo ? 'reactivado' : 'desactivado'}`);
      recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  return (
    <div className="space-y-5">
      <Card titulo="Agregar operario" meta="se sincroniza con las tablets al reconectar">
        <form onSubmit={agregar} className="flex flex-wrap items-center gap-3">
          <Input
            value={nombre}
            onChange={(e) => setNombre(e.target.value)}
            placeholder="Nombre del operario (ej. JUAN PÉREZ)"
            aria-label="Nombre del operario"
            className="flex-1 min-w-[240px]"
          />
          <Button type="submit" variante="primary" disabled={ocupado} className="px-6 py-2.5">
            Agregar
          </Button>
        </form>
        <Aviso aviso={aviso} className="mt-3" />
      </Card>

      <Card
        titulo="Operarios registrados"
        meta={`${num(activos)} activos · ${num(operarios.length)} en total`}
        sinPad
      >
        {ordenados.length === 0 ? (
          <Estado cargando={cargando} error={error} vacio="Sin operarios registrados" />
        ) : (
          <ul className="divide-y divide-sig-line">
            {ordenados.map((o) => (
              <li key={o.id} aria-label={o.nombre} className="flex items-center gap-3 px-5 py-3.5">
                <Dot tono={o.activo ? 'ok' : 'off'} />
                <span className="flex-1 min-w-0 truncate text-[14px] font-bold text-sig-text">
                  {o.nombre}
                </span>
                <Badge tono={o.activo ? 'ok' : 'gray'}>
                  {o.activo ? 'Activo' : 'Inactivo'}
                </Badge>

                {o.activo ? (
                  <>
                    <Button tamano="sm" onClick={() => cambiarActivo(o, false)}>
                      Desactivar
                    </Button>
                    <Button
                      tamano="sm"
                      onClick={() =>
                        cambiarActivo(
                          o,
                          false,
                          `¿Eliminar a ${o.nombre}?\n\nSe da de baja para las tablets, pero se conserva su histórico de turnos y checklists.`
                        )
                      }
                    >
                      Eliminar
                    </Button>
                  </>
                ) : (
                  <Button tamano="sm" onClick={() => cambiarActivo(o, true)}>
                    Reactivar
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
