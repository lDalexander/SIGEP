package com.example.controller

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.PUT
import retrofit2.http.DELETE
import retrofit2.http.Query
interface ProduccionApi {

    @POST("api/iniciar_turno")
    suspend fun iniciarTurno(@Body datos: IniciarTurnoRequest): Response<TurnoResponse>

    @POST("api/registrar_pallet")
    suspend fun registrarPallet(@Body datos: RegistrarPalletRequest): Response<RegistrarPalletResponse>

    @POST("api/finalizar_turno")
    suspend fun finalizarTurno(@Body datos: FinalizarTurnoRequest): Response<RespuestaGenerica>

    // CAMBIO 5: comentarios del turno y reportes de problemas con la app.
    @POST("api/comentarios_turno")
    suspend fun enviarComentarioTurno(@Body datos: ComentarioTurnoRequest): Response<RespuestaGenerica>

    @POST("api/reportes_app")
    suspend fun enviarReporteApp(@Body datos: ReporteAppRequest): Response<RespuestaGenerica>

    // Devuelve {paro_id, mensaje}: el id es necesario para adjuntarle la foto después.
    @POST("/api/paro/iniciar")
    suspend fun iniciarParo(@Body request: IniciarParoRequest): Response<IniciarParoResponse>

    // 200 con ya_cerrado=true si el paro ya lo había cerrado un supervisor desde la web.
    @POST("/api/paro/finalizar")
    suspend fun finalizarParo(@Body request: FinalizarParoRequest): Response<FinalizarParoResponse>

    // Estado real del paro de una sesión. Solo lectura: no crea, no cierra, no modifica.
    // Es la fuente de verdad para pintar el botón de paro (ver EstadoParoResponse).
    // 404 = la sesión no existe (borrada desde /admin) → hay que iniciar turno nuevo.
    @GET("api/paro/estado")
    suspend fun obtenerEstadoParo(@Query("sesion_id") sesionId: Int): Response<EstadoParoResponse>

    // Evidencia fotográfica OPCIONAL del paro. JPG/PNG, máximo 5 MB (más → 400).
    // Repetible: vuelve a llamar y sustituye la anterior. Va en ruta aparte y no dentro
    // de /paro/iniciar a propósito: iniciar recibe JSON y pasarlo a multipart rompería
    // a todas las tablets ya instaladas en planta.
    @Multipart
    @POST("api/paro/{paro_id}/foto")
    suspend fun subirFotoParo(
        @Path("paro_id") paroId: Int,
        @Part foto: MultipartBody.Part
    ): Response<FotoParoResponse>

    // Velocidad de máquina en fundas/min. Idempotente por request_id (duplicado=true
    // en la respuesta cuenta como éxito). 400 si está fuera de 0.1–1000 o el turno ya
    // se cerró; 404 si la sesión no existe.
    @POST("api/velocidad")
    suspend fun registrarVelocidad(@Body request: VelocidadRequest): Response<VelocidadResponse>

    @POST("/api/insumos/pedido_dinamico")
    suspend fun solicitarInsumo(@Body request: SolicitarInsumoRequest): Response<RespuestaGenerica>

    @POST("/api/login")
    suspend fun iniciarSesion(@Body request: LoginRequest): Response<UsuarioResponse>

    @POST("/api/logout")
    suspend fun cerrarSesionUsuario(@Body request: LogoutRequest): Response<RespuestaGenerica>

    // tipo opcional ("SOLIDO"/"LIQUIDO"): filtra los operarios por línea. Si es null
    // Retrofit omite el query param y el server devuelve todos (contrato retro-compatible).
    @GET("api/operadores")
    suspend fun obtenerOperadores(@Query("tipo") tipo: String? = null): Response<List<OperadorResponse>>

    @GET("api/maquinas")
    suspend fun obtenerMaquinas(): Response<List<MaquinaResponse>>

    // Catálogo de productos de una línea ("SOLIDO" / "LIQUIDO"). En LÍQUIDO el operario
    // elige uno al iniciar turno y eso filtra marca y presentación; en SÓLIDO hay uno
    // solo. Vive en la BD para que añadir un producto no obligue a reinstalar el APK en
    // toda la planta. Si devuelve vacío, la app cae a su fallback en fuente.
    @GET("api/productos")
    suspend fun obtenerProductos(@Query("tipo") tipo: String): Response<List<String>>

    // Fragancias disponibles para una combinación máquina+marca concretas. El server
    // NUNCA devuelve lista vacía: si la combinación no está configurada devuelve el
    // catálogo completo, para que una configuración incompleta en la web no deje a
    // nadie sin poder iniciar turno. Retrofit codifica las tildes de los parámetros.
    @GET("api/fragancias")
    suspend fun obtenerFragancias(
        @Query("maquina") maquina: String,
        @Query("marca") marca: String
    ): Response<List<String>>

