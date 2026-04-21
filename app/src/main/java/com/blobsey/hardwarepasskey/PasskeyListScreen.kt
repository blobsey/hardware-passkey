package com.blobsey.hardwarepasskey

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private fun formatRelativeTime(epochMillis: Long): String = DateUtils
    .getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

@Composable
private fun PasskeyCard(passkey: PasskeyData, onDeleted: () -> Unit) {
    val context = LocalContext.current

    val showTypeToConfirmDialog = remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = passkey.rpId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = passkey.userName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (passkey.userDisplayName != passkey.userName) {
                    Text(
                        text = passkey.userDisplayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                if (passkey.createdAt > 0) {
                    Text(
                        text = "Created: ${formatRelativeTime(passkey.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text =
                    passkey.lastUsedAt?.let { "Last used: ${formatRelativeTime(it)}" }
                        ?: "Never used",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = { showTypeToConfirmDialog.value = true }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete passkey",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    // Type the site name to confirm
    if (showTypeToConfirmDialog.value) {
        var typedText by remember { mutableStateOf("") }
        val matches = typedText.trim().equals(passkey.rpId, ignoreCase = true)

        AlertDialog(
            onDismissRequest = { showTypeToConfirmDialog.value = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Confirm deletion") },
            text = {
                Column {
                    Text(
                        "To confirm, type the site name exactly as shown below:\n"
                    )
                    Text(
                        text = passkey.rpId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = typedText,
                        onValueChange = { typedText = it },
                        label = { Text("Site name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        WebAuthnCommon.cleanupPasskey(context, passkey.keyAlias)
                        onDeleted()
                    },
                    enabled = matches,
                    colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTypeToConfirmDialog.value = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun PasskeyListScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Refresh trigger bumped after deletions
    var refreshKey by remember { mutableLongStateOf(0L) }

    // Also poll periodically so newly created passkeys appear automatically
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }

    val passkeys: List<PasskeyData> =
        remember(refreshKey, tick) {
            WebAuthnCommon.loadPasskeys(context)
                .sortedByDescending { it.lastUsedAt ?: it.createdAt }
        }

    if (passkeys.isEmpty()) {
        Column(
            modifier =
            modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No passkeys stored",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Passkeys will appear here when you create one on a website or app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    } else {
        LazyColumn(
            modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(passkeys, key = { it.keyAlias }) { passkey ->
                PasskeyCard(
                    passkey = passkey,
                    onDeleted = { refreshKey++ }
                )
            }
        }
    }
}
