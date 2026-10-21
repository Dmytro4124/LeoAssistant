package com.dmytro.leo.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dmytro.leo.R

/** Reminders, to-dos and tasks with delete buttons; speaking aloud; the permissions they need. */
fun LazyListScope.remindersSection(
    state: SettingsUiState,
    onSpeak: (Boolean) -> Unit,
    onRemove: (Int) -> Unit,
    onPermissionsChanged: () -> Unit,
) {
    section("Нагадування, справи й календар")
    item(key = "reminders-speak") {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Говорити нагадування вголос", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Окрім сповіщення, Leo промовить нагадування. Мовчить у беззвучному режимі, у «Не турбувати» і під час дзвінка.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.speakReminders, onCheckedChange = onSpeak)
        }
    }
    item(key = "reminders-permissions") { ReminderPermissions(onPermissionsChanged) }
    if (state.reminders.isEmpty()) {
        item(key = "reminders-empty") {
            Hint("Поки нічого. Скажи: «Нагадай завтра о 12:30 піти по хліб» або «Додай у список: купити батарейки».")
        }
    }
    items(state.reminders, key = { "reminder-${it.id}" }) { r ->
        ListItem(
            headlineContent = { Text(r.text) },
            supportingContent = { Text(r.detail) },
            trailingContent = {
                IconButton(onClick = { onRemove(r.id) }) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Видалити «${r.text}»")
                }
            },
        )
    }
    item(key = "divider-reminders") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
}

/** Only the missing ones: calendar, location "all the time" (for places), exact alarms. */
@Composable
private fun ReminderPermissions(onChanged: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        version++
        onChanged()
        onPauseOrDispose { }
    }
    val recheck = { _: Any -> version++; onChanged() }
    val calendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), recheck)
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), recheck)
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), recheck)

    // Checked again after each permission dialog and when coming back from Settings
    val hasCalendar = remember(version) { context.has(Manifest.permission.READ_CALENDAR) && context.has(Manifest.permission.WRITE_CALENDAR) }
    val hasLocation = remember(version) { context.has(Manifest.permission.ACCESS_FINE_LOCATION) }
    val hasBackground = remember(version) { context.has(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val exact = remember(version) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        if (!hasCalendar) {
            PermissionRow("Календар: щоб Leo записував і читав події", "Надати") {
                calendar.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            }
        }
        if (!hasLocation) {
            PermissionRow("Геолокація: для нагадувань «коли буду біля…»", "Надати") {
                location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        } else if (!hasBackground) {
            PermissionRow("Для нагадувань за місцем обери «Дозволяти завжди» — інакше Leo не дізнається, що ти поруч", "Дозволити") {
                background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        if (!exact) {
            PermissionRow("Точні будильники вимкнено: нагадування можуть запізнюватися на кілька хвилин", "Увімкнути") {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }
        }
    }
}

@Composable
private fun PermissionRow(text: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onClick) { Text(action) }
    }
}

private fun Context.has(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
