package com.example.controller

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SupervisorDashboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Conectamos este "cerebro" con el diseño de la torre de control
        setContentView(R.layout.activity_supervisor_dashboard)
    }
}