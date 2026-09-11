package com.blepulse.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blepulse.core.PulseState
import com.blepulse.core.ScanUiState
import com.blepulse.core.SettingsStore
import com.blepulse.scan.Permissions
import com.blepulse.scan.ScanService
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BLEPulseApp() }
    }
}

@Composable
private fun BLEPulseApp() {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) { RootScreen() }
    }
}

@Composable
private fun RootScreen() {
    val context = LocalContext.current
    val state by PulseState.state.collectAsStateWithLifecycle()

    var hasPermissions by remember { mutableStateOf(Permissions.canScan(context)) }
    var wasRefused by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermissions = Permissions.canScan(context)
        // A second denial is permanent: Android stops showing the dialog and the button
        // would silently do nothing from here on. Send them to Settings instead.
        if (!hasPermissions) wasRefused = true
    }

    // Re-check when the user comes back from the Settings screen. Doing this inline in
    // the composable body would be a state write during composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasPermissions = Permissions.canScan(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "BLEPulse",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Counts new Bluetooth devices nearby and flags the bursts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            if (!hasPermissions) {
                PermissionGate(
                    refused = wasRefused,
                    onGrant = { launcher.launch(Permissions.required()) },
                    onOpenSettings = { openAppSettings(context) },
                )
            } else {
                MonitorScreen(state = state, context = context)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PermissionGate(
    refused: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Three permissions needed", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            PermissionRow(
                "Nearby devices",
                "To listen for Bluetooth advertisements. BLEPulse never connects to anything.",
            )
            PermissionRow(
                "Location",
                "Android treats an unfiltered Bluetooth scan as location-capable and will " +
                    "return zero results without it. Your location is never read or stored.",
            )
            PermissionRow(
                "Notifications",
                "To show the ongoing scan and buzz you when a burst looks like a train.",
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Nothing leaves the phone. There is no network code in this app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                Text("Grant permissions")
            }
            if (refused) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Android will not ask again after a second refusal. Grant them in " +
                        "Settings instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open app settings")
                }
            }
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun PermissionRow(title: String, body: String) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MonitorScreen(state: ScanUiState, context: Context) {
    var showSettings by remember { mutableStateOf(false) }

    state.error?.let { message ->
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.currentCount.toString(),
            fontSize = 88.sp,
            fontWeight = FontWeight.Bold,
            color = if (state.lastAlertMs > 0 &&
                System.currentTimeMillis() - state.lastAlertMs < 60_000
            ) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Text(
            text = "new devices this " + state.config.binSeconds + "s bin",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(16.dp))
    PulseChart(bins = state.bins, baseline = state.baseline)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Last " + state.config.historyMinutes + " min. Dashed line is the usual rate; " +
            "red dots are bursts.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Stat("Active", state.activeUnique.toString(), "unique in " + state.config.windowMinutes + " min")
        Stat("Usual", format1(state.baseline), "new per bin")
        Stat("Ads", compact(state.totalAdvertisements), "seen total")
    }

    if (state.running && state.binsUntilWarm > 0) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Warming up: " + state.binsUntilWarm + " more bins before burst alerts arm.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(20.dp))
    if (state.running) {
        Button(
            onClick = { ScanService.stop(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stop scanning") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { ScanService.label(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Train now - mark this bin") }
    } else {
        Button(
            onClick = { ScanService.start(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start scanning") }
    }

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { shareCsv(context) },
            modifier = Modifier.weight(1f),
        ) { Text("Export CSV") }
        OutlinedButton(
            onClick = { showSettings = true },
            modifier = Modifier.weight(1f),
        ) { Text("Settings") }
    }

    state.csvPath?.let { path ->
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Logging to " + path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.scanRestarts > 0) {
        Text(
            text = "Scan auto-restarted " + state.scanRestarts + " time(s).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showSettings) {
        SettingsDialog(
            store = remember { SettingsStore(context) },
            onDismiss = { showSettings = false },
            onSaved = { ScanService.reloadConfig(context) },
        )
    }
}

@Composable
private fun Stat(label: String, value: String, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun compact(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private fun shareCsv(context: Context) {
    val dir: File = context.getExternalFilesDir(null) ?: run {
        Toast.makeText(context, "External storage unavailable.", Toast.LENGTH_SHORT).show()
        return
    }
    val files = dir.listFiles { f -> f.name.startsWith("blepulse-") && f.name.endsWith(".csv") }
        ?.sortedBy { it.name }
        .orEmpty()
    if (files.isEmpty()) {
        Toast.makeText(context, "No logs yet. Start scanning first.", Toast.LENGTH_SHORT).show()
        return
    }

    val uris = ArrayList(
        files.map {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it)
        },
    )

    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/csv"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Export BLEPulse logs"))
    }.onFailure {
        Toast.makeText(context, "No app available to receive the file.", Toast.LENGTH_SHORT).show()
    }
}
