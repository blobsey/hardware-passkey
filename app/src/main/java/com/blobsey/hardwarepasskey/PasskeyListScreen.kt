package com.blobsey.hardwarepasskey

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blobsey.hardwarepasskey.ui.theme.DeleteRed
import kotlinx.coroutines.delay

private fun formatRelativeTime(epochMillis: Long): String = DateUtils
    .getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasskeyListItem(passkey: PasskeyData, onDeleted: () -> Unit) {
    val context = LocalContext.current

    val showSheet = remember { mutableStateOf(false) }
    val showTypeToConfirmDialog = remember { mutableStateOf(false) }

    Surface(
        onClick = { showSheet.value = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        ListItem(
            modifier = Modifier.padding(4.dp),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(
                    text = passkey.rpId,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Column {
                    Text(
                        text = passkey.userDisplayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = passkey.lastUsedAt
                            ?.let { "Last used ${formatRelativeTime(it)}" }
                            ?: "Never used",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        )
    }

    if (showSheet.value) {
        ModalBottomSheet(onDismissRequest = { showSheet.value = false }) {
            Column(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
            ) {
                Text(
                    text = passkey.rpId,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.size(16.dp))
                DetailRow("Username", passkey.userName)
                DetailRow("Display name", passkey.userDisplayName)
                DetailRow("Created", formatRelativeTime(passkey.createdAt))
                DetailRow(
                    "Last used",
                    passkey.lastUsedAt?.let { formatRelativeTime(it) } ?: "Never"
                )
                DetailRow("User ID", passkey.userId)
                DetailRow("Key alias", passkey.keyAlias)
                Spacer(modifier = Modifier.size(24.dp))
                TextButton(
                    onClick = {
                        showSheet.value = false
                        showTypeToConfirmDialog.value = true
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = DeleteRed,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Delete passkey")
                }
            }
        }
    }

    // Type the site name to confirm
    if (showTypeToConfirmDialog.value) {
        var typedText by remember { mutableStateOf("") }
        val matches = typedText.trim().equals(passkey.rpId, ignoreCase = true)

        AlertDialog(
            onDismissRequest = { showTypeToConfirmDialog.value = false },
            title = { Text("Confirm deletion") },
            text = {
                Column {
                    Text("To confirm, type the site name exactly as shown below:")
                    Text(
                        text = passkey.rpId,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
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
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(passkeys, key = { it.keyAlias }) { passkey ->
                PasskeyListItem(
                    passkey = passkey,
                    onDeleted = { refreshKey++ }
                )
            }
        }
    }
}
