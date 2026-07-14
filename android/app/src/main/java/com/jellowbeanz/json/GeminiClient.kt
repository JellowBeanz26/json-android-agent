package com.jellowbeanz.json

import com.jellowbeanz.json.data.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Calls Gemini from the phone (Vertex AI generateContent endpoint), with memory + reasoning. */
object GeminiClient {

    /** The model's answer plus its (optional) chain-of-thought summary. */
    data class ChatReply(val text: String, val reasoning: String)

    /**
     * Sends the whole [history] and asks the model to include a summary of its thinking.
     */
    suspend fun chat(apiKey: String, model: String, system: String, history: List<Message>): ChatReply =
        withContext(Dispatchers.IO) {
            try {
                val contents = JSONArray()
                for (m in history) {
                    val role = if (m.role == "assistant") "model" else "user"
                    contents.put(
                        JSONObject()
                            .put("role", role)
                            .put("parts", JSONArray().put(JSONObject().put("text", m.text))),
                    )
                }
                val body = JSONObject()
                    .put(
                        "systemInstruction",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))),
                    )
                    .put("contents", contents)
                    .put(
                        "generationConfig",
                        JSONObject().put("thinkingConfig", JSONObject().put("includeThoughts", true)),
                    )
                    .toString()

                val endpoint =
                    "https://aiplatform.googleapis.com/v1/publishers/google/models/$model:generateContent"
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-goog-api-key", apiKey)
                    connectTimeout = 20000
                    readTimeout = 60000
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (code !in 200..299) {
                    return@withContext ChatReply(
                        "Couldn't reach the model (error $code). Check your API key in Settings.",
                        "",
                    )
                }
                parseReply(response)
            } catch (e: Exception) {
                ChatReply("Something went wrong: ${e.message ?: "request failed"}", "")
            }
        }

    private fun parseReply(response: String): ChatReply {
        val candidates = JSONObject(response).optJSONArray("candidates")
            ?: return ChatReply("(no response)", "")
        if (candidates.length() == 0) return ChatReply("(no response)", "")
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: return ChatReply("(no response)", "")
        val answer = StringBuilder()
        val thoughts = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val t = part.optString("text")
            if (t.isEmpty()) continue
            if (part.optBoolean("thought", false)) thoughts.append(t) else answer.append(t)
        }
        return ChatReply(
            answer.toString().trim().ifBlank { "(no response)" },
            thoughts.toString().trim(),
        )
    }
}
