package com.example.controller

import android.util.Log
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Único punto para reportar fallas. Antes teníamos 30+ `catch(e: Exception){}`
 * silenciosos repartidos: cuando un bug pasaba en una tablet, no quedaba rastro.
 *
 * - Loguea siempre en logcat para debug local.
 * - Reporta a Crashlytics como non-fatal cuando es algo que SÍ debería verse
 *   (parse errors, NPE, IllegalState...).
 * - NO reporta fallas de red transitorias (IOException, timeouts, 5xx)
 *   para no inundar la cuota de Crashlytics con cosas esperables.
 */
object ErrorReporter {

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
    }

    fun report(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        if (throwable == null) {
            Firebase.crashlytics.log("$tag: $message")
            return
        }
        if (esRedTransitoria(throwable)) {
            Log.w(tag, "Red caída (no se reporta): ${throwable.message}")
            return
        }
        Firebase.crashlytics.log("$tag: $message")
        Firebase.crashlytics.recordException(throwable)
    }

    fun setDeviceId(deviceId: String) {
        Firebase.crashlytics.setUserId(deviceId)
        Firebase.crashlytics.setCustomKey("device_id", deviceId)
    }

    fun setRol(rol: String?) {
        Firebase.crashlytics.setCustomKey("rol", rol ?: "desconocido")
    }

    private fun esRedTransitoria(t: Throwable): Boolean =
        t is IOException ||
        t is SocketException ||
        t is SocketTimeoutException ||
        t is UnknownHostException ||
        (t is HttpException && t.code() in 500..599)
}
