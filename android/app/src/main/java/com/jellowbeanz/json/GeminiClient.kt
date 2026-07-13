package com.jellowbeanz.json

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Calls Gemini from the phone (Vertex AI generateContent endpoint). */
object GeminiClient {
    private const val ENDPOINT =
        "https://aiplatform.googleapis.com/v1/publishers/google/models/gemini-2.5-flash:generateContent"
    private const val SYSTEM =
        "You are Json, a helpful AI assistant living on the user's Android phone. " +
            "Keep replies short, warm, and natural."

    suspend fun ask(apiKey: String, prompt: String): String = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))),
                )
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                    ),
                )
                .toString()

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
                connectTimeout = 20000
                readTimeout = 40000
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                return@withContext "Couldn't reach the model (error $code). Check your API key in Settings."
            }
            JSONObject(response)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getString("text").trim()
        } catch (e: Exception) {
            "Something went wrong: ${e.message ?: "request failed"}"
        }
    }
}
