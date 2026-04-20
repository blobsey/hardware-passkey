package com.blobsey.passkeyholder

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.blobsey.passkeyholder.ui.theme.PasskeyHolderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PasskeyHolderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Passkey Holder", modifier = Modifier.padding(bottom = 32.dp))

        Button(
            onClick = {
                // Launch the Android settings page to enable this app as a Passkey Provider
                val credentialManager = CredentialManager.create(context)
                val intent = credentialManager.createSettingsPendingIntent()
                intent.send()
            },
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Enable Passkey Provider")
        }

        Button(
            onClick = {
                // Launch the Device Admin prompt to protect the app from uninstallation
                val componentName = ComponentName(context, PasskeyDeviceAdminReceiver::class.java)
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Enable Device Admin to prevent accidental uninstallation of your Bitwarden Passkey."
                    )
                }
                context.startActivity(intent)
            }
        ) {
            Text("Protect from Uninstallation")
        }
    }
}