package com.jellowbeanz.json

import com.jellowbeanz.json.data.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Streams Gemini's reply token-by-token from the phone (Vertex AI streamGenerateContent, SSE). */
object GeminiClient {

    /**
     * Emits raw text chunks as they are generated. The model is prompted to write its reasoning
     * first (inside <think></think>), so the reasoning streams live before the answer.
     */
    fun chatStream(apiKey: String, model: String, system: String, history: List<Message>): Flow<String> = flow {
        val contents = JSONArray()
        for (m in history) {
            val role = if (m.role == "assistant") "model" else "user"
            contents.put(
                JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", m.text))),
            )
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            // Disable the model's own hidden thinking; we want it to *write* its reasoning so it streams.
            .put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
            .toString()

        val endpoint =
            "https://aiplatform.googleapis.com/v1/publishers/google/models/$model:streamGenerateContent?alt=sse"
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Accept-Encoding", "identity") // no gzip, so lines arrive live
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        streamClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                emit("Couldn't reach the model (error ${response.code}). Check your API key in Settings.")
                return@use
            }
            val source = response.body?.source() ?: return@use
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
                    if (t.isNotEmpty()) emit(t)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private val streamClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
