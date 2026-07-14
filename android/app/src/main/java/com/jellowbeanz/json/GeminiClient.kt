package com.jellowbeanz.json

import com.jellowbeanz.json.data.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Calls Gemini from the phone (Vertex AI generateContent endpoint), with full conversation memory. */
object GeminiClient {

    /**
     * Sends the whole [history] so the model has context, not just the last message.
     * Returns the reply text, or a friendly error string.
     */
    suspend fun chat(apiKey: String, model: String, system: String, history: List<Message>): String =
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
                    return@withContext "Couldn't reach the model (error $code). Check your API key in Settings."
                }
                parseText(response)
            } catch (e: Exception) {
                "Something went wrong: ${e.message ?: "request failed"}"
            }
        }

    private fun parseText(response: String): String {
        val candidates = JSONObject(response).optJSONArray("candidates") ?: return "(no response)"
        if (candidates.length() == 0) return "(no response)"
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: return "(no response)"
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val t = parts.getJSONObject(i).optString("text")
            if (t.isNotEmpty()) sb.append(t)
        }
        return sb.toString().trim().ifBlank { "(no response)" }
    }
}
