package com.jellowbeanz.json.llm

import com.jellowbeanz.json.Logger
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

/** Anthropic Claude (Messages API, SSE). Uses NATIVE extended thinking — reasoning streams for real. */
object AnthropicProvider : LlmProvider {
    override val id = "anthropic"
    override val label = "Anthropic (Claude)"
    override val models = listOf(
        ModelOption("claude-sonnet-4-20250514", "Claude Sonnet 4", "Paid · \$3 / \$15 per 1M · thinks"),
        ModelOption("claude-opus-4-20250514", "Claude Opus 4", "Paid · \$15 / \$75 per 1M · deepest"),
        ModelOption("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet", "Paid · \$3 / \$15 per 1M · thinks"),
        ModelOption("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", "Paid · \$3 / \$15 per 1M"),
        ModelOption("claude-3-5-haiku-20241022", "Claude 3.5 Haiku", "Paid · \$0.80 / \$4 per 1M · fast"),
    )

    override fun stream(apiKey: String, model: String, system: String, history: List<Message>): Flow<LlmChunk> = flow {
        Logger.d("→ $label · $model")
        val messages = JSONArray()
        for (m in history) {
            messages.put(
                JSONObject()
                    .put("role", if (m.role == "assistant") "assistant" else "user")
                    .put("content", m.text),
            )
        }
        val supportsThinking = model.startsWith("claude-sonnet-4") ||
            model.startsWith("claude-opus-4") || model.contains("-3-7-")
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 3072)
            .put("system", system)
            .put("messages", messages)
            .put("stream", true)
        if (supportsThinking) {
            body.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", 1024))
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .addHeader("Accept-Encoding", "identity")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val reasoning = StringBuilder()
        val answer = StringBuilder()
        llmHttp.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.e("$label HTTP ${resp.code}: ${runCatching { resp.body?.string()?.take(400) }.getOrNull()}")
                emit(LlmChunk("", "Couldn't reach Claude (error ${resp.code}). Check your API key in Settings."))
                return@use
            }
            val source = resp.body?.source() ?: return@use
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val json = line.substring(5).trim()
                if (json.isEmpty()) continue
                val obj = runCatching { JSONObject(json) }.getOrNull() ?: continue
                if (obj.optString("type") == "content_block_delta") {
                    val delta = obj.optJSONObject("delta")
                    when (delta?.optString("type")) {
                        "thinking_delta" -> reasoning.append(delta.optString("thinking"))
                        "text_delta" -> answer.append(delta.optString("text"))
                    }
                    emit(LlmChunk(reasoning.toString(), answer.toString()))
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
