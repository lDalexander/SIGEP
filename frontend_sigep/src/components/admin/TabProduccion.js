import React, { useEffect, useState } from 'react';
import axios from 'axios';
import { Card, Badge, Button, Campo, Select, Estado, Aviso, useAviso, Label } from '../ui';
import { num, fechaISO, plural } from '../../lib/format';
import FiltroRango from './FiltroRango';
import useApi from '../../lib/useApi';
import { admin, mensajeDeError } from '../../lib/adminApi';

const CAMPOS = ['maquina', 'operador', 'marca', 'presentacion', 'fragancia'];

/**
 * Pestaña «Producción» — corrige los datos de las sesiones que llegan de las tablets.
 *
 * Los campos son selects poblados con los catálogos reales, no texto libre, para que
 * una corrección no introduzca variantes nuevas de un mismo nombre (las capturas
 * muestran inputs, pero el brief pide selects).
 *
 * No hay tabla maestra de fragancias, así que esa lista sale de los valores históricos
 * distintos que devuelve /dashboard/opciones_filtros.
 */
export default function TabProduccion() {
  const hoy = fechaISO();
  const [rango, setRango] = useState({ desde: hoy, hasta: hoy });
  const [aplicado, setAplicado] = useState({ desde: hoy, hasta: hoy });
  const { aviso, ok, fallo } = useAviso();

  const sesiones = useApi('/sesiones', { params: aplicado, cliente: admin });
  const catalogos = useApi('/catalogos', { cliente: admin });
  const operarios = useApi('/operadores', { cliente: admin });
  const [fragancias, setFragancias] = useState([]);

  /* Borradores por sesión: { [id]: {maquina, operador, ...} } */
  const [borrador, setBorrador] = useState({});
  const [guardando, setGuardando] = useState(null);

  useEffect(() => {
    axios
      .get('/api/dashboard/opciones_filtros', { timeout: 8000 })
      .then(({ data }) => setFragancias(data?.fragancia || []))
      .catch((err) => console.error('[SIGEP] Error en opciones_filtros:', err.message));
  }, []);

  const lista = Array.isArray(sesiones.datos) ? sesiones.datos : [];
  const nombresMaquina = (catalogos.datos?.maquinas || []).map((m) => m.nombre);
  const nombresOperario = (Array.isArray(operarios.datos) ? operarios.datos : [])
    .filter((o) => o.activo)
    .map((o) => o.nombre);

  const opcionesDe = {
    maquina: nombresMaquina,
    operador: nombresOperario,
    marca: catalogos.datos?.marcas || [],
    presentacion: catalogos.datos?.presentaciones || [],
    fragancia: fragancias,
  };

  const valorDe = (sesion, campo) =>
    borrador[sesion.id]?.[campo] ?? sesion[campo] ?? '';

  const editar = (sesion, campo, valor) =>
    setBorrador((prev) => ({
      ...prev,
      [sesion.id]: { ...prev[sesion.id], [campo]: valor },
    }));

  const guardar = async (sesion) => {
    const cambios = borrador[sesion.id];
    if (!cambios || Object.keys(cambios).length === 0) {
      fallo('No hay cambios en esta sesión');
      return;
    }
    setGuardando(sesion.id);
    try {
      await admin.put(`/sesiones/${sesion.id}`, cambios);
      ok(`Sesión #${sesion.id} guardada`);
      setBorrador((prev) => {
        const resto = { ...prev };
        delete resto[sesion.id];
        return resto;
      });
      sesiones.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setGuardando(null);
    }
  };

  return (
    <div>
      <FiltroRango
        desde={rango.desde}
        hasta={rango.hasta}
        onChange={(campo, valor) => setRango((prev) => ({ ...prev, [campo]: valor }))}
        onCargar={() => setAplicado({ ...rango })}
        contador={plural(lista.length, 'sesión', 'sesiones')}
      />

      <Aviso aviso={aviso} className="mb-4" />

      {lista.length === 0 ? (
        <Card>
          <Estado
            cargando={sesiones.cargando}
            error={sesiones.error}
            vacio="Sin sesiones en el rango"
          />
        </Card>
      ) : (
        <div className="space-y-5">
          {lista.map((sesion) => {
            const tocada = Boolean(borrador[sesion.id]);
            return (
              <Card key={sesion.id} sinPad>
                <header className="flex items-center justify-between gap-3 border-b border-sig-line px-5 py-3.5">
                  <h2 className="text-[14px] font-bold text-sig-text">
                    Sesión #{sesion.id} · {sesion.inicio}
                  </h2>
                  <Badge tono={sesion.estado === 'Activo' ? 'ok' : 'gray'}>{sesion.estado}</Badge>
                </header>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 px-5 py-4">
                  {CAMPOS.map((campo) => (
                    <Campo
                      key={campo}
                      etiqueta={campo === 'presentacion' ? 'Presentación' : campo}
                      className={campo === 'fragancia' ? 'sm:col-span-1' : ''}
                    >
                      <Select
                        value={valorDe(sesion, campo)}
                        onChange={(e) => editar(sesion, campo, e.target.value)}
                        opciones={opcionesDe[campo]}
                      />
                    </Campo>
                  ))}
                </div>

                <div className="flex flex-wrap items-center justify-end gap-3 border-t border-sig-line px-5 py-3">
                  <Label caja="normal" className="rounded-lg bg-sig-input px-3 py-1.5">
                    Pacas: {num(sesion.total_pacas)} ({num(sesion.n_registros)} reg.)
                  </Label>
                  {/* Habilitado siempre, como en las capturas: si no hay cambios el
                      propio `guardar` lo avisa en lugar de mandar un PUT vacío. */}
                  <Button
                    variante="primary"
                    onClick={() => guardar(sesion)}
                    disabled={guardando === sesion.id}
                    title={tocada ? undefined : 'Sin cambios pendientes'}
                  >
                    {guardando === sesion.id ? 'Guardando…' : 'Guardar sesión'}
                  </Button>
                </div>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
