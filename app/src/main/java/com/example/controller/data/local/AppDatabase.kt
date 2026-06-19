package com.example.controller.data.local

import android.content.Context

class AppDatabase private constructor(context: Context) {

    private val dao = OperacionPendienteDao(context)
    private val mantenimientoDao = MantenimientoChecklistDao(context)

    fun operacionDao() = dao

    fun mantenimientoDao() = mantenimientoDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppDatabase(context.applicationContext).also { INSTANCE = it }
            }
    }
}
