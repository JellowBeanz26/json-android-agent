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

/** Google Gemini via Vertex AI Express (SSE). No native streamed reasoning, so we prompt for <think>. */
object GeminiProvider : LlmProvider {
    override val id = "google"
    override val label = "Google Gemini"
    override val models = listOf(
        ModelOption("gemini-2.5-flash", "Gemini 2.5 Flash", "Free tier · fast · ~\$0.30 / 1M out"),
        ModelOption("gemini-2.5-pro", "Gemini 2.5 Pro", "Paid · most capable · ~\$10 / 1M out"),
        ModelOption("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite", "Free tier · cheapest & fastest"),
        ModelOption("gemini-2.0-flash", "Gemini 2.0 Flash", "Free tier · fast"),
        ModelOption("gemini-2.0-flash-lite", "Gemini 2.0 Flash-Lite", "Free tier · cheapest"),
        ModelOption("gemini-1.5-pro", "Gemini 1.5 Pro", "Paid · long context (legacy)"),
        ModelOption("gemini-1.5-flash", "Gemini 1.5 Flash", "Free tier · legacy"),
    )

    override fun stream(apiKey: String, model: String, system: String, history: List<Message>): Flow<LlmChunk> = flow {
        val contents = JSONArray()
        for (m in history) {
            val role = if (m.role == "assistant") "model" else "user"
            contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", m.text))))
        }
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "$system\n\n$THINK_INSTRUCTION"))),
            )
            .put("contents", contents)
            // Disable hidden thinking so the visible <think> reasoning streams immediately.
            .put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
            .toString()

        val endpoint =
            "https://aiplatform.googleapis.com/v1/publishers/google/models/$model:streamGenerateContent?alt=sse"
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Accept-Encoding", "identity")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val raw = StringBuilder()
        llmHttp.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                emit(LlmChunk("", "Couldn't reach Gemini (error ${resp.code}). Check your API key in Settings."))
                return@use
            }
            val source = resp.body?.source() ?: return@use
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val json = line.substring(5).trim()
                if (json.isEmpty()) continue
                val parts = runCatching {
                    JSONObject(json).optJSONArray("candidates")?.getJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                }.getOrNull() ?: continue
                for (i in 0 until parts.length()) {
                    val t = parts.getJSONObject(i).optString("text")
                    if (t.isNotEmpty()) raw.append(t)
                }
                val (r, a) = splitThink(raw.toString())
                emit(LlmChunk(r, a))
            }
        }
    }.flowOn(Dispatchers.IO)
}
