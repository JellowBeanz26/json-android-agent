package com.jellowbeanz.json.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jellowbeanz.json.KeyStore
import com.jellowbeanz.json.data.SettingsStore
import com.jellowbeanz.json.detectProvider
import com.jellowbeanz.json.llm.GeminiProvider
import com.jellowbeanz.json.llm.Llm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onClearData: () -> Unit, onOpenDebug: () -> Unit, onOpenMemory: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    val store = remember { SettingsStore(context) }

    val theme by store.theme.collectAsState(initial = "system")
    val model by store.model.collectAsState(initial = SettingsStore.DEFAULT_MODEL)
    val style by store.style.collectAsState(initial = "default")

    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var localUrl by remember { mutableStateOf("") }
    var localModel by remember { mutableStateOf("") }
    val useLocal by store.useLocal.collectAsState(initial = false)
    LaunchedEffect(Unit) {
        name = store.userName.first()
        about = store.about.first()
        instructions = store.instructions.first()
        localUrl = store.localUrl.first()
        localModel = store.localModel.first()
    }

    var key by remember { mutableStateOf(KeyStore.get(context)) }
    var keySaved by remember { mutableStateOf(false) }
    val provider = remember(key) { detectProvider(key) }
    var confirmClear by remember { mutableStateOf(false) }

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
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Section("Personalization") {
                FieldLabel("What should Json call you?")
                SettingField(name, { name = it; scope.launch { store.setUserName(it) } }, "Your name", singleLine = true)
                Spacer(Modifier.height(14.dp))
                FieldLabel("What should Json know about you?")
                SettingField(about, { about = it; scope.launch { store.setAbout(it) } }, "Your role, interests, preferences…", singleLine = false)
                Spacer(Modifier.height(14.dp))
                FieldLabel("Custom instructions")
                SettingField(instructions, { instructions = it; scope.launch { store.setInstructions(it) } }, "e.g. always answer in Hebrew", singleLine = false)
            }

            Section("Memory") {
                Text(
                    "Json remembers useful facts about you across chats and uses them to personalize replies.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onOpenMemory, shape = RoundedCornerShape(14.dp)) { Text("Manage memory") }
            }

            Section("Response style") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsStore.STYLES.forEach { s ->
                        Pill(s.label, style == s.id) { scope.launch { store.setStyle(s.id) } }
                    }
                }
            }

            val activeProvider = remember(key) { Llm.forKey(key) ?: GeminiProvider }
            Section("Model · ${activeProvider.label}") {
                var expanded by remember { mutableStateOf(false) }
                val selected = activeProvider.models.firstOrNull { it.id == model } ?: activeProvider.models.first()
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { expanded = true }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(selected.label, style = MaterialTheme.typography.bodyLarge, color = c.onBackground)
                            Text(selected.note, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                        Icon(Icons.Filled.ArrowDropDown, "Choose model", tint = c.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        activeProvider.models.forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(m.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(m.note, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                                    }
                                },
                                trailingIcon = if (m.id == selected.id) {
                                    { Icon(Icons.Filled.Check, null, tint = c.primary) }
                                } else {
                                    null
                                },
                                onClick = { scope.launch { store.setModel(m.id) }; expanded = false },
                            )
                        }
                    }
                }
            }

            Section("Appearance") {
                FieldLabel("Theme")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (id, label) ->
                        Pill(label, theme == id) { scope.launch { store.setTheme(id) } }
                    }
                }
            }

            Section("API key") {
                Text(
                    "Stored only on this device. It never leaves except to call the model.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                SettingField(key, { key = it; keySaved = false }, "AQ…  or  AIza…", singleLine = true)
                when {
                    provider != null -> Text("Detected: ${provider.name}", style = MaterialTheme.typography.labelLarge, color = c.primary, modifier = Modifier.padding(top = 8.dp))
                    key.isNotBlank() -> Text("Unknown provider", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { KeyStore.set(context, key); keySaved = true },
                    colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(if (keySaved) "Saved ✓" else "Save key") }
            }

            Section("Local model") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use a local model", style = MaterialTheme.typography.titleSmall, color = c.onBackground)
                        Text("Ollama, LM Studio, llama.cpp…", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    Switch(checked = useLocal, onCheckedChange = { scope.launch { store.setUseLocal(it) } })
                }
                if (useLocal) {
                    Spacer(Modifier.height(14.dp))
                    FieldLabel("Server URL")
                    SettingField(localUrl, { localUrl = it; scope.launch { store.setLocalUrl(it) } }, "http://10.0.0.2:11434", singleLine = true)
                    Spacer(Modifier.height(14.dp))
                    FieldLabel("Model name")
                    SettingField(localModel, { localModel = it; scope.launch { store.setLocalModel(it) } }, "e.g. llama3.2, gemma2, qwen2.5", singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "On a PC: run Ollama or LM Studio and enter your computer's IP. On the phone: run it in Termux and use 127.0.0.1. Both must be on the same Wi-Fi, with the server bound to 0.0.0.0.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                    )
                }
            }

            Section("Data") {
                Text(
                    "Delete every conversation on this device. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { confirmClear = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.error),
                    border = BorderStroke(1.dp, c.error),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Clear all conversations") }
            }

            Section("Developer") {
                Text(
                    "See every request, response, and error — handy when adding a new key.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onOpenDebug,
                    shape = RoundedCornerShape(14.dp),
                ) { Text("View debug log") }
            }

            Section("About") {
                Text("Json — an AI that lives on your phone.", style = MaterialTheme.typography.bodyLarge, color = c.onBackground)
                Text("Version 0.2", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all conversations?") },
            text = { Text("Every chat on this device will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { onClearData(); confirmClear = false }) { Text("Delete all", color = c.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = c.primary)
        Surface(color = c.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, c.outline)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun SettingField(value: String, onValueChange: (String) -> Unit, placeholder: String, singleLine: Boolean) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = c.background, border = BorderStroke(1.dp, c.outline)) {
        Box(Modifier.fillMaxWidth().padding(14.dp)) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onBackground),
                cursorBrush = SolidColor(c.primary),
                modifier = if (singleLine) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().heightIn(min = 56.dp),
            )
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        color = if (selected) c.primary else c.background,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (selected) c.primary else c.outline),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) c.onPrimary else c.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}
