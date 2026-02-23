package net.darqlab.healthsync

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.darqlab.healthsync.ui.theme.HealthSyncTheme

class MainActivity : ComponentActivity() {

    private lateinit var healthManager: HealthConnectManager

    private val healthConnectAvailable = mutableStateOf(false)
    private val permissionsGranted = mutableStateOf(false)
    private val syncScheduled = mutableStateOf(false)
    private val statusMessage = mutableStateOf("Initializing...")
    private val logEntries = mutableStateListOf<LogEntry>()

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        lifecycleScope.launch {
            if (healthManager.hasPermissions()) {
                permissionsGranted.value = true
                startSync()
            } else {
                statusMessage.value = "Permissions denied - use button below to grant manually"
            }
        }
    }

    private fun startSync() {
        HealthSyncWorker.schedule(this)
        syncScheduled.value = true
        statusMessage.value = "Sync scheduled (every 1 hour)"
    }

    private fun checkPermissionsAndSync() {
        lifecycleScope.launch {
            if (healthManager.hasPermissions()) {
                permissionsGranted.value = true
                startSync()
            } else {
                permissionsGranted.value = false
                statusMessage.value = "Permissions still not granted"
            }
        }
        refreshLog()
    }

    private fun syncNow() {
        HealthSyncWorker.syncNow(this)
        statusMessage.value = "Sync triggered..."
    }

    private fun refreshLog() {
        logEntries.clear()
        logEntries.addAll(SyncLog.getAll(this))
    }

    private fun clearLog() {
        SyncLog.clear(this)
        logEntries.clear()
    }

    private fun openHealthConnectSettings() {
        val intent = Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS")
            .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            val fallback = packageManager.getLaunchIntentForPackage("com.google.android.apps.healthdata")
            if (fallback != null) {
                startActivity(fallback)
            } else {
                Toast.makeText(this, "Cannot open Health Connect", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!HealthConnectManager.isAvailable(this)) {
            statusMessage.value = "Health Connect is not available on this device"
        } else {
            healthConnectAvailable.value = true
            healthManager = HealthConnectManager(this)
            lifecycleScope.launch {
                if (healthManager.hasPermissions()) {
                    permissionsGranted.value = true
                    startSync()
                } else {
                    statusMessage.value = "Requesting permissions..."
                    permissionLauncher.launch(HealthConnectManager.PERMISSIONS)
                }
            }
        }

        refreshLog()

        setContent {
            HealthSyncTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    StatusScreen(
                        healthConnectAvailable = healthConnectAvailable.value,
                        permissionsGranted = permissionsGranted.value,
                        syncScheduled = syncScheduled.value,
                        statusMessage = statusMessage.value,
                        logEntries = logEntries,
                        onOpenSettings = ::openHealthConnectSettings,
                        onRefresh = ::checkPermissionsAndSync,
                        onSyncNow = ::syncNow,
                        onRefreshLog = ::refreshLog,
                        onClearLog = ::clearLog,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (healthConnectAvailable.value && ::healthManager.isInitialized) {
            checkPermissionsAndSync()
        }
        refreshLog()
    }
}

@Composable
fun StatusScreen(
    healthConnectAvailable: Boolean,
    permissionsGranted: Boolean,
    syncScheduled: Boolean,
    statusMessage: String,
    logEntries: List<LogEntry>,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onSyncNow: () -> Unit,
    onRefreshLog: () -> Unit,
    onClearLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text(text = "HealthSync", fontSize = 24.sp)
        Spacer(modifier = Modifier.height(16.dp))
        StatusLine("Health Connect", healthConnectAvailable)
        StatusLine("Permissions", permissionsGranted)
        StatusLine("Sync scheduled", syncScheduled)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = statusMessage, fontSize = 14.sp, color = Color.Gray)

        if (healthConnectAvailable && !permissionsGranted) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open Health Connect Settings")
            }
        }

        if (permissionsGranted) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onSyncNow, modifier = Modifier.fillMaxWidth()) {
                Text("Sync Now")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        Row {
            Text(text = "Sync Log", fontSize = 18.sp)
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRefreshLog) {
                Text("Refresh", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onClearLog) {
                Text("Clear", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (logEntries.isEmpty()) {
            Text(text = "No sync logs yet", fontSize = 13.sp, color = Color.Gray)
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(logEntries) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

@Composable
fun LogEntryRow(entry: LogEntry) {
    val color = if (entry.isError) Color(0xFFE53935) else Color(0xFF757575)
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "[${entry.timestamp}] ${entry.message}",
            fontSize = 12.sp,
            color = color,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp
        )
    }
}

@Composable
fun StatusLine(label: String, ok: Boolean) {
    val indicator = if (ok) "OK" else "--"
    val color = if (ok) Color(0xFF4CAF50) else Color.Gray
    Text(
        text = "$label: $indicator",
        fontSize = 16.sp,
        color = color,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@Preview(showBackground = true)
@Composable
fun StatusScreenPreview() {
    HealthSyncTheme {
        StatusScreen(
            healthConnectAvailable = true,
            permissionsGranted = true,
            syncScheduled = true,
            statusMessage = "Sync scheduled (every 1 hour)",
            logEntries = listOf(
                LogEntry("12:00:01", "Sync started"),
                LogEntry("12:00:02", "Data read - steps: 1234, hr: 72.0, sleep: 7.5h, dist: 5000m, cal: 2100"),
                LogEntry("12:00:02", "Sending to https://healthsync.darqlab.net/health/hook"),
                LogEntry("12:00:03", "Sync OK - server responded 200"),
                LogEntry("11:00:03", "FAILED: DNS error - cannot resolve host. Check URL or internet connection", isError = true),
            ),
            onOpenSettings = {},
            onRefresh = {},
            onSyncNow = {},
            onRefreshLog = {},
            onClearLog = {}
        )
    }
}
