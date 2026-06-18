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

    @POST("/api/paro/iniciar")
    suspend fun iniciarParo(@Body request: IniciarParoRequest): Response<Void>

    @POST("/api/paro/finalizar")
    suspend fun finalizarParo(@Body request: FinalizarParoRequest): Response<Void>

    @POST("/api/insumos/pedido_dinamico")
    suspend fun solicitarInsumo(@Body request: SolicitarInsumoRequest): Response<RespuestaGenerica>

    @POST("/api/login")
    suspend fun iniciarSesion(@Body request: LoginRequest): Response<UsuarioResponse>

    @POST("/api/logout")
    suspend fun cerrarSesionUsuario(@Body request: LogoutRequest): Response<RespuestaGenerica>

    @GET("api/operadores")
    suspend fun obtenerOperadores(): Response<List<OperadorResponse>>

    @GET("api/maquinas")
    suspend fun obtenerMaquinas(): Response<List<MaquinaResponse>>

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
        @Part foto: MultipartBody.Part?
    ): Response<EntregaProactivaResponse>

    @GET("/api/insumos/entregas_proactivas/{insumista_id}")
    suspend fun obtenerEntregasProactivas(@Path("insumista_id") insumistaId: Int): Response<List<EntregaProactivaHistorial>>

    // ==========================================
    // NUEVO: FASE 5 - TORRE DE CONTROL (SUPERVISORES)
    // ==========================================
    @GET("/api/supervisor/planta/estado_realtime")
    suspend fun obtenerEstadoPlanta(): Response<List<EstadoMaquinaRealTime>>

    @GET("/api/supervisor/bodega/historial_pedidos")
    suspend fun obtenerHistorialPedidosSupervisor(): Response<List<HistorialPedidoSupervisor>>

    @GET("api/config/version")
    suspend fun checkVersion(): Response<VersionResponse>

    // Endpoint pendiente de implementar en el backend FastAPI.
    // Necesario para que el servidor pueda enviar FCM a dispositivos específicos en lugar de broadcast.
    @POST("api/dispositivos/token")
    suspend fun registrarTokenFcm(@Body solicitud: SolicitudTokenFcm): Response<RespuestaTokenFcm>

    @POST("api/tablets/heartbeat")
    suspend fun enviarHeartbeat(@Body req: HeartbeatRequest): Response<HeartbeatResponse>

}