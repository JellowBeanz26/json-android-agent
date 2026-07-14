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

/** OpenAI (Chat Completions, SSE). No exposed reasoning stream, so we prompt for visible <think>. */
object OpenAiProvider : LlmProvider {
    override val id = "openai"
    override val label = "OpenAI"
    override val models = listOf(
        ModelOption("gpt-4o", "GPT-4o", "Fast and capable"),
        ModelOption("gpt-4o-mini", "GPT-4o mini", "Lightweight and cheap"),
    )

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
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .toString()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("content-type", "application/json")
            .addHeader("Accept-Encoding", "identity")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val raw = StringBuilder()
        llmHttp.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                emit(LlmChunk("", "Couldn't reach OpenAI (error ${resp.code}). Check your API key in Settings."))
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
}
