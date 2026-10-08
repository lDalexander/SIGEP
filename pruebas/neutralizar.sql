-- Se ejecuta tras cargar la copia: la BD de producción trae la configuración de correo
-- (config_correo) y con ella el reporte semanal de los viernes. Sin contraseña SMTP no
-- sale ningún correo; con semanal_activo=0 el programador ni lo intenta.
UPDATE config_correo SET smtp_pass = NULL, semanal_activo = 0;
