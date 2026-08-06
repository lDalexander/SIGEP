import React, { useState } from 'react';
import { X } from 'lucide-react';
import { Card, Badge, Button, Dot, Estado, Input, Select, Aviso, useAviso, Label } from '../ui';
import { plural } from '../../lib/format';
import useApi from '../../lib/useApi';
import { admin, mensajeDeError } from '../../lib/adminApi';

const TIPOS = ['Sólido', 'Líquido'];

/** El backend normaliza a SOLIDO/LIQUIDO; aquí se muestra con tilde. */
function tipoBonito(tipo) {
  return String(tipo || '').toUpperCase() === 'LIQUIDO' ? 'Líquido' : 'Sólido';
}
function tipoContrario(tipo) {
  return String(tipo || '').toUpperCase() === 'LIQUIDO' ? 'Sólido' : 'Líquido';
}

/**
 * Pestaña «Jerarquía» — qué produce cada máquina: marca + presentación, y las
 * fragancias que hace de cada marca.
 *
 * Es la fuente de verdad que consume la app Android para filtrar los selectores al
 * iniciar turno, y la cachea offline; editar aquí se refleja en las tablets al
 * reconectar, sin reinstalar la app.
 *
 * Son DOS jerarquías con granularidad distinta, y no es un descuido:
 * `maquina_productos` va por (máquina, marca, presentación) y
 * `maquina_marca_fragancias` por (máquina, marca) — la fragancia no depende del
 * gramaje, ULTREX 1 KG y ULTREX 3 KG de la misma máquina llevan las mismas.
 *
 * «Eliminar» hace baja lógica (`PUT {activo:false}`) en lugar del `DELETE` físico,
 * para no romper el histórico.
 */
