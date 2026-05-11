package com.example.graphwidget

import android.app.AlarmManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class)
    )

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        Toast.makeText(this, "Granted: $granted", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Exact alarm ───────────────────────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                finish()
                return
            }
        }

        // ── Проверка статуса Health Connect ───────────────────────────────
        val status = HealthConnectClient.getSdkStatus(this)
        Toast.makeText(this, "HC status: $status", Toast.LENGTH_LONG).show()

        if (status == HealthConnectClient.SDK_UNAVAILABLE) {
            Toast.makeText(this, "Health Connect недоступен", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // ── Запрос разрешений ─────────────────────────────────────────────
        try {
            val client = HealthConnectClient.getOrCreate(this)
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val granted = client.permissionController.getGrantedPermissions()
                    Toast.makeText(this@MainActivity, "Already granted: $granted", Toast.LENGTH_LONG).show()
                    if (!granted.containsAll(permissions)) {
                        requestPermissions.launch(permissions)
                    } else {
                        finish()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Init error: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
