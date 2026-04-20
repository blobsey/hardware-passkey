package com.blobsey.hardwarepasskey

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

private fun isDeviceAdminEnabled(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val componentName = ComponentName(context, PasskeyDeviceAdminReceiver::class.java)
    return dpm.isAdminActive(componentName)
}

private fun isCredentialProviderEnabled(context: Context): Boolean = try {
    val cm = context.getSystemService(android.credentials.CredentialManager::class.java)
    val componentName = ComponentName(context, PasskeyCredentialProviderService::class.java)
    cm.isEnabledCredentialProviderService(componentName)
} catch (_: Exception) {
    false
}

@Composable
fun MainApp() {
    val context = LocalContext.current

    // Poll status checks every second (no callback APIs exist for these)
    var deviceAdminOk by remember { mutableStateOf(isDeviceAdminEnabled(context)) }
    var credProviderOk by remember { mutableStateOf(isCredentialProviderEnabled(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            deviceAdminOk = isDeviceAdminEnabled(context)
            credProviderOk = isCredentialProviderEnabled(context)
        }
    }

    val setupComplete = deviceAdminOk && credProviderOk

    Scaffold { innerPadding ->
        if (setupComplete) {
            PasskeyListScreen(modifier = Modifier.padding(innerPadding))
        } else {
            SetupGateScreen(
                deviceAdminOk = deviceAdminOk,
                credProviderOk = credProviderOk,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
