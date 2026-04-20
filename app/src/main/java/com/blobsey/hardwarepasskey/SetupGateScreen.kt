package com.blobsey.hardwarepasskey

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun SetupGateScreen(
    deviceAdminOk: Boolean,
    credProviderOk: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Complete setup to continue",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Credential Provider requirement
        SetupRequirementRow(
            satisfiedLabel = "Credential Provider enabled",
            unsatisfiedLabel = "Credential Provider not enabled",
            satisfied = credProviderOk
        )
        if (!credProviderOk) {
            Button(
                onClick = {
                    val credentialManager = androidx.credentials.CredentialManager.create(context)
                    val pendingIntent = credentialManager.createSettingsPendingIntent()
                    pendingIntent.send()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 24.dp)
            ) {
                Text("Enable Passkey Provider")
            }
        } else {
            Spacer(Modifier.height(24.dp))
        }

        // Device admin requirement
        SetupRequirementRow(
            satisfiedLabel = "Uninstall protection enabled",
            unsatisfiedLabel = "Uninstall protection not enabled",
            satisfied = deviceAdminOk
        )
        if (!deviceAdminOk) {
            Button(
                onClick = {
                    val componentName =
                        ComponentName(context, PasskeyDeviceAdminReceiver::class.java)
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Enable Device Admin to prevent accidental uninstallation of Hardware Passkey."
                        )
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Protect from Uninstallation")
            }
        }
    }
}

@Composable
private fun SetupRequirementRow(satisfiedLabel: String, unsatisfiedLabel: String, satisfied: Boolean) {
    val tint by animateColorAsState(
        targetValue = if (satisfied)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.error,
        label = "requirementTint"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (satisfied) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (satisfied) satisfiedLabel else unsatisfiedLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = tint
        )
    }
}