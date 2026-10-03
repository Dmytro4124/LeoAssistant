package com.dmytro.leo.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dmytro.leo.accessibility.AutoSendService

/** Permissions for calls and messages; requested together, from the UI (tools run in the background). */
private val COMM_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.CALL_PHONE,
    Manifest.permission.SEND_SMS,
    Manifest.permission.READ_PHONE_NUMBERS,
)

/** Shown only while something is missing: permissions, or the auto-send accessibility service. */
@Composable
fun SetupBanners() {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(context.missingPermissions()) }
    var autoSendOn by remember { mutableStateOf(context.isAutoSendEnabled()) }
    var autoSendDismissed by rememberSaveable { mutableStateOf(false) }

    // Re-check when coming back from the permission dialog or Settings
    LifecycleResumeEffect(Unit) {
        missing = context.missingPermissions()
        autoSendOn = context.isAutoSendEnabled()
        onPauseOrDispose { }
    }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = context.missingPermissions()
    }

    Column {
        if (missing.isNotEmpty()) {
            Banner(
                text = "Щоб дзвонити й надсилати повідомлення, Лео потрібен доступ до контактів, дзвінків і SMS.",
                action = "Надати",
                onAction = { request.launch(missing.toTypedArray()) },
            )
        }
        if (!autoSendOn && !autoSendDismissed) {
            Banner(
                text = "Щоб Лео сам надсилав повідомлення в месенджерах, увімкни «Лео: надсилання повідомлень» у спеціальних можливостях. Плаваючу кнопку-ярлик краще вимкнути: її торкання вимикає сервіс.",
                action = "Увімкнути",
                onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onDismiss = { autoSendDismissed = true },
            )
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit, onDismiss: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Пізніше") }
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

private fun Context.missingPermissions(): List<String> =
    COMM_PERMISSIONS.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

/** Turned on in Settings → Accessibility (the service may not be bound yet right after). */
private fun Context.isAutoSendEnabled(): Boolean {
    if (AutoSendService.instance != null) return true
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(this, AutoSendService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
}
