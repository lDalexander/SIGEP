import React, { useState } from 'react';
import { Card, Badge, Button, Dot, Estado, Input, Select, Aviso, useAviso, Label, Campo } from '../ui';
import { plural } from '../../lib/format';
import useApi from '../../lib/useApi';
import { admin, mensajeDeError } from '../../lib/adminApi';

/* Espejo de NIVELES_VALIDOS en routers/admin.py. La descripción de cada uno la
   sirve /admin/niveles; esto es solo el orden y el rótulo del selector. */
const NIVELES = ['SUPERADMIN', 'ADMINPLANTA', 'ADMINBODEGA', 'ADMIN', 'CONSULTA'];

const MINIMO_CONTRASENA = 6;

/**
 * Pestaña «Usuarios» — administradores del sistema. Solo la ve un SUPERADMIN, y el
 * backend lo exige aparte: todos sus endpoints van con `require_superadmin`.
 *
 * Esta tabla NO es solo de la web. La app Android hace login contra ella
 * (`POST /api/admin/login`) y `GET /api/admin/supervisores` alimenta el selector
 * «Seleccione Supervisor» del checklist de las tablets, así que un alta o una baja
 * aquí cambia lo que ven las tablets. Los usuarios de nivel CONSULTA no aparecen en
 * ese selector, que es lo que se quiere.
 *
 * «Eliminar» hace `PUT {activo:false}`, como en el resto de la web: nunca DELETE.
 */
