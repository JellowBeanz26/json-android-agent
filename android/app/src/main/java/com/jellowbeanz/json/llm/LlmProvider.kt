package com.jellowbeanz.json.llm

import com.jellowbeanz.json.data.Message
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** A model the user can pick for a provider. */
data class ModelOption(val id: String, val label: String, val note: String)

/** Cumulative snapshot of a streaming reply: reasoning-so-far + answer-so-far. */
data class LlmChunk(val reasoning: String = "", val answer: String = "")

/** One chat backend (Gemini, Claude, OpenAI …). Each maps its native protocol to [LlmChunk]. */
interface LlmProvider {
    val id: String
    val label: String
    val models: List<ModelOption>
    val defaultModel: String get() = models.first().id
    fun stream(apiKey: String, model: String, system: String, history: List<Message>): Flow<LlmChunk>
}

/** Shared OkHttp client tuned for streaming (identity encoding is set per-request). */
internal val llmHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
}

/** For providers without native streamed reasoning (Gemini, OpenAI): make the model *write* it. */
internal const val THINK_INSTRUCTION =
    "For every substantive message you MUST begin your reply with your reasoning wrapped in " +
        "<think> and </think> tags — a few short first-person sentences, in the user's language, " +
        "about how you'll answer — then write the final answer after </think>. Never skip the " +
        "<think> block for a real question; only a bare greeting like \"hi\" may skip it. " +
        "Example: <think>The user wants X. I'll cover Y and Z.</think>Here is the answer…"

/** Splits streamed text into (reasoning, answer) around <think></think> tags. */
internal fun splitThink(raw: String): Pair<String, String> {
    val open = raw.indexOf("<think>")
    if (open == -1) {
        val t = raw.trimStart()
        // Hide a partial opening tag ("<th") so it doesn't flash as the answer.
        if (t.isNotEmpty() && t.length < 7 && "<think>".startsWith(t)) return "" to ""
        return "" to raw
    }
    val close = raw.indexOf("</think>", open + 7)
    if (close == -1) return raw.substring(open + 7).trim() to ""
    return raw.substring(open + 7, close).trim() to raw.substring(close + 8).trim()
}
