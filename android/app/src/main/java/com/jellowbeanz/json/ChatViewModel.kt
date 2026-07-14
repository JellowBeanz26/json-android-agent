package com.jellowbeanz.json

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jellowbeanz.json.data.ChatRepository
import com.jellowbeanz.json.data.Conversation
import com.jellowbeanz.json.data.JsonDatabase
import com.jellowbeanz.json.data.Message
import com.jellowbeanz.json.data.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Holds all chat state and drives persistence + the model. */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ChatRepository(JsonDatabase.get(app).chatDao())
    private val settings = SettingsStore(app)

    /** All saved conversations (pinned first, then most-recent). */
    val conversations: StateFlow<List<Conversation>> =
        repo.conversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentId = MutableStateFlow<Long?>(null)
    val currentId: StateFlow<Long?> = _currentId

    /** Messages of the open conversation; switches automatically when currentId changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> =
        _currentId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repo.messages(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking

    fun newChat() { _currentId.value = null }

    fun select(id: Long) { _currentId.value = id }

    fun rename(id: Long, title: String) = viewModelScope.launch {
        if (title.isNotBlank()) repo.rename(id, title.trim())
    }

    fun togglePin(c: Conversation) = viewModelScope.launch { repo.setPinned(c.id, !c.pinned) }

    fun delete(id: Long) = viewModelScope.launch {
        repo.delete(id)
        if (_currentId.value == id) _currentId.value = null
    }

    fun deleteAll() = viewModelScope.launch {
        repo.deleteAll()
        _currentId.value = null
    }

    fun send(text: String, apiKey: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || _thinking.value) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            var id = _currentId.value
            if (id == null) {
                id = repo.createConversation(titleFrom(trimmed), now)
                _currentId.value = id
            }
            repo.addMessage(id, "user", trimmed, now)

            if (apiKey.isBlank()) {
                repo.addMessage(
                    id,
                    "assistant",
                    "I need an API key first — open the menu → Settings and paste one.",
                    System.currentTimeMillis(),
                )
                return@launch
            }

            _thinking.value = true
            val reply = try {
                val s = settings.snapshot()
                GeminiClient.chat(apiKey, s.model, SettingsStore.systemPrompt(s), repo.history(id))
            } catch (e: Exception) {
                "Something went wrong: ${e.message ?: "request failed"}"
            }
            repo.addMessage(id, "assistant", reply, System.currentTimeMillis())
            _thinking.value = false
        }
    }

    private fun titleFrom(text: String): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "New chat"
        return if (firstLine.length <= 40) firstLine else firstLine.take(40).trimEnd() + "…"
    }
}
