import React, { useEffect, useState } from 'react';
import { Card, Badge, Button, Estado, Input, Aviso, useAviso, Label, Campo, Checkbox } from '../ui';
import useApi from '../../lib/useApi';
import { admin, mensajeDeError } from '../../lib/adminApi';

/* Las tres listas que mantiene el sistema. El orden es el de importancia para quien
   administra, no el alfabético: el semanal es lo nuevo y lo que más se va a tocar. */
const LISTAS = [
  {
    tipo: 'semanal',
    titulo: 'Reporte semanal de paros',
    ayuda: 'Se envía los viernes a las 12:00 con la semana cerrada (viernes a viernes).',
  },
  {
    tipo: 'reportes',
    titulo: 'Reportes de problemas con la app',
    ayuda: 'Cada vez que un operario reporta una falla desde la tablet.',
  },
  {
    tipo: 'pedidos',
    titulo: 'Pedidos de insumos',
    ayuda: 'Cada vez que una máquina pide insumos a bodega.',
  },
];

/* Validación deliberadamente laxa: solo descarta lo que seguro no es una dirección.
   Quien decide de verdad es el servidor SMTP, y una regla estricta rechazaría
   direcciones internas válidas. */
const PARECE_CORREO = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const ORIGEN = {
  bd: { texto: 'configurado aquí', tono: 'ok' },
  env: { texto: 'del servidor (.env)', tono: 'gray' },
  heredado: { texto: 'heredado de reportes', tono: 'amber' },
};

/**
 * Pestaña «Correo» — servidor SMTP, destinatarios y reporte semanal de paros.
 *
 * Solo la ve un SUPERADMIN y el backend lo exige aparte (`require_superadmin`): aquí
 * se decide quién recibe información de planta, se tocan las credenciales del correo
 * corporativo y los botones mandan correos de verdad.
 *
 * Un campo vacío NO significa «sin destinatarios»: significa «usa el del `.env`», que
 * es de donde salía todo antes de esta pantalla. Por eso cada lista dice de dónde sale
 * lo que está aplicando — si no, se editaría el `.env` creyendo que sigue mandando.
 */
