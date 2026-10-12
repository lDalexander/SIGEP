# SIGEP - Sistema de Gestión de Producción

Sistema integral para la gestión y monitoreo de producción industrial. Desde el
2026-10-12 es un **monorepo**: la app Android que corre en las tablets de planta vivía
en un repositorio aparte (`SIPEG`) y se fusionó aquí con todo su historial. `SIPEG`
quedó archivado.

## Estructura del Proyecto

- **api_produccion/**: Backend con FastAPI, encargado del procesamiento de datos y lógica de negocio.
- **frontend_sigep/**: Interfaz de usuario dinámica construida con React y Tailwind CSS para visualización en tiempo real.
- **android/**: App Kotlin de las tablets de planta (operario, insumista, supervisor). Se abre con Android Studio apuntando a **esta subcarpeta**, no a la raíz del repo.
- **plantillas/**: CSV de importación del catálogo de insumos y del BOM (recetas).
- **pruebas/**: Entorno Docker de pruebas (MySQL + API + web).
- **backups/**: Script de respaldo diario de la base y las fotos.

## Configuración

### Backend (API)
```bash
cd api_produccion
# Crear entorno virtual
python -m venv venv
source venv/bin/activate
# Instalar dependencias
pip install -r requirements.txt  # (Asegúrate de generar este archivo si no existe)
# Ejecutar
uvicorn main:app --reload
```

### Frontend
```bash
cd frontend_sigep
# Instalar dependencias
npm install
# Iniciar desarrollo
npm start
```

### App Android
```bash
cd android
# JAVA_HOME debe apuntar al JBR de Android Studio
./gradlew assembleRelease
```
Build, firma y versionado: ver `android/RELEASING.md`.

## Características
- Monitoreo en tiempo real.
- Dashboard industrial optimizado.
- Arquitectura escalable y desacoplada.
