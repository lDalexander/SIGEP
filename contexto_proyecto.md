# Contexto del Proyecto: SIGEP (Sistema de Gestión de Producción)

Este documento detalla la arquitectura, modelos de base de datos y flujos de negocio del backend del proyecto **SIGEP**, diseñado para interactuar con clientes frontend desarrollados en **Android Studio con Kotlin** (tablets de operadores e insumistas) y un panel web de administración en **React**.

---

## 1. Arquitectura General del Sistema
El sistema consta de:
1. **Backend (API)**: Construido en **FastAPI (Python)**, con base de datos **MySQL** administrada mediante **SQLAlchemy**.
2. **Frontend Tablets (Android/Kotlin)**: Utilizado por Operadores en planta y personal de Bodega (Insumistas). Se comunica con la API vía HTTP (REST) y mantiene WebSockets y Firebase Cloud Messaging (FCM) para tiempo real.
3. **Web Panel (React/Tailwind)**: Panel de supervisión y gestión.

---

## 2. Modelos de Base de Datos (`models.py`)
El modelo relacional mapea las entidades críticas del piso de producción:

*   **`OperadorDB`**: Operadores autorizados en la planta.
*   **`UsuarioDB`**: Usuarios con roles específicos (Insumista Empaque, Insumista Granel, Supervisor) que ingresan mediante PIN de 4 dígitos.
*   **`MaquinaDB`**: Las máquinas de la planta (líneas de producción).
*   **`SesionTrabajoDB`**: Registra cada turno de trabajo de un operador en una máquina. Almacena la marca del producto, fragancia, presentación, hora de inicio/fin de turno y duración.
*   **`PalletDB`**: Registro de pallets producidos en una sesión de trabajo. Tiene soporte para cantidad de pacas.
*   **`ParoMaquinaDB`**: Tiempos muertos o paradas de máquina. Registra el motivo, fecha de inicio/fin y la duración calculada.
*   **`PedidoBodegaDB`**: Solicitudes de insumos que hacen los operarios en planta a los Insumistas en Bodega. Flujo tipo "Uber" con estados:
    `Pendiente` ➔ `En Camino` ➔ `Entregado_Insumista` ➔ `Entregado` (Confirmado por operador).
*   **`EntregaProactivaDB`**: Entregas que hace el insumista preventivamente (planeación) sin que el operador las pida, con soporte opcional para fotos almacenadas en el servidor.
*   **`DispositivoFCMDB`**: Tokens FCM (Firebase) registrados para enviar notificaciones push en segundo plano a las tablets Android.
*   **`EstadoTabletDB`**: Monitoreo de latidos (`heartbeat`) de las tablets en red local, rastreando su estado (online/offline) y cantidad de registros en caché local pendientes por sincronizar.
*   **`ChecklistPlantillaDB` / `ChecklistCampoDB` / `ChecklistRespuestaDB` / `ChecklistRespuestaValorDB`**: Módulo dinámico para formular checklists pre-turno (ej. estado de equipo antes de iniciar labores) que los operadores deben responder obligatoriamente antes de poder iniciar su sesión.

---

## 3. Flujos de Trabajo Clave (Interacción Backend ➔ Kotlin Frontend)

### A. Flujo de Inicio de Turno y Operación (Tablet Operador)
1. **Checklist Obligatorio**: Antes de iniciar, la app verifica el estado del checklist (`GET /api/checklist/estado`). Si no está lleno, la app solicita la plantilla activa (`GET /api/checklist/plantilla_activa`), renderiza el formulario dinámico en Kotlin y envía las respuestas (`POST /api/checklist/responder`).
2. **Iniciar Sesión**: El operador inicia su turno (`POST /api/iniciar_turno`). El backend vincula el checklist realizado y abre la sesión.
3. **Registro de Producción**: La tablet envía la cantidad de pacas por pallet (`POST /api/registrar_pallet`).
4. **Registro de Paros**: Si la máquina se detiene, el operador registra el inicio del paro (`POST /api/paro/iniciar`) y luego su finalización (`POST /api/paro/finalizar`).
5. **Cerrar Turno**: Finaliza la sesión (`POST /api/finalizar_turno`).

### B. Flujo MES - Solicitud de Insumos "Estilo Uber"
1. **Crear Pedido**: El operador solicita un insumo (`POST /api/insumos/pedido_dinamico`). El backend analiza el texto para enrutarlo a la categoría adecuada ("Granel" o "Empaque") y crea el pedido en estado `Pendiente`.
2. **Alertas en Tiempo Real**: 
    *   Envía un mensaje por WebSocket (`broadcast_to_tipo`) a los insumistas activos.
    *   Envía una notificación push FCM tipo **data-only** (`notificar_insumistas_por_fcm`) para que el servicio de segundo plano en Android despierte la tablet incluso si la app está cerrada.
