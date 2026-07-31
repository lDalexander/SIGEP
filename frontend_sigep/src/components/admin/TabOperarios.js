import React, { useState } from 'react';
import { Card, Badge, Button, Dot, Estado, Input, Select, Tabs, Aviso, useAviso } from '../ui';
import { num } from '../../lib/format';
import useApi from '../../lib/useApi';
import { admin, mensajeDeError } from '../../lib/adminApi';

/* Mismo vocabulario que las máquinas: un operario de línea líquida trabaja en
   máquinas líquidas. El backend normaliza a SOLIDO/LIQUIDO. */
const TIPOS = ['Sólido', 'Líquido'];

const FILTROS = [
  { value: 'TODOS',   label: 'Todos' },
  { value: 'SOLIDO',  label: 'Sólido' },
  { value: 'LIQUIDO', label: 'Líquido' },
];

function tipoBonito(tipo) {
  return String(tipo || '').toUpperCase() === 'LIQUIDO' ? 'Líquido' : 'Sólido';
}

/**
 * Pestaña «Operarios» — alta, clasificación por línea, desactivación y baja lógica.
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
  const [tipo, setTipo] = useState('Sólido');
  const [filtro, setFiltro] = useState('TODOS');
  const [ocupado, setOcupado] = useState(false);
  const { aviso, ok, fallo } = useAviso();

  const operarios = Array.isArray(datos) ? datos : [];

  const visibles = operarios
    .filter((o) => filtro === 'TODOS' || String(o.tipo).toUpperCase() === filtro)
    .sort((a, b) => a.nombre.localeCompare(b.nombre, 'es'));

  const activos = visibles.filter((o) => o.activo).length;

  const agregar = async (evento) => {
    evento.preventDefault();
    const limpio = nombre.trim().toUpperCase(); // los operarios se guardan en mayúsculas
    if (!limpio) {
      fallo('Escribe el nombre del operario');
      return;
    }
    setOcupado(true);
    try {
      const { data } = await admin.post('/operadores', { nombre: limpio, tipo });
      ok(`${limpio} ${data.reactivado ? 'reactivado' : 'agregado'} en línea ${tipo}`);
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

  const cambiarTipo = async (operario) => {
    const nuevo = tipoBonito(operario.tipo) === 'Sólido' ? 'Líquido' : 'Sólido';
    if (!window.confirm(
      `¿Pasar a ${operario.nombre} a la línea ${nuevo}?\n\n`
      + `Dejará de aparecer en el selector de las máquinas de línea ${tipoBonito(operario.tipo)}.`
    )) return;
    try {
      await admin.put(`/operadores/${operario.id}`, { tipo: nuevo });
      ok(`${operario.nombre} pasa a línea ${nuevo}`);
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
          <Select
            value={tipo}
            onChange={(e) => setTipo(e.target.value)}
            opciones={TIPOS}
            vacio={null} /* obligatorio: sin opción vacía */
            aria-label="Línea del operario"
            className="w-[130px]"
          />
          <Button type="submit" variante="primary" disabled={ocupado} className="px-6 py-2.5">
            Agregar
          </Button>
        </form>
        <Aviso aviso={aviso} className="mt-3" />
      </Card>

      <Card
        titulo="Operarios registrados"
        meta={
          <div className="flex flex-wrap items-center gap-3">
            <Tabs items={FILTROS} value={filtro} onChange={setFiltro} />
            <span className="sig-meta">
              {num(activos)} activos · {num(visibles.length)} en total
            </span>
          </div>
        }
        sinPad
      >
        {visibles.length === 0 ? (
          <Estado
            cargando={cargando}
            error={error}
            vacio={filtro === 'TODOS'
              ? 'Sin operarios registrados'
              : `Sin operarios de línea ${tipoBonito(filtro)}`}
          />
        ) : (
          <ul className="divide-y divide-sig-line">
            {visibles.map((o) => (
              <li key={o.id} aria-label={o.nombre} className="flex items-center gap-3 px-5 py-3.5">
                <Dot tono={o.activo ? 'ok' : 'off'} />
                <span className="flex-1 min-w-0 truncate text-[14px] font-bold text-sig-text">
                  {o.nombre}
                </span>

                <Badge tono="gray">{tipoBonito(o.tipo)}</Badge>
                <Badge tono={o.activo ? 'ok' : 'gray'}>
                  {o.activo ? 'Activo' : 'Inactivo'}
                </Badge>

                <Button
                  tamano="sm"
                  onClick={() => cambiarTipo(o)}
                  title="Cambiar de línea"
                >
                  → {tipoBonito(o.tipo) === 'Sólido' ? 'Líquido' : 'Sólido'}
                </Button>

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