export default function TabJerarquia() {
  const matriz = useApi('/maquina_productos', { cliente: admin });
  const fragMatriz = useApi('/maquina_fragancias', { cliente: admin });
  const catalogos = useApi('/catalogos', { cliente: admin });
  const { aviso, ok, fallo } = useAviso();

  const [nuevaMaquina, setNuevaMaquina] = useState('');
  const [tipoMaquina, setTipoMaquina] = useState('Sólido');
  const [nuevaMarca, setNuevaMarca] = useState('');
  const [nuevaPresentacion, setNuevaPresentacion] = useState('');
  const [nuevaFragancia, setNuevaFragancia] = useState('');

  /* Combinación en preparación, por máquina: { [maquina_id]: {marca, presentacion} } */
  const [combo, setCombo] = useState({});
  /* Fragancia en preparación, por máquina+marca: { "3|ULTREX": "Floral" } */
  const [fragElegida, setFragElegida] = useState({});

  const maquinas = Array.isArray(matriz.datos) ? matriz.datos : [];
  const marcas = catalogos.datos?.marcas || [];
  const presentaciones = catalogos.datos?.presentaciones || [];
  const fragancias = catalogos.datos?.fragancias || [];

  /* maquina_id -> marcas[] con sus fragancias, tal como lo devuelve la API. */
  const fragPorMaquina = {};
  (Array.isArray(fragMatriz.datos) ? fragMatriz.datos : []).forEach((m) => {
    fragPorMaquina[m.maquina_id] = m.marcas || [];
  });

  const recargarTodo = () => {
    matriz.recargar();
    fragMatriz.recargar();
    catalogos.recargar();
  };

  /* ── Catálogos maestros ─────────────────────────────── */
  const crear = async (ruta, cuerpo, etiqueta, limpiar) => {
    try {
      const { data } = await admin.post(ruta, cuerpo);
      ok(data.reactivado ? `${etiqueta} reactivada` : `${etiqueta} agregada`);
      limpiar();
      recargarTodo();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  /* ── Máquinas ───────────────────────────────────────── */
  const alternarTipo = async (maquina) => {
    try {
      await admin.put(`/maquinas/${maquina.maquina_id}`, { tipo: tipoContrario(maquina.tipo) });
      ok(`${maquina.maquina} pasa a ${tipoContrario(maquina.tipo)}`);
      recargarTodo();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  const cambiarMaquinaActiva = async (maquina, activa) => {
    if (!activa && !window.confirm(
      `¿Desactivar ${maquina.maquina}?\n\nDejará de aparecer en las tablets al iniciar turno.`
    )) return;
    try {
      await admin.put(`/maquinas/${maquina.maquina_id}`, { activa });
      ok(`${maquina.maquina} ${activa ? 'reactivada' : 'desactivada'}`);
      recargarTodo();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  /* ── Combinaciones marca + presentación ─────────────── */
  const agregarCombinacion = async (maquina) => {
    const eleccion = combo[maquina.maquina_id] || {};
    if (!eleccion.marca || !eleccion.presentacion) {
      fallo('Elige marca y presentación');
      return;
    }
    try {
      await admin.post('/maquina_productos', {
        maquina_id: maquina.maquina_id,
        marca: eleccion.marca,
        presentacion: eleccion.presentacion,
      });
      ok(`${eleccion.marca} · ${eleccion.presentacion} añadida a ${maquina.maquina}`);
      setCombo((prev) => ({ ...prev, [maquina.maquina_id]: {} }));
      matriz.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  const cambiarComboActivo = async (producto, activo, confirmar) => {
    if (confirmar && !window.confirm(confirmar)) return;
    try {
      await admin.put(`/maquina_productos/${producto.id}`, { activo });
      ok(`${producto.marca} · ${producto.presentacion} ${activo ? 'reactivada' : 'desactivada'}`);
      matriz.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  /* ── Fragancias por máquina + marca ─────────────────── */
  const agregarFragancia = async (maquina, marca) => {
    const clave = `${maquina.maquina_id}|${marca}`;
    const fragancia = fragElegida[clave];
    if (!fragancia) {
      fallo(`Elige una fragancia para ${marca}`);
      return;
    }
    try {
      await admin.post('/maquina_fragancias', {
        maquina_id: maquina.maquina_id,
        marca,
        fragancia,
      });
      ok(`${fragancia} añadida a ${marca} en ${maquina.maquina}`);
      setFragElegida((prev) => ({ ...prev, [clave]: '' }));
      fragMatriz.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  /* Quitar es baja lógica, igual que en las combinaciones: el histórico de sesiones
     se cruza con la fragancia por texto y un borrado físico lo dejaría colgando. */
  const cambiarFraganciaActiva = async (fila, activo, contexto) => {
    try {
      await admin.put(`/maquina_fragancias/${fila.id}`, { activo });
      ok(`${fila.fragancia} ${activo ? 'reactivada' : 'quitada'} en ${contexto}`);
      fragMatriz.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    }
  };

  return (
    <div>
      <div className="flex items-start justify-between gap-4 mb-5">
        <Label caja="normal" className="text-sig-dim">
          Qué marca, presentación y fragancia produce cada máquina · se sincroniza con las
          tablets al reconectar
        </Label>
        <Button onClick={recargarTodo} className="shrink-0">Recargar</Button>
      </div>

      <Aviso aviso={aviso} className="mb-4" />

      <div className="space-y-5">
        <Card titulo="Gestionar catálogos" meta="crea nuevas máquinas, marcas y presentaciones">
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-3">
              <Input
                value={nuevaMaquina}
                onChange={(e) => setNuevaMaquina(e.target.value)}
                placeholder="Nueva máquina (ej. Máquina 20)"
                aria-label="Nueva máquina"
                className="flex-1 min-w-[220px]"
              />
              <Select
                value={tipoMaquina}
                onChange={(e) => setTipoMaquina(e.target.value)}
                opciones={TIPOS}
                vacio={null} /* obligatorio: sin opción vacía */
                aria-label="Tipo de línea"
                className="w-[130px]"
              />
              <Button
                variante="primary"
                className="px-5 py-2.5"
                onClick={() =>
                  crear('/maquinas', { nombre: nuevaMaquina.trim(), tipo: tipoMaquina }, 'Máquina',
                    () => setNuevaMaquina(''))
                }
              >
                Agregar máquina
              </Button>
            </div>

            <div className="flex flex-wrap items-center gap-3">
              <Input
                value={nuevaMarca}
                onChange={(e) => setNuevaMarca(e.target.value)}
                placeholder="Nueva marca (ej. ARIEL)"
                aria-label="Nueva marca"
                className="flex-1 min-w-[220px]"
              />
              <Button
                variante="primary"
                className="px-5 py-2.5"
                onClick={() =>
                  crear('/marcas', { nombre: nuevaMarca.trim() }, 'Marca', () => setNuevaMarca(''))
                }
              >
                Agregar marca
              </Button>
            </div>

            <div className="flex flex-wrap items-center gap-3">
              <Input
                value={nuevaPresentacion}
                onChange={(e) => setNuevaPresentacion(e.target.value)}
                placeholder="Nueva presentación (ej. 750 GR)"
                aria-label="Nueva presentación"
                className="flex-1 min-w-[220px]"
              />
              <Button
                variante="primary"
                className="px-5 py-2.5"
                onClick={() =>
                  crear('/presentaciones', { nombre: nuevaPresentacion.trim() }, 'Presentación',
                    () => setNuevaPresentacion(''))
                }
              >
                Agregar presentación
              </Button>
            </div>

            <div className="flex flex-wrap items-center gap-3">
              <Input
                value={nuevaFragancia}
                onChange={(e) => setNuevaFragancia(e.target.value)}
                placeholder="Nueva fragancia (ej. Lavanda)"
                aria-label="Nueva fragancia"
                className="flex-1 min-w-[220px]"
              />
              <Button
                variante="primary"
                className="px-5 py-2.5"
                onClick={() =>
                  crear('/fragancias', { nombre: nuevaFragancia.trim() }, 'Fragancia',
                    () => setNuevaFragancia(''))
                }
              >
                Agregar fragancia
              </Button>
            </div>
          </div>
        </Card>

        {maquinas.length === 0 ? (
          <Card>
            <Estado
              cargando={matriz.cargando}
              error={matriz.error}
              vacio="Sin máquinas registradas"
            />
          </Card>
        ) : (
          maquinas.map((maquina) => {
            const eleccion = combo[maquina.maquina_id] || {};
            return (
              <Card
                key={maquina.maquina_id}
                sinPad
                className={maquina.activa ? '' : 'opacity-60'}
              >
                <header className="flex flex-wrap items-center gap-3 px-5 pt-4 pb-3">
                  <h2 className="text-[15px] font-bold text-sig-text">{maquina.maquina}</h2>
                  <Badge tono="gray">{tipoBonito(maquina.tipo)}</Badge>
                  <Label caja="normal" className="flex-1">
                    {plural(maquina.productos.length, 'combinación', 'combinaciones')}
                  </Label>
                  <Button tamano="sm" onClick={() => alternarTipo(maquina)}>
                    → {tipoContrario(maquina.tipo)}
                  </Button>
                  <Button
                    tamano="sm"
                    onClick={() => cambiarMaquinaActiva(maquina, !maquina.activa)}
                  >
                    {maquina.activa ? 'Desactivar' : 'Reactivar'}
                  </Button>
                </header>

                {maquina.productos.length > 0 && (
                  <ul className="divide-y divide-sig-line border-t border-sig-line">
                    {maquina.productos.map((p) => (
                      <li key={p.id} className="flex items-center gap-3 px-5 py-3">
                        <Dot tono={p.activo ? 'ok' : 'off'} />
                        <span className="flex-1 min-w-0 truncate text-[13px] font-bold text-sig-text">
                          {p.marca} · {p.presentacion}
                        </span>
                        {p.activo ? (
                          <>
                            <Button tamano="sm" onClick={() => cambiarComboActivo(p, false)}>
                              Desactivar
                            </Button>
                            <Button
                              tamano="sm"
                              onClick={() =>
                                cambiarComboActivo(
                                  p,
                                  false,
                                  `¿Eliminar ${p.marca} · ${p.presentacion} de ${maquina.maquina}?\n\nSe da de baja para las tablets, pero se conserva el histórico.`
                                )
                              }
                            >
                              Eliminar
                            </Button>
                          </>
                        ) : (
                          <Button tamano="sm" onClick={() => cambiarComboActivo(p, true)}>
                            Reactivar
                          </Button>
                        )}
                      </li>
                    ))}
                  </ul>
                )}

                {/* Alta de combinación para esta máquina */}
                <div className="flex flex-wrap items-center gap-2.5 border-t border-sig-line px-5 py-3">
                  <Select
                    value={eleccion.marca || ''}
                    onChange={(e) =>
                      setCombo((prev) => ({
                        ...prev,
                        [maquina.maquina_id]: { ...eleccion, marca: e.target.value },
                      }))
                    }
                    opciones={marcas}
                    vacio="Marca…"
                    aria-label={`Marca para ${maquina.maquina}`}
                    className="w-[190px]"
                  />
                  <Select
                    value={eleccion.presentacion || ''}
                    onChange={(e) =>
                      setCombo((prev) => ({
                        ...prev,
                        [maquina.maquina_id]: { ...eleccion, presentacion: e.target.value },
                      }))
                    }
                    opciones={presentaciones}
                    vacio="Presentación…"
                    aria-label={`Presentación para ${maquina.maquina}`}
                    className="w-[170px]"
                  />
                  <Button tamano="sm" onClick={() => agregarCombinacion(maquina)}>
                    Agregar combinación
                  </Button>
                </div>

                {/* Fragancias: cuelgan de (máquina, marca), no de la presentación.
                    Solo se ofrecen las marcas que esta máquina produce, para que no
                    se puedan configurar fragancias de una marca que nunca hará. */}
                <div className="border-t border-sig-line px-5 py-3">
                  <Label caja="normal" className="mb-2.5 block text-sig-muted">
                    Fragancias por marca
                  </Label>

                  {(fragPorMaquina[maquina.maquina_id] || []).length === 0 ? (
                    <Label caja="normal" className="text-sig-dim">
                      {fragMatriz.cargando
                        ? 'Cargando…'
                        : 'Sin marcas todavía — agrega una combinación arriba'}
                    </Label>
                  ) : (
                    <div className="space-y-2">
                      {(fragPorMaquina[maquina.maquina_id] || []).map((mf) => {
                        const clave = `${maquina.maquina_id}|${mf.marca}`;
                        const contexto = `${mf.marca} · ${maquina.maquina}`;
                        const activas = mf.fragancias.filter((f) => f.activo);
                        const inactivas = mf.fragancias.filter((f) => !f.activo);
                        return (
                          <div key={mf.marca} className="flex flex-wrap items-center gap-2">
                            <span className="w-[112px] shrink-0 font-mono text-[11px] uppercase
                                             tracking-label text-sig-muted">
                              {mf.marca}
                            </span>

                            {activas.map((f) => (
                              <button
                                key={f.id}
                                type="button"
                                onClick={() => cambiarFraganciaActiva(f, false, contexto)}
                                aria-label={`Quitar ${f.fragancia} de ${contexto}`}
                                title={`Quitar ${f.fragancia} de ${mf.marca} en ${maquina.maquina}`}
                                className="inline-flex items-center gap-1.5 rounded-full border
                                           border-sig-amber/25 bg-sig-amber/[0.12] px-2 py-[3px]
                                           font-mono text-[10px] uppercase tracking-label leading-none
                                           text-sig-amber transition-colors hover:bg-sig-amber/20"
                              >
                                {f.fragancia}
                                <X size={10} aria-hidden="true" />
                              </button>
                            ))}

                            {/* Sin ninguna activa la API cae al catálogo completo, así que
                                la tablet las ofrece TODAS. Decirlo evita leer el hueco
                                como «esta marca no lleva fragancia». */}
                            {activas.length === 0 && (
                              <Label caja="normal" className="text-sig-dim">
                                sin configurar · se ofrecen todas
                              </Label>
                            )}

                            {inactivas.map((f) => (
                              <button
                                key={f.id}
                                type="button"
                                onClick={() => cambiarFraganciaActiva(f, true, contexto)}
                                aria-label={`Reactivar ${f.fragancia} en ${contexto}`}
                                title={`Quitada. Pulsa para volver a ofrecer ${f.fragancia} en ${mf.marca}`}
                                className="inline-flex items-center gap-1.5 rounded-full border
                                           border-sig-line bg-white/[0.04] px-2 py-[3px] font-mono
                                           text-[10px] uppercase tracking-label leading-none
                                           text-sig-dim line-through transition-colors
                                           hover:text-sig-text"
                              >
                                {f.fragancia}
                              </button>
                            ))}

                            <div className="ml-auto flex items-center gap-2">
                              <Select
                                value={fragElegida[clave] || ''}
                                onChange={(e) =>
                                  setFragElegida((prev) => ({ ...prev, [clave]: e.target.value }))
                                }
                                opciones={fragancias.filter(
                                  (nombre) => !activas.some((f) => f.fragancia === nombre),
                                )}
                                vacio="Fragancia…"
                                aria-label={`Fragancia para ${mf.marca} en ${maquina.maquina}`}
                                className="w-[160px]"
                              />
                              <Button
                                tamano="sm"
                                onClick={() => agregarFragancia(maquina, mf.marca)}
                              >
                                Añadir
                              </Button>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              </Card>
            );
          })
        )}
      </div>
    </div>
  );
}
