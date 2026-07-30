import React, { useState } from 'react';
import { Card, Badge, Button, Campo, Input, Textarea, Checkbox, Estado, Aviso, useAviso, Label } from '../ui';
import { fechaISO, plural } from '../../lib/format';
import FiltroRango from './FiltroRango';
import useApi from '../../lib/useApi';
import { admin, mensajeDeError } from '../../lib/adminApi';

/**
 * Pestaña «Checklists» — corrige los checklists de mantenimiento de las tablets.
 *
 * Los ítems se leen SIEMPRE de la respuesta de la API (cada checklist trae sus propios
 * `items` con su `id`); no hay ninguna lista escrita en el código, de modo que si
 * cambian en la base de datos, la pantalla cambia con ellos.
 */
export default function TabChecklists() {
  const hoy = fechaISO();
  const [rango, setRango] = useState({ desde: hoy, hasta: hoy });
  const [aplicado, setAplicado] = useState({ desde: hoy, hasta: hoy });
  const { aviso, ok, fallo } = useAviso();

  const { datos, cargando, error, recargar } = useApi('/checklists', {
    params: aplicado,
    cliente: admin,
  });

  /* Borradores: { [checklistId]: { supervisor?, comentarios?, items: {[itemId]: bool} } } */
  const [borrador, setBorrador] = useState({});
  const [guardando, setGuardando] = useState(null);

  const lista = Array.isArray(datos) ? datos : [];

  const marcadoDe = (checklist, item) =>
    borrador[checklist.id]?.items?.[item.id] ?? item.marcado;

  const textoDe = (checklist, campo) =>
    borrador[checklist.id]?.[campo] ?? checklist[campo] ?? '';

  const alternarItem = (checklist, item) =>
    setBorrador((prev) => {
      const actual = prev[checklist.id] || {};
      return {
        ...prev,
        [checklist.id]: {
          ...actual,
          items: { ...actual.items, [item.id]: !marcadoDe(checklist, item) },
        },
      };
    });

  const editarTexto = (checklist, campo, valor) =>
    setBorrador((prev) => ({
      ...prev,
      [checklist.id]: { ...prev[checklist.id], [campo]: valor },
    }));

  const guardar = async (checklist) => {
    const cambios = borrador[checklist.id];
    if (!cambios) {
      fallo('No hay cambios en este checklist');
      return;
    }
    const cuerpo = {};
    if (cambios.supervisor !== undefined) cuerpo.supervisor = cambios.supervisor;
    if (cambios.comentarios !== undefined) cuerpo.comentarios = cambios.comentarios;
    if (cambios.items) {
      cuerpo.items = Object.entries(cambios.items).map(([id, marcado]) => ({
        id: Number(id),
        marcado,
      }));
    }

    setGuardando(checklist.id);
    try {
      await admin.put(`/checklists/${checklist.id}`, cuerpo);
      ok(`Checklist #${checklist.id} guardado`);
      setBorrador((prev) => {
        const resto = { ...prev };
        delete resto[checklist.id];
        return resto;
      });
      recargar();
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
        contador={plural(lista.length, 'checklist', 'checklists')}
      />

      <Aviso aviso={aviso} className="mb-4" />

      {lista.length === 0 ? (
        <Card>
          <Estado cargando={cargando} error={error} vacio="Sin checklists en el rango" />
        </Card>
      ) : (
        <div className="space-y-5">
          {lista.map((c) => (
            <Card key={c.id} sinPad>
              <header className="flex items-center justify-between gap-3 border-b border-sig-line px-5 py-3.5">
                <h2 className="text-[14px] font-bold text-sig-text truncate">
                  #{c.id} · {c.maquina} · {c.operador}
                </h2>
                <Badge tono={c.momento === 'ENTRADA' ? 'ok' : 'amber'}>{c.momento}</Badge>
              </header>

              <div className="px-5 pt-3">
                <Label caja="normal">
                  {[c.fecha_turno, c.codigo_turno, c.hora].filter(Boolean).join(' · ')}
                </Label>
              </div>

              <ul className="px-5 py-4 space-y-3.5">
                {(c.items || []).map((item) => (
                  <li key={item.id}>
                    <Checkbox
                      checked={marcadoDe(c, item)}
                      onChange={() => alternarItem(c, item)}
                      etiqueta={
                        <span className="font-mono text-[12px] font-bold uppercase tracking-label text-sig-text">
                          {item.etiqueta}
                        </span>
                      }
                    />
                  </li>
                ))}
              </ul>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 border-t border-sig-line px-5 py-4">
                <Campo etiqueta="Supervisor">
                  <Input
                    value={textoDe(c, 'supervisor')}
                    onChange={(e) => editarTexto(c, 'supervisor', e.target.value)}
                  />
                </Campo>
                <Campo etiqueta="Comentarios">
                  <Textarea
                    rows={2}
                    value={textoDe(c, 'comentarios')}
                    onChange={(e) => editarTexto(c, 'comentarios', e.target.value)}
                  />
                </Campo>
              </div>

              <div className="flex justify-end border-t border-sig-line px-5 py-3">
                <Button
                  variante="primary"
                  onClick={() => guardar(c)}
                  disabled={!borrador[c.id] || guardando === c.id}
                >
                  {guardando === c.id ? 'Guardando…' : 'Guardar checklist'}
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