    @GET("/api/sesion/{sesion_id}/historial")
    suspend fun obtenerHistorialSesion(@Path("sesion_id") sesionId: Int): Response<List<HistorialPallet>>

    @GET("/api/sesion/{sesion_id}/insumos_permitidos")
    suspend fun obtenerInsumosPermitidos(@Path("sesion_id") sesionId: Int): Response<List<InsumoResponse>>

    @GET("/api/sesion/{sesion_id}/estado_pedidos")
    suspend fun obtenerEstadoPedidos(@Path("sesion_id") sesionId: Int): Response<EstadoPedidosResponse>

    @GET("/api/usuarios")
    suspend fun obtenerUsuarios(): Response<List<ListaUsuariosResponse>>

    @GET("/api/admin/supervisores")
    suspend fun obtenerSupervisores(): Response<List<ListaUsuariosResponse>>

    @POST("/api/admin/login")
    suspend fun loginAdministrador(@Body request: AdminLoginRequest): Response<AdminLoginResponse>

// ==========================================
    // NUEVO: FASE 4 - RADAR UBER (INSUMISTAS)
    // ==========================================

    @GET("/api/insumos/pendientes/{rol}")
    suspend fun obtenerPedidosPendientes(@Path("rol") rol: String): Response<List<PedidoPendienteResponse>>

    // 👇 SOLO DEBE EXISTIR ESTA VERSIÓN CON PUT
    @PUT("/api/insumos/aceptar")
    suspend fun aceptarPedido(@Body request: PedidoAccionRequest): Response<RespuestaGenerica>

    // 👇 SOLO DEBE EXISTIR ESTA VERSIÓN CON PUT
    @PUT("/api/insumos/entregar")
    suspend fun entregarPedido(@Body request: PedidoAccionRequest): Response<RespuestaGenerica>

    @GET("/api/insumos/asignados/{insumista_id}")
    suspend fun obtenerPedidosAsignados(@Path("insumista_id") insumistaId: Int): Response<List<PedidoPendienteResponse>>

    @GET("/api/sesion/{sesion_id}/detalle_pedidos")
    suspend fun obtenerDetallePedidos(@Path("sesion_id") sesionId: Int): Response<List<DetallePedidoOperador>>

    @PUT("/api/insumos/confirmar_recepcion")
    suspend fun confirmarRecepcion(@Body request: ConfirmarRecepcionRequest): Response<RespuestaGenerica>

    @GET("/api/insumos/pendientes_confirmacion/{sesion_id}")
    suspend fun obtenerPendientesConfirmacion(@Path("sesion_id") sesionId: Int): Response<List<PedidoPendienteResponse>>

    @Multipart
    @POST("/api/insumos/entrega_proactiva")
    suspend fun registrarEntregaProactiva(
        @Part("insumista_id") insumistaId: RequestBody,
        @Part("tipo_producto") tipoProducto: RequestBody,
        @Part("insumo")        insumo: RequestBody,
        @Part("cantidad")      cantidad: RequestBody,
        @Part("maquina")       maquina: RequestBody,
        @Part("observaciones") observaciones: RequestBody?,
        // v1.7.0: ata la entrega al catálogo y la hace idempotente. Opcionales en el
        // servidor, así que una tablet anterior sigue registrando igual.
        @Part("insumo_id")     insumoId: RequestBody?,
        @Part("request_id")    requestId: RequestBody?,
        // v1.7.0: plan de producción al que se destina el material. Es lo que permite
        // dejar insumos preparados para producción que aún no tiene máquina asignada.
        @Part("plan_id")       planId: RequestBody?,
        @Part foto: MultipartBody.Part?
    ): Response<EntregaProactivaResponse>

    /**
     * Catálogo de insumos de la bodega del insumista (v1.7.0).
     *
     * El operario filtra por el BOM de su turno; el insumista no tiene turno contra el
     * que filtrar, así que necesita el catálogo entero de su bodega. Con token, el
     * servidor deduce la bodega y el parámetro sobra.
     */
    @GET("/api/insumos/catalogo")
    suspend fun obtenerCatalogoInsumos(@Query("bodega") bodega: String? = null): Response<List<InsumoResponse>>

    @GET("/api/insumos/entregas_proactivas/{insumista_id}")
    suspend fun obtenerEntregasProactivas(@Path("insumista_id") insumistaId: Int): Response<List<EntregaProactivaHistorial>>

