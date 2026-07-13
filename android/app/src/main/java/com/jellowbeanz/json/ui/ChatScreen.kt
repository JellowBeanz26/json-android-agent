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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jellowbeanz.json.GeminiClient
import com.jellowbeanz.json.KeyStore
import kotlinx.coroutines.launch

enum class Role { USER, ASSISTANT, ACTION, THINKING }
data class Message(val role: Role, val text: String)

@Composable
fun ChatScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val messages = remember {
        mutableStateListOf(
            Message(Role.ASSISTANT, "Hi — I'm Json. Ask me anything, or tell me what to do on your phone."),
        )
    }
    var input by remember { mutableStateOf("") }

    // keep the newest message in view
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { JsonTopBar(onOpenSettings) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(messages) { msg ->
                    when (msg.role) {
                        Role.USER -> UserBubble(msg.text)
                        Role.ASSISTANT -> AssistantMessage(msg.text)
                        Role.ACTION -> ActionCard(msg.text)
                        Role.THINKING -> ThinkingIndicator()
                    }
                }
            }
            InputBar(
                value = input,
                onValueChange = { input = it },
                onSend = {
                    val text = input.trim()
                    if (text.isNotBlank()) {
                        input = ""
                        messages.add(Message(Role.USER, text))
                        val key = KeyStore.get(context)
                        if (key.isBlank()) {
                            messages.add(
                                Message(
                                    Role.ASSISTANT,
                                    "I need an API key first — tap the settings icon (top right) to add one.",
                                ),
                            )
                        } else {
                            val thinking = Message(Role.THINKING, "")
                            messages.add(thinking)
                            scope.launch {
                                val reply = GeminiClient.ask(key, text)
                                messages.remove(thinking)
                                messages.add(Message(Role.ASSISTANT, reply))
                            }
                        }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JsonTopBar(onOpenSettings: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column {
        TopAppBar(
            title = { Text("Json", style = MaterialTheme.typography.titleLarge, color = c.onBackground) },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = c.onSurfaceVariant)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = c.background),
        )
        HorizontalDivider(color = c.outline.copy(alpha = 0.6f))
    }
}

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
private fun AssistantMessage(text: String) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        JsonMark()
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = c.onBackground,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun ActionCard(text: String) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.padding(start = 40.dp)) {
        Surface(color = c.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, c.outline)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(c.primary))
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
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
        Text("J", style = MaterialTheme.typography.labelLarge, color = c.onPrimary)
    }
}

@Composable
private fun InputBar(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.background) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
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
