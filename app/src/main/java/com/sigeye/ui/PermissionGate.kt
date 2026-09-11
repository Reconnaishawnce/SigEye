package com.sigeye.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** One line of the "why this permission" explanation. */
data class PermissionReason(val title: String, val body: String)

/**
 * Shared permission wall. Each experiment declares its own permissions and its own
 * reasons, so the user is only ever asked for what the thing in front of them needs.
 *
 * [content] is shown once every permission in [blocking] is granted.
 */
@Composable
fun PermissionGate(
    request: Array<String>,
    blocking: Array<String>,
    reasons: List<PermissionReason>,
    footnote: String,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    fun satisfied(): Boolean = blocking.all { isGranted(context, it) }

    var granted by remember { mutableStateOf(satisfied()) }
    var refused by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = satisfied()
        // Android stops showing the dialog after a second refusal, so from then on the
        // button would silently do nothing. Offer Settings instead.
        if (!granted) refused = true
    }

    // Re-check on resume, to catch a grant made over in the Settings app.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = satisfied()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    if (granted) {
        content()
        return
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = if (reasons.size == 1) "One permission needed" else
                    "${reasons.size} permissions needed",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            reasons.forEach { reason ->
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text(reason.title, style = MaterialTheme.typography.labelLarge)
                    Text(
                        reason.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                footnote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(request) }, modifier = Modifier.fillMaxWidth()) {
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
                OutlinedButton(
                    onClick = { openAppSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open app settings") }
            }
        }
    }
}

private fun isGranted(context: Context, permission: String): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
