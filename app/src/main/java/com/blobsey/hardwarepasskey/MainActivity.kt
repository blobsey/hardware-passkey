package com.blobsey.hardwarepasskey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.blobsey.hardwarepasskey.ui.theme.HardwarePasskeyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HardwarePasskeyTheme {
                MainApp()
            }
        }
    }
}
