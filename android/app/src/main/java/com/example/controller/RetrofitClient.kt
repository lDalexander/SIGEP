package com.example.controller

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    const val BASE_URL = "http://150.36.200.252:8000/"

    /**
     * Token de la sesion de turno. Lo escribe LoginActivity al entrar, lo restaura
     * SigepApplication al arrancar y lo borra el logout.
     *
     * Vive aqui y no se lee de SharedPreferences dentro del interceptor porque el
     * interceptor no tiene Context, y pasarselo obligaria a inicializar el cliente
     * HTTP desde la Application: mas piezas para el mismo efecto.
     *
     * @Volatile porque lo escribe el hilo de la UI y lo lee el de OkHttp.
     */
    @Volatile
    var tokenSesion: String? = null

    /**
     * Token del SUPERVISOR (tabla `administradores`), que es otra credencial distinta.
     *
     * Son dos porque los dos extremos viven en tablas distintas y el backend los valida
     * por separado: `X-User-Token` contra `sesiones_usuario` y `X-Admin-Token` contra el
     * almacén del admin. Una tablet nunca tiene los dos a la vez —o entra un insumista o
     * entra un supervisor— pero se mandan los que haya y el servidor coge el que entienda.
     *
     * OJO: este vive en la MEMORIA DEL SERVIDOR, así que un reinicio del backend lo
     * invalida y el supervisor tiene que volver a entrar. El del insumista no, porque
     * el suyo está en la base.
     */
    @Volatile
    var tokenAdmin: String? = null

    // Timeouts moderados: la red interna no debería tomar más de unos segundos.
    // Si no respondemos en este rango es porque algo se cayó y queremos abortar
    // antes de que la coroutine quede colgada indefinidamente.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Adjunta X-User-Token cuando hay sesion. El servidor lo PREFIERE sobre lo
            // que diga el cuerpo o la URL, asi que es lo que impide que un cliente se
            // haga pasar por otro insumista o mire la bandeja de otra bodega.
            .addInterceptor { cadena ->
                val constructor = cadena.request().newBuilder()
                tokenSesion?.takeIf { it.isNotBlank() }?.let { constructor.header("X-User-Token", it) }
                tokenAdmin?.takeIf { it.isNotBlank() }?.let { constructor.header("X-Admin-Token", it) }
                cadena.proceed(constructor.build())
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    })
                }
            }
            .build()
    }

    val apiService: ProduccionApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ProduccionApi::class.java)
    }
}
