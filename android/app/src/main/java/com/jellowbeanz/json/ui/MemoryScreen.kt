package com.jellowbeanz.json.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jellowbeanz.json.data.SettingsStore
import com.jellowbeanz.json.memory.MemoryExtractor
import kotlinx.coroutines.launch

/** ChatGPT-style memory manager: see, add, and delete the durable facts Json remembers about you. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    val store = remember { SettingsStore(context) }

    val enabled by store.memoryEnabled.collectAsState(initial = true)
    val memories by store.memories.collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = c.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Memory", style = MaterialTheme.typography.titleLarge, color = c.onBackground) },
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
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // What memory is + the on/off switch.
            Surface(color = c.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, c.outline)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Remember things about me", style = MaterialTheme.typography.titleSmall, color = c.onBackground)
                            Text(
                                "Json picks up useful facts as you chat and uses them to personalize future replies.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = enabled, onCheckedChange = { scope.launch { store.setMemoryEnabled(it) } })
                    }
                }
            }

            // Header row: count + clear-all.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "SAVED · ${memories.size} / ${MemoryExtractor.MAX}",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.primary,
                    modifier = Modifier.weight(1f),
                )
                if (memories.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) { Text("Clear all", color = c.error) }
                }
            }

            // Add a memory by hand.
            Surface(shape = RoundedCornerShape(12.dp), color = c.background, border = BorderStroke(1.dp, c.outline)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                        if (draft.isEmpty()) {
                            Text("Add something Json should remember…", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onBackground),
                            cursorBrush = SolidColor(c.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    IconButton(
                        onClick = {
                            val t = draft.trim()
                            if (t.isNotEmpty()) { scope.launch { store.addMemory(t) }; draft = "" }
                        },
                        enabled = draft.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Add, "Add", tint = if (draft.isNotBlank()) c.primary else c.onSurfaceVariant)
                    }
                }
            }

            // The list.
            if (memories.isEmpty()) {
                Text(
                    "Nothing remembered yet. As you chat, Json will note useful facts about you here — and you can always add, edit, or delete them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    memories.forEach { fact ->
                        Surface(color = c.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, c.outline)) {
                            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(fact, style = MaterialTheme.typography.bodyLarge, color = c.onBackground, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                                IconButton(onClick = { scope.launch { store.removeMemory(fact) } }) {
                                    Icon(Icons.Filled.Close, "Forget", tint = c.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all memory?") },
            text = { Text("Json will forget everything it has learned about you. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { store.clearMemories() }; confirmClear = false }) {
                    Text("Forget all", color = c.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
