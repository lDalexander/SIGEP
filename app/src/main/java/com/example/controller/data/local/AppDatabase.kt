package com.example.controller.data.local

import android.content.Context

class AppDatabase private constructor(context: Context) {

    private val dao = OperacionPendienteDao(context)
    private val checklistDao = ChecklistLocalDao(context)

    fun operacionDao() = dao

    fun checklistDao() = checklistDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppDatabase(context.applicationContext).also { INSTANCE = it }
            }
    }
}
