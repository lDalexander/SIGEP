package com.example.controller

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.PUT
interface ProduccionApi {

    @POST("api/iniciar_turno")
    suspend fun iniciarTurno(@Body datos: IniciarTurnoRequest): Response<TurnoResponse>

    @POST("api/registrar_pallet")
    suspend fun registrarPallet(@Body datos: RegistrarPalletRequest): Response<RespuestaGenerica>

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

    @GET("api/operadores")
    suspend fun obtenerOperadores(): Response<List<OperadorResponse>>

    @GET("api/maquinas")
    suspend fun obtenerMaquinas(): Response<List<MaquinaResponse>>

    @GET("/api/sesion/{sesion_id}/historial")
    suspend fun obtenerHistorialSesion(@Path("sesion_id") sesionId: Int): Response<List<HistorialPallet>>

    @GET("/api/sesion/{sesion_id}/insumos_permitidos")
    suspend fun obtenerInsumosPermitidos(@Path("sesion_id") sesionId: Int): Response<List<InsumoResponse>>

    @GET("/api/usuarios")
    suspend fun obtenerUsuarios(): Response<List<ListaUsuariosResponse>>

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
}