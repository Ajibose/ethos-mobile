package com.ethosprotocol.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethosprotocol.models.NotificationPreferences
import com.ethosprotocol.ui.NotificationPreferencesViewModel
import com.ethosprotocol.utils.TrackScreen

@Composable
fun NotificationPreferencesScreen(
    onBack: () -> Unit,
    vm: NotificationPreferencesViewModel = hiltViewModel()
) {
    TrackScreen("NotificationPreferences")
    val state by vm.state.collectAsStateWithLifecycle()
    NotificationPreferencesContent(
        preferences = state.preferences,
        isSaving = state.isSaving,
        error = state.error,
        onUpdate = { vm.update(it) },
        onBack = onBack
    )
}

/**
 * Stateless content layer, extracted for testability.
 */
@Composable
fun NotificationPreferencesContent(
    preferences: NotificationPreferences,
    isSaving: Boolean,
    error: String?,
    onUpdate: (NotificationPreferences) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification Preferences") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Notification Types", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("TTL Expiry Warnings", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Switch(
                    checked = preferences.ttlWarningsEnabled,
                    onCheckedChange = { onUpdate(preferences.copy(ttlWarningsEnabled = it)) }
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Check-in Reminders", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Switch(
                    checked = preferences.checkInRemindersEnabled,
                    onCheckedChange = { onUpdate(preferences.copy(checkInRemindersEnabled = it)) }
                )
            }

            Text(
                "Control which push notifications Ethos-Protocol sends you. Changes are synced with the server so they survive reinstall.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))

            Text("Quiet Hours", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Enable Quiet Hours", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Switch(
                    checked = preferences.quietHoursEnabled,
                    onCheckedChange = { onUpdate(preferences.copy(quietHoursEnabled = it)) }
                )
            }

            if (preferences.quietHoursEnabled) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Start hour: ${formatHour(preferences.quietHoursStart)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    Row {
                        TextButton(onClick = {
                            onUpdate(preferences.copy(quietHoursStart = (preferences.quietHoursStart - 1 + 24) % 24))
                        }) { Text("-") }
                        TextButton(onClick = {
                            onUpdate(preferences.copy(quietHoursStart = (preferences.quietHoursStart + 1) % 24))
                        }) { Text("+") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("End hour: ${formatHour(preferences.quietHoursEnd)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    Row {
                        TextButton(onClick = {
                            onUpdate(preferences.copy(quietHoursEnd = (preferences.quietHoursEnd - 1 + 24) % 24))
                        }) { Text("-") }
                        TextButton(onClick = {
                            onUpdate(preferences.copy(quietHoursEnd = (preferences.quietHoursEnd + 1) % 24))
                        }) { Text("+") }
                    }
                }
                Text(
                    "Notifications suppressed between ${formatHour(preferences.quietHoursStart)} and ${formatHour(preferences.quietHoursEnd)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            if (isSaving) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

private fun formatHour(hour: Int): String {
    val h = hour % 12
    val amPm = if (hour < 12) "AM" else "PM"
    return "${if (h == 0) 12 else h}:00 $amPm"
}
