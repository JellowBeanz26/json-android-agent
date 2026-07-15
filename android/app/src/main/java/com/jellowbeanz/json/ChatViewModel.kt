package com.jellowbeanz.json

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jellowbeanz.json.data.ChatRepository
import com.jellowbeanz.json.data.Conversation
import com.jellowbeanz.json.data.JsonDatabase
import com.jellowbeanz.json.data.Message
import com.jellowbeanz.json.data.Settings
import com.jellowbeanz.json.data.SettingsStore
import com.jellowbeanz.json.agent.PhoneAgent
import com.jellowbeanz.json.memory.MemoryExtractor
import com.jellowbeanz.json.llm.GeminiProvider
import com.jellowbeanz.json.llm.Llm
import com.jellowbeanz.json.llm.LocalProvider
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

    /** When on, messages are treated as tasks to perform on the phone (not chat). */
    private val _agentMode = MutableStateFlow(false)
    val agentMode: StateFlow<Boolean> = _agentMode
    fun setAgentMode(on: Boolean) { _agentMode.value = on }

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

            if (_agentMode.value) {
                runAgent(id, trimmed, apiKey)
                return@launch
            }

            val s = settings.snapshot()
            val useLocal = s.useLocal && s.localUrl.isNotBlank()
            if (!useLocal && apiKey.isBlank()) {
                repo.addMessage(
                    id,
                    "assistant",
                    "I need an API key first — open the menu → Settings and paste one, or turn on a Local model.",
                    System.currentTimeMillis(),
                )
                return@launch
            }

            _streaming.value = Streaming(active = true)
            var targetR = ""
            var targetA = ""
            var done = false

            // Typewriter reveal: play the reasoning first (slow, readable), then the answer (brisk),
            // decoupled from bursty network delivery so nothing flashes past.
            val reveal = launch {
                var shownR = 0
                var shownA = 0
                while (true) {
                    var changed = false
                    if (shownR < targetR.length) {
                        shownR = (shownR + if (targetR.length - shownR > 160) 10 else 2).coerceAtMost(targetR.length)
                        changed = true
                    } else if (shownA < targetA.length) {
                        shownA = (shownA + if (targetA.length - shownA > 400) 18 else 5).coerceAtMost(targetA.length)
                        changed = true
                    }
                    if (changed) {
                        _streaming.value = Streaming(targetR.take(shownR), targetA.take(shownA), active = true)
                    } else if (done) {
                        break
                    }
                    delay(16)
                }
            }

            try {
                val provider = if (useLocal) LocalProvider(s.localUrl) else Llm.forKey(apiKey)
                if (provider == null) {
                    targetA = "I don't recognize that API key. Add a Gemini, Claude, or OpenAI key in Settings, or turn on a Local model."
                } else {
                    val chosenModel = if (provider is LocalProvider) s.localModel else Llm.resolveModel(provider, s.model)
                    Logger.d("send · ${provider.label} · $chosenModel")
                    provider.stream(apiKey, chosenModel, SettingsStore.systemPrompt(s), repo.history(id))
                        .collect { chunk ->
                            targetR = chunk.reasoning
                            targetA = chunk.answer
                        }
                }
            } catch (e: Exception) {
                Logger.e("stream failed", e)
                if (targetA.isEmpty()) targetA = "Something went wrong: ${e.message ?: "request failed"}"
            }
            done = true
            reveal.join()

            repo.addMessage(id, "assistant", targetA.ifBlank { "(no response)" }, System.currentTimeMillis())
            _streaming.value = Streaming(active = false)
            maybeRemember(trimmed, targetA, apiKey, s)
        }
    }

    /**
     * After a normal reply, quietly learn durable facts about the user for long-term memory.
     * Best-effort and Gemini-only; when memory grows past the cap it consolidates instead of
     * blindly dropping the oldest. Never blocks or breaks the chat.
     */
    private fun maybeRemember(userText: String, assistantText: String, apiKey: String, s: Settings) {
        if (!s.memoryEnabled || s.useLocal || assistantText.isBlank()) return
        if (Llm.forKey(apiKey) != GeminiProvider) return // extractor uses the Gemini endpoint
        viewModelScope.launch {
            val existing = settings.snapshot().memories
            val facts = MemoryExtractor.extract(apiKey, "gemini-2.5-flash", userText, assistantText, existing)
            if (facts.isEmpty()) return@launch
            facts.forEach { settings.addMemory(it) }
            val after = settings.snapshot().memories
            if (after.size > MemoryExtractor.MAX) {
                val merged = MemoryExtractor.consolidate(apiKey, "gemini-2.5-flash", after)
                settings.clearMemories()
                merged.forEach { settings.addMemory(it) }
            }
            Logger.d("memory: learned ${facts.size}, ${settings.snapshot().memories.size} total")
        }
    }

    /** Drives the phone via the accessibility service, streaming each step as an "action" message. */
    private suspend fun runAgent(convId: Long, task: String, apiKey: String) {
        val service = JsonAccessibilityService.instance
        if (service == null) {
            repo.addMessage(
                convId,
                "assistant",
                "Turn on my accessibility service first — Settings → Accessibility → Json — so I can see and control the screen.",
                System.currentTimeMillis(),
            )
            return
        }
        if (Llm.forKey(apiKey) != GeminiProvider) {
            repo.addMessage(convId, "assistant", "Phone control currently needs a Google Gemini key.", System.currentTimeMillis())
            return
        }
        _streaming.value = Streaming(active = true)
        val name = settings.snapshot().userName
        val summary = try {
            PhoneAgent.run(apiKey, "gemini-2.5-flash", name, task, service) { desc ->
                repo.addMessage(convId, "action", desc, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Logger.e("agent failed", e)
            "Something went wrong: ${e.message ?: "the agent stopped"}"
        }
        _streaming.value = Streaming(active = false)
        repo.addMessage(convId, "assistant", summary, System.currentTimeMillis())
    }

    private fun titleFrom(text: String): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "New chat"
        return if (firstLine.length <= 40) firstLine else firstLine.take(40).trimEnd() + "…"
    }
}
