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
                val token = tokenSesion
                val peticion = if (token.isNullOrBlank()) {
                    cadena.request()
                } else {
                    cadena.request().newBuilder()
                        .header("X-User-Token", token)
                        .build()
                }
                cadena.proceed(peticion)
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
