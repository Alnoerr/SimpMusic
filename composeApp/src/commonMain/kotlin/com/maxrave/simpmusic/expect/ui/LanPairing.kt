package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import lansync.api.LanSyncController

@Composable
expect fun LanPairingDialog(
    controller: LanSyncController,
    onDismiss: () -> Unit,
)
