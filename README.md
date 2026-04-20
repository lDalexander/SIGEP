# SIGEP - Sistema de Gestión de Producción

Sistema integral para la gestión y monitoreo de producción industrial. Este proyecto consta de un backend desarrollado en Python (FastAPI) y un frontend moderno desarrollado en React.

## Estructura del Proyecto

- **api_produccion/**: Backend con FastAPI, encargado del procesamiento de datos y lógica de negocio.
- **frontend_sigep/**: Interfaz de usuario dinámica construida con React y Tailwind CSS para visualización en tiempo real.

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

## Características
- Monitoreo en tiempo real.
- Dashboard industrial optimizado.
- Arquitectura escalable y desacoplada.
