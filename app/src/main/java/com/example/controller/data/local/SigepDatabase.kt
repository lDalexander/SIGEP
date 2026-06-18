package com.example.controller.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SigepDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "sigep_db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        crearOperacionesPendientes(db)
    }

    // IMPORTANTE: la migración es ADITIVA para operaciones_pendientes. NO se borra
    // jamás, de lo contrario perderíamos turnos/pacas encolados sin sincronizar.
    // En la v3 se eliminan las tablas del checklist dinámico viejo (ya retirado).
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS checklist_completados")
            db.execSQL("DROP TABLE IF EXISTS checklist_plantilla_cache")
        }
    }

    private fun crearOperacionesPendientes(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS operaciones_pendientes (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                tipo        TEXT    NOT NULL,
                payloadJson TEXT    NOT NULL,
                requestId   TEXT    NOT NULL,
                creadoEn    INTEGER NOT NULL,
                intentos    INTEGER NOT NULL DEFAULT 0,
                ultimoError TEXT,
                fatal       INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }
}
