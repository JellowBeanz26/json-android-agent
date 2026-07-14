package com.jellowbeanz.json.llm

import com.jellowbeanz.json.data.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * A local / self-hosted model over an OpenAI-compatible API — Ollama, LM Studio, llama.cpp, etc.,
 * running on a PC on the same network or on the phone itself (via Termux). Configured by base URL.
 */
class LocalProvider(baseUrl: String) : LlmProvider {
    override val id = "local"
    override val label = "Local model"
    override val models = emptyList<ModelOption>() // model name is free-text for local servers

    private val endpoint = normalize(baseUrl)

    override fun stream(apiKey: String, model: String, system: String, history: List<Message>): Flow<LlmChunk> = flow {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", "$system\n\n$THINK_INSTRUCTION"))
        for (m in history) {
            messages.put(
                JSONObject()
                    .put("role", if (m.role == "assistant") "assistant" else "user")
                    .put("content", m.text),
            )
        }
        val body = JSONObject()
            .put("model", model.ifBlank { "local" })
            .put("messages", messages)
            .put("stream", true)
            .toString()

        val builder = Request.Builder()
            .url(endpoint)
            .addHeader("content-type", "application/json")
            .addHeader("Accept-Encoding", "identity")
        if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey") // some servers want a token
        builder.post(body.toRequestBody("application/json".toMediaType()))

        val raw = StringBuilder()
        llmHttp.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                emit(LlmChunk("", "Couldn't reach the local model (error ${resp.code}). Check the URL in Settings."))
                return@use
            }
            val source = resp.body?.source() ?: return@use
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val json = line.substring(5).trim()
                if (json.isEmpty() || json == "[DONE]") continue
                val content = runCatching {
                    JSONObject(json).optJSONArray("choices")?.getJSONObject(0)
                        ?.optJSONObject("delta")?.optString("content")
                }.getOrNull()
                if (!content.isNullOrEmpty()) raw.append(content)
                val (r, a) = splitThink(raw.toString())
                emit(LlmChunk(r, a))
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** Accepts host:port, .../v1, or a full .../chat/completions URL. */
        private fun normalize(base: String): String {
            val u = base.trim().removeSuffix("/")
            return when {
                u.endsWith("/chat/completions") -> u
                u.endsWith("/v1") -> "$u/chat/completions"
                else -> "$u/v1/chat/completions"
            }
        }
    }
}
