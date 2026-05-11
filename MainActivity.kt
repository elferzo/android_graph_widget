package com.example.graphwidget

import android.app.AlarmManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val healthPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class)
    )

    private val requestHealthPermissions = registerForActivityResult(
        HealthConnectClient.createRequestPermissionResultContract()
    ) {
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Exact alarm ───────────────────────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                return
            }
        }

        // ── Health Connect разрешения ─────────────────────────────────────
        val client = try {
            HealthConnectClient.getOrCreate(this)
        } catch (e: Exception) {
            finish()
            return
        }

        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(healthPermissions)) {
                requestHealthPermissions.launch(healthPermissions)
            } else {
                finish()
            }
        }
    }
}
