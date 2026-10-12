package com.example.controller

/**
 * Singleton para gestionar el estado global de la aplicación y el contexto del usuario.
 * Ayuda a decidir si una notificación crítica debe sonar o no.
 */
object AppState {
    var usuarioRol: String? = null
    var moduloActivo: String? = null
    
    // Constantes de identificación
    const val MODULO_GESTION_INSUMOS = "BandejaInsumosActivity"

    /**
     * Valida si el dispositivo actual debe disparar la alarma industrial.
     */
    fun debeDispararAlarma(): Boolean {
        // Solo suena si el rol contiene "insumista" (para cubrir Empaque y Granel) 
        // Y el usuario está dentro del módulo específico
        val esRolCorrecto = usuarioRol?.lowercase()?.contains("insumista") ?: false
        return esRolCorrecto && moduloActivo == MODULO_GESTION_INSUMOS
    }
}