package com.example.controller.data.local

data class OperacionPendiente(
    val id: Long = 0,
    val tipo: String,
    val payloadJson: String,
    val requestId: String,
    val creadoEn: Long,
    var intentos: Int = 0,
    var ultimoError: String? = null,
    var fatal: Boolean = false,
)
