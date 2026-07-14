package com.jellowbeanz.json

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.jellowbeanz.json.data.SettingsStore
import com.jellowbeanz.json.ui.App
import com.jellowbeanz.json.ui.theme.JsonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // The app is English-first; keep the UI left-to-right regardless of device locale.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val context = LocalContext.current
                val store = remember { SettingsStore(context) }
                val theme by store.theme.collectAsState(initial = "system")
                val dark = when (theme) {
                    "dark" -> true
                    "light" -> false
                    else -> isSystemInDarkTheme()
                }
                JsonTheme(darkTheme = dark) {
                    App()
                }
            }
        }
    }
}
