package com.jellowbeanz.json.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellowbeanz.json.ChatViewModel
import com.jellowbeanz.json.JsonAccessibilityService
import com.jellowbeanz.json.KeyStore
import com.jellowbeanz.json.R
import com.jellowbeanz.json.Streaming
import com.jellowbeanz.json.data.Conversation
import com.jellowbeanz.json.data.Message
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.app.Activity
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.graphics.vector.ImageVector
import com.jellowbeanz.json.data.SettingsStore
import com.jellowbeanz.json.llm.GeminiProvider
import com.jellowbeanz.json.llm.Llm
import com.jellowbeanz.json.llm.ModelOption

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val currentId by vm.currentId.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val agentMode by vm.agentMode.collectAsStateWithLifecycle()
    var showAgentSetup by remember { mutableStateOf(false) }

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val imeVisible = WindowInsets.isImeVisible
    val rows = remember(messages) { toChatRows(messages) }

    LaunchedEffect(rows.size, streaming, imeVisible) {
        val total = rows.size + if (streaming.active) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    // Model picker data (top models of the active provider) for the input bar.
    val store = remember { SettingsStore(context) }
    val modelId by store.model.collectAsStateWithLifecycle(initialValue = SettingsStore.DEFAULT_MODEL)
    val apiKey = KeyStore.get(context)
    val provider = remember(apiKey) { Llm.forKey(apiKey) ?: GeminiProvider }
    val modelLabel = remember(provider, modelId) {
        provider.models.firstOrNull { it.id == modelId }?.label ?: provider.models.first().label
    }

    // Voice: text-to-speech for spoken replies + speech-to-text for the mic / voice button.
    val tts = remember { TextToSpeech(context) { } }
    DisposableEffect(Unit) { onDispose { tts.stop(); tts.shutdown() } }
    var autoSendVoice by remember { mutableStateOf(false) }
    var speakNextReply by remember { mutableStateOf(false) }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank()) {
            if (autoSendVoice) {
                speakNextReply = true
                vm.send(spoken, apiKey)
            } else {
                input = if (input.isBlank()) spoken else input.trimEnd() + " " + spoken
            }
        }
        autoSendVoice = false
    }
    fun startSpeech(autoSend: Boolean) {
        autoSendVoice = autoSend
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
        }
        runCatching { speechLauncher.launch(intent) }
    }
    // Read a voice-initiated reply aloud once it's finished streaming.
    LaunchedEffect(messages.lastOrNull()?.id, streaming.active) {
        val last = messages.lastOrNull()
        if (speakNextReply && !streaming.active && last != null && last.role != "user" && last.role != "action") {
            runCatching { tts.speak(last.text.take(4000), TextToSpeech.QUEUE_FLUSH, null, "reply") }
            speakNextReply = false
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                conversations = conversations,
                currentId = currentId,
                onNew = { vm.newChat(); scope.launch { drawerState.close() } },
                onSelect = { vm.select(it); scope.launch { drawerState.close() } },
                onRename = { c, title -> vm.rename(c.id, title) },
                onTogglePin = { vm.togglePin(it) },
                onDelete = { vm.delete(it.id) },
                onSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                JsonTopBar(
                    onMenu = { scope.launch { drawerState.open() } },
                    onNewChat = { vm.newChat() },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .fillMaxSize(),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty() && !streaming.active) {
                        EmptyState(onPrompt = { input = it })
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(rows, key = { it.key }) { row ->
                                when (row) {
                                    is ChatRow.User -> UserBubble(row.msg.text)
                                    is ChatRow.Assistant -> AssistantMessage(row.msg.text)
                                    is ChatRow.Actions -> AgentActions(row.items)
                                }
                            }
                            if (streaming.active) item { StreamingMessage(streaming) }
                        }
                    }
                }
                InputBar(
                    value = input,
                    onValueChange = { input = it },
                    agentMode = agentMode,
                    onToggleAgent = {
                        if (agentMode) {
                            vm.setAgentMode(false)
                        } else if (isAccessibilityServiceEnabled(context) && Settings.canDrawOverlays(context)) {
                            vm.setAgentMode(true)
                        } else {
                            showAgentSetup = true
                        }
                    },
                    onSend = {
                        val t = input.trim()
                        if (t.isNotBlank()) {
                            input = ""
                            vm.send(t, apiKey)
                        }
                    },
                    modelLabel = modelLabel,
                    models = provider.models.take(5),
                    onSelectModel = { id -> scope.launch { store.setModel(id) } },
                    onMic = { startSpeech(autoSend = false) },
                    onVoice = { startSpeech(autoSend = true) },
                    onNewChat = { vm.newChat() },
                )
            }
        }
    }

    if (showAgentSetup) {
        AgentSetupDialog(
            onDismiss = { showAgentSetup = false },
            onAccessibility = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onOverlay = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onReady = {
                vm.setAgentMode(true)
                showAgentSetup = false
            },
        )
    }
}

