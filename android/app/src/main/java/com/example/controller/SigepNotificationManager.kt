package com.example.controller

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Gestor centralizado de notificaciones para el sistema SIGEP.
 */
object SigepNotificationManager {

    const val CHANNEL_ID_ALARM = "sigep_insumos_v2"

    // ID fijo para que la misma notificación se pueda cancelar al aceptar el pedido.
    // Si llega un FCM mientras hay otra alarma activa, el ID compartido hace que
    // Android actualice la existente en vez de apilar dos alarmas sonando.
    private const val NOTIF_ID_PEDIDO_INSUMO = 1001

    /** Canal del chat. Separado del de alarmas para que se puedan silenciar aparte. */
    const val CHANNEL_ID_MENSAJES = "sigep_mensajes_bodega"
    private const val NOTIF_ID_MENSAJE_BODEGA = 1002

    /**
     * Aviso de un mensaje de supervisión: notificación normal, sin alarma.
     *
     * La alarma industrial está reservada para un pedido sin atender, que para la
     * línea. Si un mensaje sonara igual, en dos días nadie distinguiría cuál de las
     * dos cosas está pasando y se acabarían ignorando las dos.
     */
    fun mostrarMensajeBodega(context: Context, titulo: String, cuerpo: String) {
        val gestor = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            gestor.getNotificationChannel(CHANNEL_ID_MENSAJES) == null
        ) {
            gestor.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_MENSAJES,
                    "Mensajes de supervisión",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Chat entre bodega y supervisión" }
            )
        }

        val intent = Intent(context, BandejaInsumosActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Tocar el aviso lleva al chat, no solo a la bandeja.
            putExtra(BandejaInsumosActivity.EXTRA_ABRIR_CHAT, true)
        }
        val pendiente = PendingIntent.getActivity(
            context, 2, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val aviso = NotificationCompat.Builder(context, CHANNEL_ID_MENSAJES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(titulo)
            .setContentText(cuerpo)
            // El texto largo se corta en una línea si no se expande, y un mensaje
            // cortado obliga a abrir la app para saber si era importante.
            .setStyle(NotificationCompat.BigTextStyle().bigText(cuerpo))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendiente)
            .setAutoCancel(true)
            .build()

        try {
            gestor.notify(NOTIF_ID_MENSAJE_BODEGA, aviso)
        } catch (e: SecurityException) {
            // Sin permiso de notificaciones (Android 13+) no se puede avisar, pero el
            // mensaje ya está guardado y se ve al abrir el chat.
            ErrorReporter.warn("SIGEP_NOTIF", "Sin permiso para notificar: ${e.message}")
        }
    }

    fun createAlarmNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Verificar si el canal ya existe
            val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID_ALARM)
            if (existingChannel != null) return

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID_ALARM,
                "Alertas de Insumos Críticas",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de emergencia para solicitudes de insumos"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Dispara la alerta de pedido (notificación heads-up + intento de full-screen
     * a AlertaPedidoActivity para encender pantalla y reproducir alarma).
     *
     * Único punto desde donde sale esta alerta — antes lo hacía solo
     * SigepFirebaseMessagingService, ahora también lo usa el botón de test
     * en Ajustes para verificar el flujo sin depender del servidor.
     */
    @SuppressLint("MissingPermission")
    fun dispararAlertaPedido(context: Context, titulo: String, cuerpo: String) {
        createAlarmNotificationChannel(context)

        val intent = Intent(context, AlertaPedidoActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificacion = NotificationCompat.Builder(context, CHANNEL_ID_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(titulo)
            .setContentText(cuerpo)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIF_ID_PEDIDO_INSUMO, notificacion)
    }

    /**
     * Cancela la alerta de pedido activa. Necesario porque el canal tiene su
     * propio sonido de alarma que sigue reproduciéndose mientras la
     * notificación está visible, aunque AlertaPedidoActivity ya haya
     * detenido su MediaPlayer.
     */
    fun cancelarAlertaPedido(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_PEDIDO_INSUMO)
    }
}
