package com.jellowbeanz.json.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Maintains a small, dense long-term memory of durable facts about the user — like ChatGPT's memory.
 *
 * [extract] pulls 0–3 genuinely lasting facts from the latest exchange (append-only, safe).
 * [consolidate] merges/dedupes/trims the whole list back under [MAX] when it grows too large —
 * the "smart solution when space runs out" rather than blindly dropping the oldest.
 *
 * Gemini-only (uses the Vertex Express generateContent endpoint), best-effort: any failure
 * returns an empty result so a memory hiccup never breaks the chat.
 */
object MemoryExtractor {
    /** Hard cap on how many facts we keep, so memory stays focused and cheap to inject. */
    const val MAX = 40

    private const val EXTRACT_SYSTEM =
        "You maintain a long-term memory of durable facts about ONE user for their personal AI " +
            "assistant. From the latest chat exchange, extract ONLY genuinely lasting, useful facts about " +
            "the USER worth remembering in future chats — e.g. their name, stable preferences, background/" +
            "profession, ongoing projects, important people or relationships, hard constraints. Do NOT store: " +
            "one-off requests, questions, trivia, the assistant's own replies, anything temporary, or facts " +
            "already listed as known. Reply with ONLY a JSON object {\"facts\":[\"...\"]} containing 0-3 " +
            "concise facts, each a short sentence in the user's own language. Nothing worth saving → {\"facts\":[]}."

    private const val CONSOLIDATE_SYSTEM =
        "You compress a personal AI assistant's long-term memory about ONE user. Given the current list " +
            "of remembered facts, return a cleaner list that merges overlapping or duplicate facts, drops " +
            "trivia / low-value / outdated entries, and keeps the most useful DURABLE facts (name, key " +
            "preferences, relationships, ongoing projects). Reply with ONLY a JSON object {\"facts\":[\"...\"]} " +
            "with AT MOST $MAX concise facts — ideally fewer. Keep each fact short, in the user's language."

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** New durable facts from this exchange, already filtered against [existing] (case-insensitive). */
    suspend fun extract(
        apiKey: String,
        model: String,
        userText: String,
        assistantText: String,
        existing: List<String>,
    ): List<String> {
        val prompt = buildString {
            if (existing.isNotEmpty()) {
                append("ALREADY KNOWN (do not repeat):\n")
                existing.forEach { append("- ").append(it).append("\n") }
                append("\n")
            }
            append("LATEST EXCHANGE:\n")
            append("User: ").append(userText.take(2000)).append("\n")
            append("Assistant: ").append(assistantText.take(2000)).append("\n\n")
            append("Extract new lasting facts about the user.")
        }
        val facts = call(apiKey, model, EXTRACT_SYSTEM, prompt)
        return facts.filter { f -> existing.none { it.equals(f, ignoreCase = true) } }
    }

    /** The whole memory, merged and trimmed to at most [MAX]. Falls back to the first [MAX] on failure. */
    suspend fun consolidate(apiKey: String, model: String, memories: List<String>): List<String> {
        val prompt = buildString {
            append("CURRENT MEMORY (").append(memories.size).append(" items):\n")
            memories.forEach { append("- ").append(it).append("\n") }
            append("\nReturn the consolidated list.")
        }
        val merged = call(apiKey, model, CONSOLIDATE_SYSTEM, prompt)
        return (merged.ifEmpty { memories }).take(MAX)
    }

    /** One Gemini generateContent round-trip that returns the "facts" string array (empty on any failure). */
    private suspend fun call(apiKey: String, model: String, system: String, user: String): List<String> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", user))),
                    ),
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                        .put("responseMimeType", "application/json"),
                )
                .toString()

            val endpoint = "https://aiplatform.googleapis.com/v1/publishers/google/models/$model:generateContent"
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("x-goog-api-key", apiKey)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            runCatching {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val text = JSONObject(resp.body!!.string())
                        .getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                        .getString("text")
                    val json = text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)
                    val arr = JSONObject(json).optJSONArray("facts") ?: return@withContext emptyList()
                    (0 until arr.length())
                        .map { arr.getString(it).trim() }
                        .filter { it.isNotBlank() }
                }
            }.getOrDefault(emptyList())
        }
}