3. **Aceptar Pedido (Insumista)**: El insumista acepta la solicitud (`PUT /api/insumos/aceptar`), asignándose el pedido. El estado cambia a `En Camino` e informa a los demás insumistas por WS para que desaparezca de sus pendientes.
4. **Entregar Pedido (Insumista)**: Al llegar a la máquina, el insumista marca la entrega (`PUT /api/insumos/entregar`). El estado cambia a `Entregado_Insumista`.
5. **Confirmación de Recepción (Operador)**: El operador confirma en su tablet que recibió el material (`PUT /api/insumos/confirmar_recepcion`), y el estado pasa a `Entregado` (cerrando el pedido).

### C. Robustez y Sincronización Offline (Offline-First)
*   **Idempotencia con `request_id`**: Las tablets generan un UUID local único para cada inicio de turno, checklist y pallet. Si hay fallas de red y la tablet reintenta, el backend ignora duplicados usando este ID.
*   **Anti-Ráfaga (Server-Side)**: El backend descarta inserciones masivas accidentales (más de 3 inserciones en 5 segundos en una misma sesión) para evitar duplicación lógica en vaciados de cola offline.
*   **Heartbeat y Sync**: Las tablets reportan su latido (`POST /api/tablets/heartbeat`) informando cuántos pendientes tienen acumulados localmente. Si el backend requiere forzar una sincronización, manda una señal por el WebSocket persistente (`/ws/tablets/{device_id}`) o activa la bandera `sync_solicitada` en la respuesta del heartbeat para que la tablet vacíe su cola local.

---

## 4. Estructura de Módulos del Backend (`api_produccion/`)
*   `main.py`: Entrypoint de la aplicación FastAPI. Carga los routers y arranca el Garbage Collector de turnos viejos.
*   `models.py`: Definición de tablas SQLAlchemy (MySQL).
*   `schemas.py`: Validación de tipos y serialización de datos con Pydantic.
*   `database.py`: Inicialización de base de datos y sesión.
*   `ws_manager.py`: Manager de conexiones WebSocket para comunicación en tiempo real.
*   `routers/`:
    *   `auth.py`: Control de acceso y login rápido con PIN.
    *   `checklist.py`: Lógica y CRUD para checklists de operadores y admins.
    *   `operaciones.py`: Maneja el flujo operacional principal (turnos, pallets, paros).
    *   `insumos.py`: Maneja el flujo de pedidos "Uber" y entregas proactivas.
    *   `tablets.py`: Sincronización y WebSocket para monitoreo de tablets.
    *   `websocket_insumos.py` / `dispositivos.py`: Utilidades secundarias para WS e tokens FCM.
*   `services/fcm_service.py`: Integración del SDK de Firebase Admin para push data-only.
*   `tasks.py`: Tareas asíncronas en segundo plano (Garbage Collector).

---

## 5. Plantilla de Prompt para Claude
Copia y pega el siguiente texto en **Claude**, y en la sección indicada escribe en tus propias palabras qué necesitas agregar al proyecto. Claude entenderá todo el contexto y te escribirá un prompt optimizado para que me lo pases a mí (Antigravity/Gemini) y yo haga los cambios exactos en el código:

```text
Actúa como un arquitecto de software experto.
Estoy trabajando en un proyecto llamado SIGEP, que tiene un backend desarrollado en FastAPI (Python) y base de datos MySQL con SQLAlchemy. El frontend está hecho en Android Studio con Kotlin para tablets en planta de producción.

Aquí tienes el contexto técnico del backend:
- Modelos de BD: Operadores, Sesiones de Trabajo, Pallets producidos, Paros de máquina, Pedidos de bodega ("estilo Uber" con estados y enrutamiento a Empaque/Granel), Entregas proactivas, tokens FCM para notificaciones push en Android, estado/heartbeat de las tablets, y un sistema dinámico de Checklists pre-turno.
- Comunicación: API REST, WebSockets para alertas en tiempo real de insumos, y notificaciones FCM "data-only" de alta prioridad para despertar la app en Android en segundo plano.
- Seguridad y robustez: Validación de idempotencia con request_id generados por la tablet y sistema anti-ráfaga server-side.

QUIERO AGREGAR LO SIGUIENTE AL PROYECTO:
[Escribe aquí en tus propias palabras qué funcionalidad, tabla o cambio quieres añadir. No te preocupes si no sabes explicarlo técnicamente, solo dinos qué quieres que pase o qué botón/pantalla quieres agregar en la app]

Con base en esto, escribe una instrucción (un prompt detallado) en español dirigida a mi asistente de desarrollo de IA (Antigravity/Gemini) que tiene acceso completo al código fuente. El prompt que generes debe:
1. Explicar claramente los cambios a realizar en el backend (ej. qué archivos modificar como models.py, schemas.py, routers, etc.).
2. Indicar qué base de datos y endpoints nuevos o modificados se requieren, detallando sus rutas HTTP, métodos, parámetros y respuestas.
3. Mantener las convenciones existentes (Pydantic, SQLAlchemy, logging con logger, excepciones HTTPException, etc.).
```
