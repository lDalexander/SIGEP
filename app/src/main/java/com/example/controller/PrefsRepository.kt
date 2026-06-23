package com.example.controller

import android.content.Context
import java.util.UUID

class PrefsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("DetcuadorPrefs", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString("tablet_device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("tablet_device_id", it).apply()
        }

    var tabletNombre: String?
        get() = prefs.getString("tablet_nombre", null)
        set(v) { prefs.edit().putString("tablet_nombre", v).apply() }

    var tabletMaquina: String?
        get() = prefs.getString("tablet_maquina", null)
        set(v) { prefs.edit().putString("tablet_maquina", v).apply() }
}
