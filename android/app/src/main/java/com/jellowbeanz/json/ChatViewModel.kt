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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Live state while a reply is being generated (reasoning first, then the answer). */
data class Streaming(val reasoning: String = "", val answer: String = "", val active: Boolean = false)

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

    private val _streaming = MutableStateFlow(Streaming())
    val streaming: StateFlow<Streaming> = _streaming

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
        if (trimmed.isBlank() || _streaming.value.active) return
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

            _streaming.value = Streaming(active = true)
            val raw = StringBuilder()
            var done = false

            // Reveal the accumulated text at a steady, readable pace (typewriter) so the reasoning
            // plays out visibly instead of flashing past — decoupled from bursty network delivery.
            val reveal = launch {
                var shown = 0
                while (true) {
                    val target = raw.length
                    if (shown < target) {
                        val inAnswer = raw.substring(0, shown).contains("</think>")
                        val remaining = target - shown
                        val step = when {
                            remaining > 500 -> 18 // far behind: catch up
                            inAnswer -> 6 // answer: brisk
                            else -> 2 // reasoning: slow and readable
                        }
                        shown = (shown + step).coerceAtMost(target)
                        val (reasoning, answer) = splitThinking(raw.substring(0, shown))
                        _streaming.value = Streaming(reasoning, answer, active = true)
                    } else if (done) {
                        break
                    }
                    delay(16)
                }
            }

            try {
                val s = settings.snapshot()
                GeminiClient.chatStream(apiKey, s.model, SettingsStore.systemPrompt(s), repo.history(id))
                    .collect { chunk -> raw.append(chunk) }
            } catch (e: Exception) {
                if (raw.isEmpty()) raw.append("Something went wrong: ${e.message ?: "request failed"}")
            }
            done = true
            reveal.join()

            val finalAnswer = splitThinking(raw.toString()).second
                .ifBlank { raw.toString().replace("<think>", "").replace("</think>", "").trim() }
            repo.addMessage(id, "assistant", finalAnswer.ifBlank { "(no response)" }, System.currentTimeMillis())
            _streaming.value = Streaming(active = false)
        }
    }

    private fun titleFrom(text: String): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "New chat"
        return if (firstLine.length <= 40) firstLine else firstLine.take(40).trimEnd() + "…"
    }

    /**
     * The model's turn is prefilled with "<think>", so the stream is reasoning until "</think>",
     * then the answer. Returns (live reasoning, answer).
     */
    private fun splitThinking(raw: String): Pair<String, String> {
        val open = raw.indexOf("<think>")
        if (open == -1) {
            val t = raw.trimStart()
            // Hide a partial opening tag ("<th") so it doesn't flash as the answer.
            if (t.isNotEmpty() && t.length < 7 && "<think>".startsWith(t)) return "" to ""
            return "" to raw
        }
        val close = raw.indexOf("</think>", open + 7)
        if (close == -1) return raw.substring(open + 7).trim() to ""
        return raw.substring(open + 7, close).trim() to raw.substring(close + 8).trim()
    }
}