export default function TabCorreo() {
  const config = useApi('/correo', { cliente: admin });
  const previa = useApi('/correo/semanal_vista_previa', { cliente: admin });
  const { aviso, ok, fallo } = useAviso();

  const [servidor, setServidor] = useState(null);
  const [clave, setClave] = useState('');
  const [nuevos, setNuevos] = useState({});   // texto en curso de cada input de la lista
  const [ocupado, setOcupado] = useState(null);

  const datos = config.datos;

  /* El formulario del servidor se rellena con lo que responde la API y a partir de ahí
     es del usuario: no se pisa en cada refresco, o borraría lo que esté escribiendo. */
  useEffect(() => {
    if (datos && servidor === null) {
      setServidor({
        smtp_host: datos.smtp_host || '',
        smtp_port: datos.smtp_port || 587,
        smtp_user: datos.smtp_user || '',
        smtp_from: datos.smtp_from || '',
      });
    }
  }, [datos, servidor]);

  const guardar = async (cambios, mensaje, marca = 'guardar') => {
    setOcupado(marca);
    try {
      const { data } = await admin.put('/correo', cambios);
      config.recargar();
      ok(mensaje);
      return data;
    } catch (err) {
      fallo(mensajeDeError(err));
      return null;
    } finally {
      setOcupado(null);
    }
  };

  const guardarServidor = async () => {
    const puerto = Number(servidor.smtp_port);
    if (!Number.isInteger(puerto) || puerto < 1 || puerto > 65535) {
      fallo('El puerto debe ser un número entre 1 y 65535');
      return;
    }
    const cambios = { ...servidor, smtp_port: puerto };
    /* La contraseña solo viaja si se escribió algo: mandarla vacía sería pedirle al
       backend que no la toque, pero mandarla siempre invita a borrarla por descuido. */
    if (clave.trim()) cambios.smtp_pass = clave.trim();
    const hecho = await guardar(cambios, 'Servidor de correo actualizado', 'servidor');
    if (hecho) setClave('');
  };

  const listaDe = (tipo, campo) => datos?.destinos?.[tipo]?.[campo] || [];

  const anadir = (tipo, campo) => {
    const clave2 = `${tipo}_${campo}`;
    const valor = (nuevos[clave2] || '').trim();
    if (!valor) return;
    if (!PARECE_CORREO.test(valor)) {
      fallo(`«${valor}» no parece una dirección de correo`);
      return;
    }
    const actual = listaDe(tipo, campo);
    if (actual.some((e) => e.toLowerCase() === valor.toLowerCase())) {
      fallo(`${valor} ya está en la lista`);
      return;
    }
    setNuevos((p) => ({ ...p, [clave2]: '' }));
    guardar({ [clave2]: [...actual, valor] }, `${valor} añadido`, clave2);
  };

  const quitar = (tipo, campo, correo) => {
    const restantes = listaDe(tipo, campo).filter((e) => e !== correo);
    /* Quitar el último TO deja la lista vacía, y eso hace que vuelva a mandar la del
       `.env` en vez de dejar el correo sin destino. Se avisa porque no es evidente. */
    if (campo === 'to' && restantes.length === 0 &&
      !window.confirm(
        `¿Quitar ${correo}?\n\nEs el último destinatario de esta lista: al quedarse ` +
        'vacía se volverán a usar los destinatarios configurados en el servidor (.env).'
      )) return;
    guardar({ [`${tipo}_${campo}`]: restantes }, `${correo} quitado`, `${tipo}_${campo}`);
  };

  const probar = async (tipo) => {
    setOcupado(`prueba_${tipo}`);
    try {
      const { data } = await admin.post('/correo/prueba', { tipo });
      ok(`Correo de prueba enviado a ${data.destino}`);
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const probarSuelto = async () => {
    const destinatario = window.prompt('¿A qué dirección mando la prueba?');
    if (destinatario === null) return;
    if (!PARECE_CORREO.test(destinatario.trim())) {
      fallo('Esa dirección no parece válida');
      return;
    }
    setOcupado('prueba_suelta');
    try {
      const { data } = await admin.post('/correo/prueba', {
        tipo: 'reportes', destinatario: destinatario.trim(),
      });
      ok(`Correo de prueba enviado a ${data.destino}`);
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const enviarSemanal = async () => {
    if (!window.confirm(
      '¿Enviar ahora el reporte de la última semana cerrada?\n\n' +
      'Se manda a los destinatarios de la lista. No afecta al envío automático ' +
      'del viernes, que saldrá igual.'
    )) return;
    setOcupado('semanal');
    try {
      const { data } = await admin.post('/correo/semanal_ahora');
      ok(`Reporte enviado · ${data.total_horas} en ${data.total_paros} paro(s)`);
      config.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  if (config.cargando || config.error || !datos) {
    return <Estado cargando={config.cargando} error={config.error} vacio="Sin configuración de correo" />;
  }

  const p = previa.datos;

  return (
    <div>
      <div className="flex items-start justify-between gap-4 mb-5">
        <Label caja="normal" className="text-sig-dim">
          Servidor de salida, quién recibe cada aviso y el reporte semanal de paros ·
          los cambios se aplican al siguiente correo, sin reiniciar nada
        </Label>
        <Button onClick={config.recargar} className="shrink-0">Recargar</Button>
      </div>

      <Aviso aviso={aviso} className="mb-4" />

      <div className="space-y-5">
        <Card
          titulo="Servidor de salida"
          meta={
            <Badge tono={datos.password_definida ? 'ok' : 'amber'}>
              {datos.password_definida
                ? (datos.password_en_bd ? 'contraseña guardada aquí' : 'contraseña del .env')
                : 'sin contraseña'}
            </Badge>
          }
        >
          {servidor && (
            <>
              <div className="grid grid-cols-1 sm:grid-cols-4 gap-3">
                <Campo etiqueta="Servidor">
                  <Input
                    value={servidor.smtp_host}
                    onChange={(e) => setServidor((s) => ({ ...s, smtp_host: e.target.value }))}
                    aria-label="Servidor SMTP"
                  />
                </Campo>
                <Campo etiqueta="Puerto">
                  <Input
                    value={servidor.smtp_port}
                    onChange={(e) => setServidor((s) => ({ ...s, smtp_port: e.target.value }))}
                    aria-label="Puerto SMTP"
                    inputMode="numeric"
                  />
                </Campo>
                <Campo etiqueta="Usuario">
                  <Input
                    value={servidor.smtp_user}
                    onChange={(e) => setServidor((s) => ({ ...s, smtp_user: e.target.value }))}
                    aria-label="Usuario SMTP"
                    autoComplete="off"
                  />
                </Campo>
                <Campo etiqueta="Remite">
                  <Input
                    value={servidor.smtp_from}
                    onChange={(e) => setServidor((s) => ({ ...s, smtp_from: e.target.value }))}
                    aria-label="Dirección del remitente"
                    autoComplete="off"
                  />
                </Campo>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 mt-3 items-end">
                <Campo etiqueta="Contraseña">
                  <Input
                    type="password"
                    value={clave}
                    onChange={(e) => setClave(e.target.value)}
                    placeholder="dejar vacío para no cambiarla"
                    aria-label="Contraseña SMTP"
                    autoComplete="new-password"
                  />
                </Campo>
                <div className="flex flex-wrap gap-2.5">
                  <Button
                    variante="primary"
                    onClick={guardarServidor}
                    disabled={ocupado === 'servidor'}
                    className="px-5 py-2.5"
                  >
                    Guardar servidor
                  </Button>
                  <Button onClick={probarSuelto} disabled={ocupado === 'prueba_suelta'}>
                    Probar a una dirección…
                  </Button>
                </div>
              </div>

              <Label className="block mt-3 text-sig-dim">
                La contraseña nunca se muestra. Para volver a la del servidor (.env),
                escribe un guion (-) y guarda.
              </Label>
            </>
          )}
        </Card>

        {LISTAS.map(({ tipo, titulo, ayuda }) => (
          <Card
            key={tipo}
            titulo={titulo}
            meta={
              <Badge tono={ORIGEN[datos.destinos[tipo].origen_to]?.tono || 'gray'}>
                {ORIGEN[datos.destinos[tipo].origen_to]?.texto || '—'}
              </Badge>
            }
          >
            <Label caja="normal" className="block text-sig-dim">{ayuda}</Label>

            {['to', 'cc'].map((campo) => (
              <div key={campo} className="mt-4">
                <Label className="block mb-2">{campo === 'to' ? 'Destinatarios' : 'Copia (CC)'}</Label>

                <div className="flex flex-wrap gap-2">
                  {listaDe(tipo, campo).length === 0 && (
                    <Label className="text-sig-dim">
                      {campo === 'to' ? 'sin destinatarios propios · se usan los del servidor' : 'sin copias'}
                    </Label>
                  )}
                  {listaDe(tipo, campo).map((correo) => (
                    <span
                      key={correo}
                      className="inline-flex items-center gap-1.5 rounded-full border border-sig-line
                                 bg-sig-input px-2.5 py-[3px] text-[12px] text-sig-text"
                    >
                      {correo}
                      <button
                        type="button"
                        onClick={() => quitar(tipo, campo, correo)}
                        className="text-sig-muted hover:text-sig-amber transition-colors"
                        aria-label={`Quitar ${correo} de ${campo === 'to' ? 'destinatarios' : 'copia'} de ${titulo}`}
                      >
                        ×
                      </button>
                    </span>
                  ))}
                </div>

                <div className="flex flex-wrap gap-2.5 mt-2.5">
                  <Input
                    value={nuevos[`${tipo}_${campo}`] || ''}
                    onChange={(e) => setNuevos((n) => ({ ...n, [`${tipo}_${campo}`]: e.target.value }))}
                    onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); anadir(tipo, campo); } }}
                    placeholder="nombre@detcuador.com"
                    aria-label={`Añadir a ${campo === 'to' ? 'destinatarios' : 'copia'} de ${titulo}`}
                    className="max-w-[280px]"
                  />
                  <Button
                    onClick={() => anadir(tipo, campo)}
                    disabled={ocupado === `${tipo}_${campo}`}
                  >
                    Añadir
                  </Button>
                </div>
              </div>
            ))}

            <div className="mt-4 pt-3 border-t border-sig-line">
              <Button onClick={() => probar(tipo)} disabled={ocupado === `prueba_${tipo}`}>
                {ocupado === `prueba_${tipo}` ? 'Enviando…' : 'Enviar correo de prueba'}
              </Button>
            </div>
          </Card>
        ))}

        <Card
          titulo="Reporte semanal de paros"
          meta={<Badge tono={datos.semanal_activo ? 'ok' : 'gray'}>
            {datos.semanal_activo ? 'activo' : 'desactivado'}
          </Badge>}
        >
          <div className="flex flex-wrap items-center gap-4">
            <Checkbox
              checked={!!datos.semanal_activo}
              onChange={(e) => guardar(
                { semanal_activo: e.target.checked },
                e.target.checked ? 'Envío automático activado' : 'Envío automático desactivado',
                'activo',
              )}
              etiqueta={<Label>Enviar automáticamente los viernes a las 12:00</Label>}
            />
            <Button onClick={enviarSemanal} disabled={ocupado === 'semanal'}>
              {ocupado === 'semanal' ? 'Enviando…' : 'Enviar ahora'}
            </Button>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mt-4">
            <div>
              <Label className="block text-sig-dim">Último envío</Label>
              <p className="text-[13px] text-sig-text mt-1">{datos.semanal_ultimo_envio || '—'}</p>
            </div>
            <div>
              <Label className="block text-sig-dim">Semana ya enviada hasta</Label>
              <p className="text-[13px] text-sig-text mt-1">{datos.semanal_ultima_ventana || '—'}</p>
            </div>
            <div>
              <Label className="block text-sig-dim">Próximo envío</Label>
              <p className="text-[13px] text-sig-text mt-1">{datos.semanal_proximo_envio || '—'}</p>
            </div>
          </div>

          {/* Vista previa: los mismos números que saldrían por correo, para no tener
              que mandarse uno a sí mismo solo para mirarlos. */}
          <div className="mt-5 pt-4 border-t border-sig-line">
            <Label className="block mb-3">Lo que se enviaría ahora</Label>
            {previa.cargando || previa.error || !p ? (
              <Estado cargando={previa.cargando} error={previa.error} vacio="Sin datos del periodo" />
            ) : (
              <>
                <Label className="block text-sig-dim">{p.desde} → {p.hasta}</Label>
                <p className="mt-1.5 text-[28px] font-bold tracking-tight text-sig-text">
                  {p.total_horas}
                </p>
                <Label className="block text-sig-dim">
                  {p.total_paros} paro(s) · media {p.promedio || '—'}
                  {p.variacion_pct !== null && p.variacion_pct !== undefined
                    ? ` · ${p.variacion_pct > 0 ? '▲' : '▼'} ${Math.abs(p.variacion_pct)}% vs ${p.previo_horas}`
                    : ''}
                </Label>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-5 mt-4">
                  {[['Categorías', p.por_categoria], ['Máquinas', p.por_maquina]].map(([rotulo, filas]) => (
                    <div key={rotulo}>
                      <Label className="block mb-2 text-sig-dim">{rotulo}</Label>
                      {filas.length === 0 ? (
                        <Label className="text-sig-dim">sin paros en el periodo</Label>
                      ) : (
                        <ul className="space-y-1.5">
                          {filas.map((f, i) => (
                            <li key={f.etiqueta} className="flex items-baseline justify-between gap-3">
                              <Label className="truncate">
                                <span className="text-sig-dim mr-2">{i + 1}</span>{f.etiqueta}
                              </Label>
                              <span className="shrink-0 text-[13px] font-semibold text-sig-text">
                                {f.horas}
                              </span>
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  ))}
                </div>

                {(p.sin_cierre > 0 || p.en_curso > 0) && (
                  <Label className="block mt-4 text-sig-amber">
                    {p.sin_cierre > 0 && `${p.sin_cierre} paro(s) sin cerrar: su duración se acota al fin del turno. `}
                    {p.en_curso > 0 && `${p.en_curso} paro(s) seguían abiertos.`}
                  </Label>
                )}
              </>
            )}
          </div>
        </Card>
      </div>
    </div>
  );
}
