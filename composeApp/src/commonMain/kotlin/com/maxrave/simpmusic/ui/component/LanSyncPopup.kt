package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.simpmusic.expect.ui.LanPairingDialog
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.Sync
import com.maxrave.simpmusic.ui.icon.SyncDisabled
import kotlin.time.Clock
import kotlinx.coroutines.launch
import lansync.api.LanSyncController
import lansync.api.LanSyncStatus
import lansync.api.LanSyncUiState
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.edit
import simpmusic.composeapp.generated.resources.lan_sync_auto
import simpmusic.composeapp.generated.resources.lan_sync_connected_device
import simpmusic.composeapp.generated.resources.lan_sync_device_name
import simpmusic.composeapp.generated.resources.lan_sync_enable
import simpmusic.composeapp.generated.resources.lan_sync_forget
import simpmusic.composeapp.generated.resources.lan_sync_last_sync
import simpmusic.composeapp.generated.resources.lan_sync_never
import simpmusic.composeapp.generated.resources.lan_sync_pair
import simpmusic.composeapp.generated.resources.lan_sync_pending
import simpmusic.composeapp.generated.resources.lan_sync_status
import simpmusic.composeapp.generated.resources.lan_sync_sync_now
import simpmusic.composeapp.generated.resources.lan_sync_title
import simpmusic.composeapp.generated.resources.lan_sync_trusted_devices
import simpmusic.composeapp.generated.resources.ok
import simpmusic.composeapp.generated.resources.save

@Composable
fun LanSyncStatusButton(
    state: LanSyncUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = lanStatusDescription(state.status)
    val color = when (state.status) {
        LanSyncStatus.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        LanSyncStatus.UP_TO_DATE, LanSyncStatus.CONNECTED -> MaterialTheme.colorScheme.primary
        LanSyncStatus.DISCOVERING,
        LanSyncStatus.CONNECTING,
        LanSyncStatus.HOST_UNAVAILABLE, -> MaterialTheme.colorScheme.tertiary
        LanSyncStatus.ERROR,
        LanSyncStatus.AUTHENTICATION_FAILED,
        LanSyncStatus.PROTOCOL_MISMATCH, -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp).semantics { contentDescription = description },
    ) {
        if (state.status == LanSyncStatus.SYNCHRONIZING) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                imageVector = if (state.status == LanSyncStatus.DISABLED) SimpIcons.SyncDisabled else SimpIcons.Sync,
                contentDescription = null,
                tint = color,
            )
        }
    }
}

@Composable
fun LanSyncPopup(
    controller: LanSyncController,
    onDismiss: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val devices by controller.trustedDevices.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showPairing by remember { mutableStateOf(false) }
    var forgetDeviceId by remember { mutableStateOf<String?>(null) }
    var renameDeviceId by remember { mutableStateOf<String?>(null) }
    var renameDeviceName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.lan_sync_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(Res.string.lan_sync_enable))
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { enabled -> scope.launch { controller.setEnabled(enabled) } },
                    )
                }
                Text(stringResource(Res.string.lan_sync_status, lanStatusLabel(state.status)))
                state.connectedDeviceName?.let {
                    Text(stringResource(Res.string.lan_sync_connected_device, it))
                }
                Text(
                    stringResource(
                        Res.string.lan_sync_last_sync,
                        state.lastSuccessfulSyncMillis?.let(::relativeSyncTime)
                            ?: stringResource(Res.string.lan_sync_never),
                    ),
                )
                Text(stringResource(Res.string.lan_sync_pending, state.pendingChanges))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showPairing = true },
                        enabled = state.enabled,
                    ) { Text(stringResource(Res.string.lan_sync_pair)) }
                    OutlinedButton(
                        onClick = { scope.launch { runCatching { controller.syncNow() } } },
                        enabled = state.enabled && state.status !in setOf(
                            LanSyncStatus.NOT_PAIRED,
                            LanSyncStatus.DISCOVERING,
                            LanSyncStatus.CONNECTING,
                        ),
                    ) { Text(stringResource(Res.string.lan_sync_sync_now)) }
                }

                if (devices.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        stringResource(Res.string.lan_sync_trusted_devices),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    devices.forEach { device ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(device.displayName, style = MaterialTheme.typography.bodyLarge)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(stringResource(Res.string.lan_sync_auto))
                                Switch(
                                    checked = device.autoSync,
                                    onCheckedChange = { enabled ->
                                        scope.launch { controller.setDeviceAutoSync(device.deviceId, enabled) }
                                    },
                                )
                            }
                            Row {
                                TextButton(
                                    onClick = {
                                        renameDeviceId = device.deviceId
                                        renameDeviceName = device.displayName
                                    },
                                ) { Text(stringResource(Res.string.edit)) }
                                TextButton(onClick = { forgetDeviceId = device.deviceId }) {
                                    Text(stringResource(Res.string.lan_sync_forget), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.ok)) }
        },
    )

    if (showPairing) {
        LanPairingDialog(controller = controller, onDismiss = { showPairing = false })
    }

    renameDeviceId?.let { deviceId ->
        AlertDialog(
            onDismissRequest = { renameDeviceId = null },
            title = { Text(stringResource(Res.string.edit)) },
            text = {
                OutlinedTextField(
                    value = renameDeviceName,
                    onValueChange = { renameDeviceName = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.lan_sync_device_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameDeviceName.isNotBlank(),
                    onClick = {
                        scope.launch { controller.renameDevice(deviceId, renameDeviceName.trim()) }
                        renameDeviceId = null
                    },
                ) { Text(stringResource(Res.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameDeviceId = null }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }

    forgetDeviceId?.let { deviceId ->
        AlertDialog(
            onDismissRequest = { forgetDeviceId = null },
            title = { Text(stringResource(Res.string.lan_sync_forget)) },
            text = { Text("This revokes access. Favorites and local playlists stay on both devices.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { controller.forgetDevice(deviceId) }
                        forgetDeviceId = null
                    },
                ) { Text(stringResource(Res.string.lan_sync_forget), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { forgetDeviceId = null }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

@Composable
private fun lanStatusDescription(status: LanSyncStatus): String = when (status) {
    LanSyncStatus.DISABLED -> "LAN synchronization disabled"
    LanSyncStatus.NOT_PAIRED -> "LAN synchronization not paired"
    LanSyncStatus.DISCOVERING -> "Looking for trusted device"
    LanSyncStatus.CONNECTING -> "Connecting to trusted device"
    LanSyncStatus.SYNCHRONIZING -> "LAN synchronization in progress"
    LanSyncStatus.UP_TO_DATE -> "LAN synchronization up to date"
    LanSyncStatus.ERROR,
    LanSyncStatus.AUTHENTICATION_FAILED,
    LanSyncStatus.PROTOCOL_MISMATCH,
    -> "LAN synchronization error"
    else -> "LAN synchronization ${status.name.lowercase().replace('_', ' ')}"
}

private fun lanStatusLabel(status: LanSyncStatus): String =
    status.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun relativeSyncTime(timestamp: Long): String {
    val seconds = ((Clock.System.now().toEpochMilliseconds() - timestamp).coerceAtLeast(0L) / 1_000L)
    return when {
        seconds < 60 -> "Just now"
        seconds < 3_600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}
