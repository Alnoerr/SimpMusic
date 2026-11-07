package com.maxrave.simpmusic.expect.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.maxrave.simpmusic.ui.component.QrScanner
import kotlin.time.Clock
import kotlinx.coroutines.launch
import lansync.api.LanSyncController
import lansync.model.LanPairingPayload
import lansync.model.LanPairingPayloadCodec
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.lan_sync_confirm_pairing
import simpmusic.composeapp.generated.resources.lan_sync_pair
import simpmusic.composeapp.generated.resources.lan_sync_scan_hint
import simpmusic.composeapp.generated.resources.lan_sync_trust_checkbox

@Composable
actual fun LanPairingDialog(
    controller: LanSyncController,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var payloadText by remember { mutableStateOf<String?>(null) }
    var payload by remember { mutableStateOf<LanPairingPayload?>(null) }
    var trust by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
        if (!it) error = "Camera permission is required to scan the pairing code."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.lan_sync_pair)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(32.dp))
                    payload == null && cameraGranted -> {
                        Text(stringResource(Res.string.lan_sync_scan_hint))
                        QrScanner(
                            onResult = { scanned ->
                                if (!LanPairingPayloadCodec.isLanPairingPayload(scanned)) {
                                    error = "This QR code is not a LAN synchronization invitation."
                                } else {
                                    runCatching {
                                        LanPairingPayloadCodec.decode(scanned).also {
                                            it.requireUsable(Clock.System.now().toEpochMilliseconds())
                                        }
                                    }.onSuccess {
                                        payloadText = scanned
                                        payload = it
                                        error = null
                                    }.onFailure { error = it.message }
                                }
                            },
                            onCameraUnavailable = { error = "No camera is available." },
                            modifier = Modifier.fillMaxWidth().size(280.dp),
                        )
                    }
                    payload == null -> {
                        Text(error ?: "Camera access is needed to scan the Desktop pairing code.")
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Allow camera")
                        }
                    }
                    else -> {
                        val value = payload!!
                        Text(stringResource(Res.string.lan_sync_confirm_pairing, value.hostDisplayName))
                        Text("${value.host}:${value.port}")
                        Text("Fingerprint: ${value.certificateFingerprint.take(16)}…")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = trust, onCheckedChange = { trust = it })
                            Text(
                                stringResource(Res.string.lan_sync_trust_checkbox),
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
                error?.takeIf { payload != null || cameraGranted }?.let {
                    Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (payload != null) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { controller.pair(payloadText!!, trust) }
                                .onSuccess { onDismiss() }
                                .onFailure { error = it.message; busy = false }
                        }
                    },
                ) { Text(stringResource(Res.string.lan_sync_pair)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}