/** Whether our accessibility service is enabled in system settings (authoritative, reflects the user's toggle). */
private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${JsonAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

// ---------- top bar ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JsonTopBar(onMenu: () -> Unit, onNewChat: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column {
        TopAppBar(
            title = { Text("Json", style = MaterialTheme.typography.titleLarge, color = c.onBackground) },
            navigationIcon = {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Filled.Menu, contentDescription = "Conversations", tint = c.onBackground)
                }
            },
            actions = {
                IconButton(onClick = onNewChat) {
                    Icon(Icons.Filled.Add, contentDescription = "New chat", tint = c.onBackground)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = c.background),
        )
        HorizontalDivider(color = c.outline.copy(alpha = 0.6f))
    }
}

// ---------- history drawer ----------

@Composable
private fun HistoryDrawer(
    conversations: List<Conversation>,
    currentId: Long?,
    onNew: () -> Unit,
    onSelect: (Long) -> Unit,
    onRename: (Conversation, String) -> Unit,
    onTogglePin: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
    onSettings: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    val filtered = remember(conversations, query) {
        if (query.isBlank()) conversations else conversations.filter { it.title.contains(query, ignoreCase = true) }
    }

    ModalDrawerSheet(drawerContainerColor = c.background, modifier = Modifier.fillMaxWidth(0.86f)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text(
                "Json",
                style = MaterialTheme.typography.titleLarge,
                color = c.onBackground,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 12.dp),
            )

            Surface(
                color = c.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, c.outline),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(onClick = onNew),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Filled.Add, null, tint = c.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("New chat", style = MaterialTheme.typography.titleSmall, color = c.onBackground)
                }
            }

            Surface(
                color = c.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, c.outline),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Filled.Search, null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text("Search", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onBackground),
                            cursorBrush = SolidColor(c.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(filtered, key = { it.id }) { conv ->
                    ConversationRow(
                        conv = conv,
                        selected = conv.id == currentId,
                        onClick = { onSelect(conv.id) },
                        onRename = { renaming = conv },
                        onTogglePin = { onTogglePin(conv) },
                        onDelete = { deleting = conv },
                    )
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (query.isBlank()) "No conversations yet" else "No matches",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }

            HorizontalDivider(color = c.outline.copy(alpha = 0.6f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSettings)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
            ) {
                Icon(Icons.Filled.Settings, null, tint = c.onBackground)
                Spacer(Modifier.width(14.dp))
                Text("Settings", style = MaterialTheme.typography.titleSmall, color = c.onBackground)
            }
        }
    }

    renaming?.let { conv ->
        RenameDialog(conv, onDismiss = { renaming = null }, onConfirm = { onRename(conv, it); renaming = null })
    }
    deleting?.let { conv ->
        DeleteDialog(conv, onDismiss = { deleting = null }, onConfirm = { onDelete(conv); deleting = null })
    }
}

