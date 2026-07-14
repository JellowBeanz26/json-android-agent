package com.jellowbeanz.json.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellowbeanz.json.Logger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val lines by Logger.lines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Scaffold(
        containerColor = c.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Debug log", style = MaterialTheme.typography.titleLarge, color = c.onBackground) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.onBackground)
                        }
                    },
                    actions = {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }) {
                            Icon(Icons.Filled.ContentCopy, "Copy log", tint = c.onSurfaceVariant)
                        }
                        IconButton(onClick = { Logger.clear() }) {
                            Icon(Icons.Filled.Delete, "Clear log", tint = c.onSurfaceVariant)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = c.background),
                )
                HorizontalDivider(color = c.outline.copy(alpha = 0.6f))
            }
        },
    ) { padding ->
        if (lines.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No logs yet. Send a message and requests will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(lines) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (line.contains("✗")) c.error else c.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}
