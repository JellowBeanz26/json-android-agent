package com.jellowbeanz.json

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.jellowbeanz.json.ui.ChatScreen
import com.jellowbeanz.json.ui.theme.JsonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // The app is English-first; keep the UI left-to-right regardless of device locale.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                JsonTheme {
                    ChatScreen()
                }
            }
        }
    }
}
