package com.jellowbeanz.json.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jellowbeanz.json.KeyStore
import com.jellowbeanz.json.detectProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    var key by remember { mutableStateOf(KeyStore.get(context)) }
    var saved by remember { mutableStateOf(false) }
    val provider = remember(key) { detectProvider(key) }

    Scaffold(
        containerColor = c.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Settings", style = MaterialTheme.typography.titleLarge, color = c.onBackground) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.onBackground)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = c.background),
                )
                HorizontalDivider(color = c.outline.copy(alpha = 0.6f))
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("API key", style = MaterialTheme.typography.titleMedium, color = c.onBackground)
            Text(
                "Paste your API key. It's stored only on this device and never leaves it except to call the model.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = c.surface,
                border = BorderStroke(1.dp, c.outline),
            ) {
                Box(Modifier.padding(16.dp)) {
                    if (key.isEmpty()) {
                        Text("AQ…  or  AIza…", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = key,
                        onValueChange = { key = it; saved = false },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onBackground),
                        cursorBrush = SolidColor(c.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            when {
                provider != null -> Text(
                    "Detected: ${provider.name}",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.primary,
                )
                key.isNotBlank() -> Text(
                    "Unknown provider",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    KeyStore.set(context, key)
                    saved = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Save key") }
            if (saved) {
                Text("Saved.", style = MaterialTheme.typography.labelLarge, color = c.primary)
            }
        }
    }
}
