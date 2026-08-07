import React, { useState } from 'react';
import { Card, Button, Textarea, Checkbox, Estado, Aviso, useAviso, Label } from '../ui';
import useApi from '../../lib/useApi';
import { antiguedad } from '../../lib/format';
import { admin, mensajeDeError } from '../../lib/adminApi';

/* Plantillas que rellenan el textarea con un toque. */
const PLANTILLAS = [
  'Sube la velocidad',
  'Baja la velocidad',
  'Cambia de referencia',
  'Revisa la máquina',
];

/**
 * Pestaña «Mensajes» — envía una alerta emergente a las tablets en producción.
 *
 * Usa el endpoint masivo que ya consume la app Android: se persiste el mensaje y se
 * empuja por WebSocket; si la tablet está offline lo recoge en su próximo heartbeat.
 * Una lista vacía de `sesion_ids` significa TODAS las sesiones activas, así que hay
 * que enviarla siempre poblada salvo en el envío general.
 */
export default function TabMensajes() {
  const { datos, cargando, error, recargar } = useApi('/sesiones_activas', {
    cliente: admin,
    intervalo: 15000,
  });
  const { aviso, ok, fallo } = useAviso();

  const [texto, setTexto] = useState('');
  const [elegidas, setElegidas] = useState([]);
  const [enviando, setEnviando] = useState(false);

  const sesiones = Array.isArray(datos) ? datos : [];
  const todasElegidas = sesiones.length > 0 && elegidas.length === sesiones.length;

  const alternar = (sesionId) =>
    setElegidas((prev) =>
      prev.includes(sesionId) ? prev.filter((id) => id !== sesionId) : [...prev, sesionId]
    );

  const alternarTodas = () =>
    setElegidas(todasElegidas ? [] : sesiones.map((s) => s.sesion_id));

  const enviar = async ({ aTodas }) => {
    const mensaje = texto.trim();
    if (!mensaje) {
      fallo('Escribe la alerta antes de enviarla');
      return;
    }
    if (aTodas && !window.confirm(
      `¿Enviar esta alerta a TODAS las sesiones activas (${sesiones.length})?\n\n«${mensaje}»`
    )) return;

    setEnviando(true);
    try {
      // sesion_ids vacío/ausente => todas las activas (contrato del backend).
      const cuerpo = aTodas ? { texto: mensaje } : { texto: mensaje, sesion_ids: elegidas };
      const { data } = await admin.post('/mensajes/masivo', cuerpo);
      ok(data.mensaje || `Alerta enviada a ${data.enviados} tablet(s)`);
      setTexto('');
      setElegidas([]);
    } catch (err) {
      fallo(mensajeDeError(err, 'No se pudo enviar la alerta'));
    } finally {
      setEnviando(false);
    }
  };

  return (
    <div>
      <div className="flex items-start justify-between gap-4 mb-5">
        <Label caja="normal" className="text-sig-dim">
          Envía una alerta (ventana emergente) a las tablets en producción · a una, a varias o a todas
        </Label>
        <Button onClick={recargar} className="shrink-0">Recargar</Button>
      </div>

      <div className="space-y-5">
        <Card
          titulo="Componer alerta"
          meta={`${elegidas.length} seleccionadas`}
        >
          <div className="flex flex-wrap gap-2 mb-3">
            {PLANTILLAS.map((p) => (
              <Button key={p} tamano="sm" onClick={() => setTexto(p)}>
                {p}
              </Button>
            ))}
          </div>

          <Textarea
            value={texto}
            onChange={(e) => setTexto(e.target.value)}
            placeholder="Escribe la alerta..."
            aria-label="Texto de la alerta"
            maxLength={500}
          />

          <div className="flex flex-wrap items-center gap-3 mt-3.5">
            <Button
              variante="primary"
              onClick={() => enviar({ aTodas: false })}
              disabled={elegidas.length === 0 || enviando}
              className="px-5 py-2.5"
            >
              Enviar a seleccionadas
            </Button>
            <Button
              onClick={() => enviar({ aTodas: true })}
              disabled={sesiones.length === 0 || enviando}
            >
              📢 Enviar a TODAS las activas
            </Button>
          </div>

          <Aviso aviso={aviso} className="mt-3" />
        </Card>

        <Card
          titulo="Sesiones activas"
          meta={
            <Checkbox
              checked={todasElegidas}
              onChange={alternarTodas}
              disabled={sesiones.length === 0}
              etiqueta={<Label>Seleccionar todas</Label>}
            />
          }
          sinPad
        >
          {sesiones.length === 0 ? (
            <Estado cargando={cargando} error={error} vacio="Sin sesiones activas ahora mismo" />
          ) : (
            <ul className="divide-y divide-sig-line">
              {sesiones.map((s) => (
                <li key={s.sesion_id} className="flex items-center gap-6 px-5 py-3.5">
                  <Checkbox
                    checked={elegidas.includes(s.sesion_id)}
                    onChange={() => alternar(s.sesion_id)}
                    aria-label={`Seleccionar ${s.maquina} · ${s.operador}`}
                  />

                  <div className="min-w-0 flex-1">
                    <Label className="block truncate">
                      {s.maquina} · {s.operador}
                    </Label>
                    <Label className="block mt-1 truncate text-sig-dim">
                      {s.producto} · desde {s.inicio}
                    </Label>
                  </div>

                  {/* No dice si la tablet «está encendida» —eso no lo sabe nadie—
                      sino cuándo verá el mensaje, que es lo que se decide aquí. El
                      ONLINE/OFFLINE anterior salía del heartbeat con un umbral de
                      60 s, y como los latidos llegan cada 20 minutos marcaba OFFLINE
                      a máquinas que estaban produciendo. */}
                  <div className="shrink-0 text-right">
                    <span
                      className={`text-[12px] font-semibold tracking-tight ${
                        s.tablet_online ? 'text-sig-ok' : 'text-sig-muted'
                      }`}
                    >
                      {s.tablet_online ? 'AL INSTANTE' : 'EN COLA'}
                    </span>
                    <Label className="block mt-1 text-sig-dim">
                      {s.tablet_online
                        ? 'conectada'
                        : s.segundos_desde_contacto === null ||
                          s.segundos_desde_contacto === undefined
                          ? 'sin contacto'
                          : `contacto hace ${antiguedad(s.segundos_desde_contacto)}`}
                    </Label>
                  </div>
                </li>
              ))}
            </ul>
          )}

          {sesiones.length > 0 && (
            <p className="sig-meta border-t border-sig-line px-5 py-3 text-sig-dim">
              «En cola» no es un fallo: el mensaje se guarda y la tablet lo recibe en
              cuanto vuelve a conectar. «Al instante» significa que su conexión está
              abierta ahora mismo.
            </p>
          )}
        </Card>
      </div>
    </div>
  );
}
