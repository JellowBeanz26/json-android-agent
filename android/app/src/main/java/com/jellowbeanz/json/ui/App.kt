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
    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false }, onClearData = { vm.deleteAll() })
    } else {
        ChatScreen(vm = vm, onOpenSettings = { showSettings = true })
    }
}
