package com.jellowbeanz.json.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One decision from the agent: which action to take next. */
data class AgentAction(
    val thought: String = "",
    val action: String = "",
    val index: Int? = null,
    val text: String? = null,
    val app: String? = null,
    val direction: String? = null,
    val summary: String? = null,
)

/** Picks the next phone action given the task + current screen (Gemini, JSON output). */
object AgentBrain {
    private const val SYSTEM =
        "You operate an Android phone to accomplish the user's task. Each turn you are given the " +
            "current screen as a numbered list of on-screen elements. Choose ONE next action and reply " +
            "with ONLY a JSON object (no prose):\n" +
            "{\"thought\":\"brief reasoning\",\"action\":\"tap\",\"index\":N}\n" +
            "Valid actions:\n" +
            "- {\"action\":\"tap\",\"index\":N} — tap element number N\n" +
            "- {\"action\":\"type\",\"text\":\"...\"} — type into the focused text field\n" +
            "- {\"action\":\"open_app\",\"app\":\"Calculator\"} — launch an app by name\n" +
            "- {\"action\":\"swipe\",\"direction\":\"up|down|left|right\"}\n" +
            "- {\"action\":\"back\"} or {\"action\":\"home\"}\n" +
            "- {\"action\":\"done\",\"summary\":\"what you accomplished\"} — when the task is complete\n" +
            "Only tap element numbers that appear in the list. To open ANY app, use open_app with its name " +
            "(e.g. {\"action\":\"open_app\",\"app\":\"Calculator\"}) — do not hunt for its icon on the home screen. " +
            "Complete the task fully, including any final confirmation step such as pressing '=' on a " +
            "calculator or a send/submit button, before you use done."

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun nextAction(apiKey: String, model: String, task: String, screen: String, history: String): AgentAction =
        withContext(Dispatchers.IO) {
            val userText = buildString {
                append("TASK: ").append(task).append("\n\n")
                if (history.isNotBlank()) append("STEPS SO FAR:\n").append(history).append("\n")
                append("CURRENT SCREEN:\n").append(screen)
            }
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", userText))),
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

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext AgentAction(action = "done", summary = "Couldn't reach the model (error ${resp.code}).")
                }
                val text = runCatching {
                    JSONObject(resp.body!!.string())
                        .getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                        .getString("text")
                }.getOrNull() ?: return@withContext AgentAction(action = "done", summary = "No response from the model.")
                runCatching { parse(text) }.getOrElse {
                    AgentAction(action = "done", summary = "Couldn't understand the next step.")
                }
            }
        }

    private fun parse(text: String): AgentAction {
        val json = text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)
        val o = JSONObject(json)
        return AgentAction(
            thought = o.optString("thought"),
            action = o.optString("action"),
            index = if (o.has("index")) o.optInt("index") else null,
            text = o.optString("text").takeIf { it.isNotEmpty() },
            app = o.optString("app").takeIf { it.isNotEmpty() },
            direction = o.optString("direction").takeIf { it.isNotEmpty() },
            summary = o.optString("summary").takeIf { it.isNotEmpty() },
        )
    }
}
