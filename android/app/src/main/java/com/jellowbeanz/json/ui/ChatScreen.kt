package com.jellowbeanz.json.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellowbeanz.json.ChatViewModel
import com.jellowbeanz.json.KeyStore
import com.jellowbeanz.json.R
import com.jellowbeanz.json.data.Conversation
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val currentId by vm.currentId.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val thinking by vm.thinking.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(messages.size, thinking, imeVisible) {
        val total = messages.size + if (thinking) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
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
                    if (messages.isEmpty() && !thinking) {
                        EmptyState(onPrompt = { input = it })
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(messages, key = { it.id }) { msg ->
                                if (msg.role == "user") UserBubble(msg.text) else AssistantMessage(msg.text, msg.reasoning)
                            }
                            if (thinking) item { ThinkingIndicator() }
                        }
                    }
                }
                InputBar(
                    value = input,
                    onValueChange = { input = it },
                    onSend = {
                        val t = input.trim()
                        if (t.isNotBlank()) {
                            input = ""
                            vm.send(t, KeyStore.get(context))
                        }
                    },
                )
            }
        }
    }
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

            // New chat
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

            // Search
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
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
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
        Text(
            "How can I help?",
            style = MaterialTheme.typography.headlineSmall,
            color = c.onBackground,
        )
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = c.surfaceVariant,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = c.onBackground,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun AssistantMessage(text: String, reasoning: String) {
    val c = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        JsonMark()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (reasoning.isNotBlank()) {
                ReasoningSection(reasoning)
                Spacer(Modifier.height(8.dp))
            }
            MarkdownText(text, color = c.onBackground, modifier = Modifier.padding(top = 3.dp))
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(text)) },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(Icons.Filled.ContentCopy, "Copy", tint = c.onSurfaceVariant, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun ReasoningSection(reasoning: String) {
    val c = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp, horizontal = 2.dp),
        ) {
            Icon(painterResource(R.drawable.ic_json_spark), null, tint = c.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Thought process", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = c.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Surface(color = c.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                MarkdownText(reasoning, color = c.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        JsonMark()
        Spacer(Modifier.width(12.dp))
        val transition = rememberInfiniteTransition(label = "thinking")
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.onSurfaceVariant.copy(alpha = alpha)))
            }
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
private fun InputBar(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.background, modifier = Modifier.imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                color = c.surface,
                border = BorderStroke(1.dp, c.outline),
            ) {
                Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    if (value.isEmpty()) {
                        Text("Message Json…", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onBackground),
                        cursorBrush = SolidColor(c.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(c.primary).clickable(onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = c.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
