package com.example.controller.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SigepDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "sigep_db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        crearOperacionesPendientes(db)
        crearTablasChecklist(db)
    }

    // IMPORTANTE: la migración es ADITIVA. NO se borra operaciones_pendientes,
    // de lo contrario perderíamos turnos/pacas encolados sin sincronizar.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            crearTablasChecklist(db)
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

    private fun crearTablasChecklist(db: SQLiteDatabase) {
        // Caché de la plantilla activa para uso offline (siempre un solo registro id=1).
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS checklist_plantilla_cache (
                id         INTEGER PRIMARY KEY,
                json       TEXT    NOT NULL,
                guardadaEn INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // Guard offline: 1 checklist por (maquina, operador, fecha_turno, codigo_turno).
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS checklist_completados (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                maquina     TEXT    NOT NULL,
                operador    TEXT    NOT NULL,
                fechaTurno  TEXT    NOT NULL,
                codigoTurno TEXT    NOT NULL,
                creadoEn    INTEGER NOT NULL,
                UNIQUE(maquina, operador, fechaTurno, codigoTurno)
            )
            """.trimIndent()
        )
    }
}
