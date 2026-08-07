import React, { useState } from 'react';
import { Label, Button, Logo, Campo, Input } from '../ui';
import { entrar, mensajeDeError } from '../../lib/adminApi';

/**
 * Pantalla de acceso a Administración. No hay captura de referencia de esta pantalla
 * (las cinco del admin están ya autenticadas), así que sigue el sistema de diseño.
 */
export default function AdminLogin({ onEntrar, onVolver, aviso = null }) {
  const [nombre, setNombre] = useState('');
  const [pin, setPin] = useState('');
  const [error, setError] = useState(null);
  const [enviando, setEnviando] = useState(false);

  const enviar = async (evento) => {
    evento.preventDefault();
    if (!nombre.trim() || !pin) {
      setError('Escribe el usuario y la contraseña');
      return;
    }
    setEnviando(true);
    setError(null);
    try {
      const sesion = await entrar(nombre.trim(), pin);
      onEntrar(sesion);
    } catch (err) {
      setError(mensajeDeError(err, 'Usuario o contraseña incorrectos'));
    } finally {
      setEnviando(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center px-6 py-12">
      <form onSubmit={enviar} className="sig-card w-full max-w-[380px] animate-fade-in p-7">
        <div className="flex items-center gap-3">
          <Logo tamano={34} />
          <div className="leading-none">
            <p className="text-[17px] font-bold tracking-tight text-sig-text">SIGEP</p>
            <Label className="block mt-1 text-sig-dim">Administración</Label>
          </div>
        </div>

        {/* Por qué se volvió al login sin que el usuario pulsara «Salir»: inactividad,
            o un reinicio del servicio que se llevó los tokens por delante. Se oculta
            en cuanto hay un error del intento actual, que es más reciente. */}
        {aviso && !error && (
          <p role="status" className="sig-meta mt-6 -mb-1 text-sig-dim">
            {aviso}
          </p>
        )}

        <div className="mt-7 space-y-4">
          <Campo etiqueta="Usuario">
            <Input
              value={nombre}
              onChange={(e) => setNombre(e.target.value)}
              autoComplete="username"
              autoFocus
            />
          </Campo>

          <Campo etiqueta="Contraseña">
            <Input
              type="password"
              value={pin}
              onChange={(e) => setPin(e.target.value)}
              autoComplete="current-password"
            />
          </Campo>
        </div>

        {error && (
          <p role="alert" className="sig-meta mt-4 text-sig-amber">
            {error}
          </p>
        )}

        <Button
          type="submit"
          variante="primary"
          disabled={enviando}
          className="mt-6 w-full justify-center py-2.5"
        >
          {enviando ? 'Comprobando…' : 'Entrar'}
        </Button>

        <button
          type="button"
          onClick={onVolver}
          className="sig-meta mt-5 block w-full text-center text-sig-dim hover:text-sig-text transition-colors"
        >
          volver al dashboard
        </button>
      </form>
    </div>
  );
}
