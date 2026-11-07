package com.maxrave.simpmusic.expect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch
import lansync.api.LanSyncController
import lansync.model.LanPairingPayloadCodec
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.lan_sync_pair
import simpmusic.composeapp.generated.resources.lan_sync_pair_expires

@Composable
actual fun LanPairingDialog(
    controller: LanSyncController,
    onDismiss: () -> Unit,
) {
    var payload by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val closePairing = {
        scope.launch { controller.cancelPairing() }
        onDismiss()
    }
    LaunchedEffect(controller) {
        runCatching { controller.createPairingQrPayload() }
            .onSuccess { payload = it }
            .onFailure { error = it.message }
    }
    AlertDialog(
        onDismissRequest = closePairing,
        title = { Text(stringResource(Res.string.lan_sync_pair)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    payload != null -> {
                        QrCode(payload!!, Modifier.size(260.dp))
                        val invitation = LanPairingPayloadCodec.decode(payload!!)
                        Text(stringResource(Res.string.lan_sync_pair_expires, invitation.host))
                        Text("Fingerprint: ${invitation.certificateFingerprint.take(16)}…")
                    }
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    else -> CircularProgressIndicator()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = closePairing) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

@Composable
private fun QrCode(
    text: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White)) {
        val cell = minOf(size.width / matrix.width, size.height / matrix.height)
        val left = (size.width - matrix.width * cell) / 2
        val top = (size.height - matrix.height * cell) / 2
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(Color.Black, Offset(left + x * cell, top + y * cell), Size(cell, cell))
                }
            }
        }
    }
}
