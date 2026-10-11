package com.lynk.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.lynk.android.ui.LynkScreen
import com.lynk.android.ui.LynkViewModel
import com.lynk.android.ui.theme.LynkTheme

class MainActivity : ComponentActivity() {

    private val viewModel: LynkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LynkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LynkScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh discovery when app comes to foreground
        viewModel.startDiscovery()
    }

    override fun onPause() {
        super.onPause()
        // Stop discovery when in background to conserve battery and Wi-Fi multicast resources
        viewModel.stopDiscovery()
    }
}
