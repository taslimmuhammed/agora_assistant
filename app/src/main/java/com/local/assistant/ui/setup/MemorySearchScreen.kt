package com.local.assistant.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.local.assistant.model.Transfer
import com.local.assistant.model.TransferKind
import com.local.assistant.model.formatBytes
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.GlassIconButton
import com.local.assistant.ui.theme.glass

/**
 * The one model the phone still keeps for itself: photo and memory search's embedder, optional.
 * Luna's replies come from her server, so there is no chat model to download or manage.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MemorySearchScreen(memorySearch: MemorySearchControls, onBack: () -> Unit) {
    Scaffold(
        containerColor = AppColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Photo and memory search", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack, Modifier.padding(start = 10.dp, end = 6.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.Background,
                    titleContentColor = AppColors.TextPrimary,
                    navigationIconContentColor = AppColors.TextPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // One card of glass, as a settings page.
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp)
                .glass()
                .padding(horizontal = 18.dp),
        ) {
            Text(
                text = "Luna answers from her server, the same one her calls use, so chatting needs no download. " +
                    "This model is optional: it runs on the phone, to find photos by what's in them and earlier chats by meaning.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 18.dp),
            )
            MemorySearchSection(memorySearch)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun TransferSection(transfer: Transfer, onCancel: () -> Unit) {
    Column(Modifier.padding(top = 28.dp)) {
        Text(
            text = when (transfer.kind) {
                TransferKind.DOWNLOAD -> "Downloading"
                TransferKind.IMPORT -> "Importing"
            },
            style = MaterialTheme.typography.labelLarge,
            color = AppColors.TextPrimary,
        )
        LinearProgressIndicator(
            progress = { transfer.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            color = AppColors.Accent,
            trackColor = AppColors.SurfaceMuted,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${formatBytes(transfer.bytesDone)} / ${formatBytes(transfer.bytesTotal)}",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
            )
            transfer.etaSeconds?.let {
                Text(
                    text = "${formatBytes(transfer.bytesPerSecond)}/s · ${formatDuration(it)} left",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                )
            }
        }
        if (transfer.kind == TransferKind.DOWNLOAD) {
            Text(
                text = "You can leave the app: it keeps downloading, with its progress in a notification.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        // All of it is here while it's checked. A download keeps what arrived: Pause, and the
        // next Download carries on from there.
        if (!transfer.checking) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.padding(top = 20.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (transfer.kind == TransferKind.DOWNLOAD) "Pause" else "Cancel")
            }
        }
    }
}

private fun formatDuration(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds}s"
}