export default function TabUsuarios() {
  const usuarios = useApi('/usuarios', { cliente: admin });
  const niveles = useApi('/niveles', { cliente: admin });
  const { aviso, ok, fallo } = useAviso();

  const [nuevo, setNuevo] = useState({ username: '', password: '', nivel_acceso: 'ADMINPLANTA' });
  const [ocupado, setOcupado] = useState(null);

  const lista = Array.isArray(usuarios.datos) ? usuarios.datos : [];
  const activos = lista.filter((u) => u.activo).length;
  const descripciones = Object.fromEntries(
    (Array.isArray(niveles.datos) ? niveles.datos : []).map((n) => [n.nivel, n.descripcion]),
  );

  const crear = async () => {
    const username = nuevo.username.trim();
    if (!username) {
      fallo('El usuario es obligatorio');
      return;
    }
    if (nuevo.password.length < MINIMO_CONTRASENA) {
      fallo(`La contraseña debe tener al menos ${MINIMO_CONTRASENA} caracteres`);
      return;
    }
    setOcupado('nuevo');
    try {
      const { data } = await admin.post('/usuarios', { ...nuevo, username });
      ok(data.reactivado ? `${username} reactivado` : `${username} creado`);
      setNuevo({ username: '', password: '', nivel_acceso: 'ADMINPLANTA' });
      usuarios.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const actualizar = async (usuario, cambios, mensaje) => {
    setOcupado(usuario.id);
    try {
      await admin.put(`/usuarios/${usuario.id}`, cambios);
      ok(mensaje);
      usuarios.recargar();
    } catch (err) {
      fallo(mensajeDeError(err));
    } finally {
      setOcupado(null);
    }
  };

  const cambiarNivel = (usuario, nivel_acceso) => {
    if (nivel_acceso === usuario.nivel_acceso) return;
    actualizar(usuario, { nivel_acceso }, `${usuario.username} pasa a ${nivel_acceso}`);
  };

  const resetearContrasena = (usuario) => {
    const password = window.prompt(
      `Nueva contraseña para ${usuario.username} (mínimo ${MINIMO_CONTRASENA} caracteres):`
    );
    if (password === null) return;
    if (password.length < MINIMO_CONTRASENA) {
      fallo(`La contraseña debe tener al menos ${MINIMO_CONTRASENA} caracteres`);
      return;
    }
    actualizar(usuario, { password }, `Contraseña de ${usuario.username} cambiada`);
  };

  const cambiarActivo = (usuario, activo) => {
    if (!activo && !window.confirm(
      `¿Desactivar a ${usuario.username}?\n\n` +
      'No podrá entrar a la administración y desaparecerá del selector de ' +
      'supervisor de las tablets. El histórico se conserva.'
    )) return;
    actualizar(usuario, { activo }, `${usuario.username} ${activo ? 'reactivado' : 'desactivado'}`);
  };

  return (
    <div>
      <div className="flex items-start justify-between gap-4 mb-5">
        <Label caja="normal" className="text-sig-dim">
          Quién entra a la administración y con qué permisos · también son los
          supervisores que ofrecen las tablets en el checklist
        </Label>
        <Button onClick={usuarios.recargar} className="shrink-0">Recargar</Button>
      </div>

      <Aviso aviso={aviso} className="mb-4" />

      <div className="space-y-5">
        {/* El título no puede ser «Nuevo usuario»: Card lo expone como `aria-label`
            de la sección y chocaría con el del campo de usuario. */}
        <Card titulo="Alta de usuario" meta="la contraseña se guarda cifrada">
          <div className="grid grid-cols-1 sm:grid-cols-4 gap-3 items-end">
            <Campo etiqueta="Usuario">
              <Input
                value={nuevo.username}
                onChange={(e) => setNuevo((p) => ({ ...p, username: e.target.value }))}
                placeholder="ej. agarcia"
                aria-label="Nuevo usuario"
                autoComplete="off"
              />
            </Campo>
            <Campo etiqueta="Contraseña">
              <Input
                type="password"
                value={nuevo.password}
                onChange={(e) => setNuevo((p) => ({ ...p, password: e.target.value }))}
                placeholder={`mínimo ${MINIMO_CONTRASENA} caracteres`}
                aria-label="Contraseña del nuevo usuario"
                autoComplete="new-password"
              />
            </Campo>
            <Campo etiqueta="Nivel de acceso">
              <Select
                value={nuevo.nivel_acceso}
                onChange={(e) => setNuevo((p) => ({ ...p, nivel_acceso: e.target.value }))}
                opciones={NIVELES}
                vacio={null}
                aria-label="Nivel del nuevo usuario"
              />
            </Campo>
            <Button
              variante="primary"
              className="px-5 py-2.5"
              onClick={crear}
              disabled={ocupado === 'nuevo'}
            >
              {ocupado === 'nuevo' ? 'Creando…' : 'Crear usuario'}
            </Button>
          </div>
          {descripciones[nuevo.nivel_acceso] && (
            <Label caja="normal" className="mt-3 block text-sig-dim">
              {descripciones[nuevo.nivel_acceso]}
            </Label>
          )}
        </Card>

        <Card
          titulo="Usuarios"
          meta={`${activos} ${plural(activos, 'activo', 'activos')} · ${lista.length} en total`}
          sinPad
        >
          {lista.length === 0 ? (
            <div className="px-5 py-4">
              <Estado
                cargando={usuarios.cargando}
                error={usuarios.error}
                vacio="Sin usuarios registrados"
              />
            </div>
          ) : (
            <ul className="divide-y divide-sig-line border-t border-sig-line">
              {lista.map((usuario) => (
                <li
                  key={usuario.id}
                  className={`flex flex-wrap items-center gap-3 px-5 py-3 ${usuario.activo ? '' : 'opacity-60'}`}
                >
                  <Dot tono={usuario.activo ? 'ok' : 'off'} />
                  <span className="text-[13px] font-bold text-sig-text">{usuario.username}</span>
                  {usuario.es_tu_usuario && <Badge tono="amber">tú</Badge>}
                  {/* Mientras siga en texto plano se ve; se arregla solo la próxima
                      vez que esa persona inicie sesión. */}
                  {!usuario.password_migrada && (
                    <Badge tono="gray" className="normal-case">
                      contraseña sin cifrar
                    </Badge>
                  )}

                  <div className="ml-auto flex flex-wrap items-center gap-2">
                    <Select
                      value={usuario.nivel_acceso}
                      onChange={(e) => cambiarNivel(usuario, e.target.value)}
                      opciones={NIVELES}
                      vacio={null}
                      disabled={ocupado === usuario.id}
                      aria-label={`Nivel de ${usuario.username}`}
                      className="w-[160px]"
                    />
                    <Button
                      tamano="sm"
                      onClick={() => resetearContrasena(usuario)}
                      disabled={ocupado === usuario.id}
                    >
                      Cambiar contraseña
                    </Button>
                    {usuario.activo ? (
                      <Button
                        tamano="sm"
                        onClick={() => cambiarActivo(usuario, false)}
                        disabled={ocupado === usuario.id || usuario.es_tu_usuario}
                        title={usuario.es_tu_usuario ? 'No puedes desactivar tu propio usuario' : undefined}
                      >
                        Eliminar
                      </Button>
                    ) : (
                      <Button
                        tamano="sm"
                        onClick={() => cambiarActivo(usuario, true)}
                        disabled={ocupado === usuario.id}
                      >
                        Reactivar
                      </Button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </div>
  );
}
