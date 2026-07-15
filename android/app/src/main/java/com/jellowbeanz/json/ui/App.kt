package com.jellowbeanz.json.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jellowbeanz.json.ChatViewModel

@Composable
fun App() {
    val vm: ChatViewModel = viewModel()
    var screen by rememberSaveable { mutableStateOf("chat") }
    when (screen) {
        "settings" -> SettingsScreen(
            onBack = { screen = "chat" },
            onClearData = { vm.deleteAll() },
            onOpenDebug = { screen = "debug" },
            onOpenMemory = { screen = "memory" },
        )
        "debug" -> DebugLogScreen(onBack = { screen = "settings" })
        "memory" -> MemoryScreen(onBack = { screen = "settings" })
        else -> ChatScreen(vm = vm, onOpenSettings = { screen = "settings" })
    }
}