    // ==========================================
    // NUEVO: FASE 5 - TORRE DE CONTROL (SUPERVISORES)
    // ==========================================
    @GET("/api/supervisor/planta/estado_realtime")
    suspend fun obtenerEstadoPlanta(): Response<List<EstadoMaquinaRealTime>>

    // ── Torre de control v1.7.0 ──────────────────────────────────────────────

    /** Quién está de turno en bodega ahora mismo. */
    @GET("/api/supervisor/insumistas")
    suspend fun obtenerInsumistasDeTurno(): Response<List<InsumistaTurno>>

    /** Todo lo de un turno en una sola llamada: es la ventana emergente del panel. */
    @GET("/api/supervisor/sesion/{id}/detalle")
    suspend fun obtenerDetalleSesion(@Path("id") sesionId: Int): Response<DetalleSesion>

    /** Cierra un pedido que el operario no estaba para confirmar. Exige X-Admin-Token. */
    @PUT("/api/supervisor/pedidos/{id}/confirmar")
    suspend fun confirmarComoSupervisor(
        @Path("id") pedidoId: Int,
        @Body request: ConfirmarSupervisorRequest
    ): Response<RespuestaGenerica>

    @GET("/api/supervisor/entregas_pendientes")
    suspend fun obtenerEntregasPorVerificar(): Response<List<EntregaPorVerificar>>

    @PUT("/api/supervisor/entregas/{id}/verificar")
    suspend fun verificarEntrega(
        @Path("id") entregaId: Int,
        @Body request: VerificarEntregaRequest
    ): Response<RespuestaGenerica>

    // ── Chat de bodega v1.7.0 ────────────────────────────────────────────────
    // El emisor lo deduce el servidor del token que viaje en la cabecera, así que el
    // mismo par de métodos sirve al supervisor y al insumista.

    @GET("/api/mensajes/bodega")
    suspend fun leerChatBodega(@Query("insumista_id") insumistaId: Int? = null): Response<ConversacionBodega>

    @POST("/api/mensajes/bodega")
    suspend fun enviarMensajeBodega(@Body request: EnviarMensajeRequest): Response<RespuestaGenerica>

    @POST("/api/mensajes/bodega/leidos")
    suspend fun marcarChatLeido(): Response<RespuestaGenerica>

    @GET("/api/mensajes/bodega/resumen")
    suspend fun resumenChatBodega(): Response<ResumenChat>

    // ── Planificación v1.7.0 ─────────────────────────────────────────────────

    /**
     * Marca urgente un insumo del plan. Exige X-Admin-Token.
     *
     * Va por `/supervisor/` y no por `/planificacion/` por los permisos: aquel exige
     * nivel de planta, y priorizar material es tanto cosa de bodega como de planta.
     */
    @PUT("/api/supervisor/plan_insumos/{id}/prioridad")
    suspend fun marcarPrioridadInsumo(
        @Path("id") itemId: Int,
        @Body request: PrioridadRequest
    ): Response<RespuestaGenerica>

    @GET("/api/planificacion/planes")
    suspend fun obtenerPlanes(
        @Query("desde") desde: String? = null,
        @Query("hasta") hasta: String? = null
    ): Response<PlanesResponse>

    @GET("/api/supervisor/bodega/historial_pedidos")
    suspend fun obtenerHistorialPedidosSupervisor(): Response<List<HistorialPedidoSupervisor>>

    @GET("api/config/version")
    suspend fun checkVersion(): Response<VersionResponse>

    // Registra el token FCM del dispositivo para que el servidor pueda enviar
    // notificaciones dirigidas (no broadcast). Implementado en el backend.
    @POST("api/dispositivos/token")
    suspend fun registrarTokenFcm(@Body solicitud: SolicitudTokenFcm): Response<RespuestaTokenFcm>

    @POST("api/tablets/heartbeat")
    suspend fun enviarHeartbeat(@Body req: HeartbeatRequest): Response<HeartbeatResponse>

    // ACK de un mensaje admin: la tablet confirma que lo mostró (CAMBIO 4).
    @POST("api/tablets/mensajes/{id}/leido")
    suspend fun marcarMensajeLeido(
        @Path("id") id: Int,
        @Query("device_id") deviceId: String,
    ): Response<RespuestaGenerica>

    // ==========================================
    // CHECKLIST DE MANTENIMIENTO Y LIMPIEZA (ENTRADA / SALIDA)
    // Idempotente por request_id: un 409/duplicado se trata como éxito en el Worker.
    // ==========================================
    @POST("api/mantenimiento/checklist")
    suspend fun responderMantenimiento(@Body req: MantenimientoChecklistRequest): Response<MantenimientoChecklistResponse>

}