@Composable
private fun ConversationRow(
    conv: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = if (selected) c.surfaceVariant else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        ) {
            if (conv.pinned) {
                Icon(Icons.Filled.PushPin, null, tint = c.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                conv.title,
                style = MaterialTheme.typography.bodyLarge,
                color = c.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, "Options", tint = c.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (conv.pinned) "Unpin" else "Pin") },
                        leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                        onClick = { menu = false; onTogglePin() },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = c.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(conv: Conversation, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(conv.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteDialog(conv: Conversation, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete chat?") },
        text = { Text("\"${conv.title}\" will be permanently deleted.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = c.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- empty state ----------

@Composable
private fun EmptyState(onPrompt: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val suggestions = listOf(
        "What can you do?",
        "Draft a text message for me",
        "Explain a concept simply",
        "Give me an idea for today",
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(c.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_json_spark),
                contentDescription = null,
                tint = c.onPrimary,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("How can I help?", style = MaterialTheme.typography.headlineSmall, color = c.onBackground)
        Spacer(Modifier.height(28.dp))
        suggestions.forEach { s ->
            Surface(
                color = c.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, c.outline),
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onPrompt(s) },
            ) {
                Text(
                    s,
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.onBackground,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
    }
}

// ---------- message rows ----------

@Composable
private fun UserBubble(text: String) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            color = c.surfaceVariant,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.onBackground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        CopyButton(text)
    }
}

@Composable
private fun AssistantMessage(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        JsonMark()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SelectionContainer {
                MarkdownText(text, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 3.dp))
            }
            CopyButton(text)
        }
    }
}

/** A small copy affordance under a message; briefly shows a check when tapped. */
@Composable
private fun CopyButton(text: String) {
    val c = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1200); copied = false } }
    IconButton(
        onClick = { clipboard.setText(AnnotatedString(text)); copied = true },
        modifier = Modifier.size(28.dp),
    ) {
        Icon(
            if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            contentDescription = "Copy message",
            tint = if (copied) c.primary else c.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** The message being generated: live reasoning first, then the answer streams in (reasoning fades away). */
@Composable
private fun StreamingMessage(state: Streaming) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        JsonMark()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (state.answer.isBlank()) {
                LiveThinking(state.reasoning)
            } else {
                MarkdownText(state.answer, color = c.onBackground, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun LiveThinking(reasoning: String) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val transition = rememberInfiniteTransition(label = "thinking")
            val alpha by transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
                label = "pulse",
            )
            Text("Thinking", style = MaterialTheme.typography.labelLarge, color = c.primary.copy(alpha = alpha))
            Spacer(Modifier.width(8.dp))
            ThinkingDots()
        }
        val lines = remember(reasoning) {
            reasoning.split(Regex("(?<=[.!?])\\s+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }
        if (lines.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val shown = lines.takeLast(3)
            Column(
                modifier = Modifier.animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                shown.forEachIndexed { i, line ->
                    val a = when (shown.size - 1 - i) {
                        0 -> 1f
                        1 -> 0.5f
                        else -> 0.28f
                    }
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant.copy(alpha = a),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingDots() {
    val c = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 160),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(Modifier.size(6.dp).clip(CircleShape).background(c.onSurfaceVariant.copy(alpha = alpha)))
        }
    }
}

@Composable
private fun JsonMark() {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(c.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_json_spark),
            contentDescription = null,
            tint = c.onPrimary,
            modifier = Modifier.size(17.dp),
        )
    }
}

// ---------- input ----------

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    agentMode: Boolean,
    onToggleAgent: () -> Unit,
    modelLabel: String,
    models: List<ModelOption>,
    onSelectModel: (String) -> Unit,
    onMic: () -> Unit,
    onVoice: () -> Unit,
    onNewChat: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val hasText = value.isNotBlank()
    var plusMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }

    Surface(color = c.background, modifier = Modifier.imePadding()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            shape = RoundedCornerShape(26.dp),
            color = c.surface,
            border = BorderStroke(1.dp, if (agentMode) c.primary else c.outline),
        ) {
            Column(Modifier.padding(6.dp)) {
                // Row 1 — the text field.
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (value.isEmpty()) {
                        Text(
                            if (agentMode) "Tell Json what to do on your phone…" else "Message Json…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onBackground),
                        cursorBrush = SolidColor(c.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Row 2 — the toolbar: + · model    …    mic · voice/send.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        ToolButton(Icons.Filled.Add, "More options") { plusMenu = true }
                        DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (agentMode) "Stop controlling phone" else "Control my phone") },
                                leadingIcon = {
                                    Icon(Icons.Filled.PhoneAndroid, null, tint = if (agentMode) c.primary else c.onSurfaceVariant)
                                },
                                onClick = { plusMenu = false; onToggleAgent() },
                            )
                            DropdownMenuItem(
                                text = { Text("New chat") },
                                leadingIcon = { Icon(Icons.Filled.Add, null) },
                                onClick = { plusMenu = false; onNewChat() },
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Box {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = c.background,
                            border = BorderStroke(1.dp, c.outline),
                            modifier = Modifier.clickable { modelMenu = true },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                            ) {
                                Text(
                                    modelLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = c.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 130.dp),
                                )
                                Icon(Icons.Filled.KeyboardArrowDown, null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            models.forEach { m ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(m.label, style = MaterialTheme.typography.bodyMedium, color = c.onBackground)
                                            Text(m.note, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { modelMenu = false; onSelectModel(m.id) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    ToolButton(Icons.Filled.Mic, "Dictate", onClick = onMic)
                    Spacer(Modifier.width(2.dp))
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(c.primary)
                            .clickable(onClick = { if (hasText) onSend() else onVoice() }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (hasText) Icons.AutoMirrored.Filled.Send else Icons.Filled.GraphicEq,
                            contentDescription = if (hasText) "Send" else "Voice chat",
                            tint = c.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

/** A rendered chat row: a user bubble, an assistant message, or a collapsed group of agent actions. */
private sealed class ChatRow(val key: String) {
    class User(val msg: Message) : ChatRow("u${msg.id}")
    class Assistant(val msg: Message) : ChatRow("m${msg.id}")
    class Actions(val items: List<Message>) : ChatRow("g${items.first().id}")
}

/** Coalesce consecutive agent "action" messages into one collapsible group so the chat stays clean. */
private fun toChatRows(messages: List<Message>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var buffer = mutableListOf<Message>()
    fun flush() {
        if (buffer.isNotEmpty()) {
            rows.add(ChatRow.Actions(buffer.toList()))
            buffer = mutableListOf()
        }
    }
    for (m in messages) {
        when (m.role) {
            "action" -> buffer.add(m)
            "user" -> { flush(); rows.add(ChatRow.User(m)) }
            else -> { flush(); rows.add(ChatRow.Assistant(m)) }
        }
    }
    flush()
    return rows
}

/** Collapsed agent steps: shows the latest step + a count; tap the arrow to reveal the full list. */
@Composable
private fun AgentActions(items: List<Message>) {
    val c = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.padding(start = 40.dp)) {
        Surface(color = c.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, c.outline)) {
            Column(Modifier.animateContentSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c.primary))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (expanded) "Steps Json took" else (items.lastOrNull()?.text ?: "Working…"),
                        style = MaterialTheme.typography.labelLarge,
                        color = c.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${items.size}", style = MaterialTheme.typography.labelLarge, color = c.primary)
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = c.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (expanded) {
                    HorizontalDivider(color = c.outline)
                    Column(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items.forEachIndexed { i, m ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    "${i + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = c.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.width(22.dp),
                                )
                                Text(m.text, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSetupDialog(
    onDismiss: () -> Unit,
    onAccessibility: () -> Unit,
    onOverlay: () -> Unit,
    onReady: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Re-check both permissions whenever the user returns from a settings screen, so the checkmarks are live.
    var accessibilityOn by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityOn = isAccessibilityServiceEnabled(context)
                overlayOn = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val ready = accessibilityOn && overlayOn
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Let Json control your phone") },
        text = {
            Column {
                Text(
                    "Json needs two one-time permissions. Turn each on — the checkmarks update by themselves, then tap Start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                PermissionRow("Accessibility — see & tap the screen", accessibilityOn, onAccessibility)
                Spacer(Modifier.height(8.dp))
                PermissionRow("Display over other apps — control badge", overlayOn, onOverlay)
            }
        },
        confirmButton = {
            TextButton(onClick = onReady, enabled = ready) {
                Text(if (ready) "Start" else "Waiting for permissions…")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (granted) c.primary else c.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.onBackground, modifier = Modifier.weight(1f))
        if (granted) {
            Text("On", style = MaterialTheme.typography.labelLarge, color = c.primary)
        } else {
            TextButton(onClick = onGrant, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                Text("Turn on")
            }
        }
    }
}
