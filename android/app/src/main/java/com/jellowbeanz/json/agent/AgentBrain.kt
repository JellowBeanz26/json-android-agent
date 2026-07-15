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
            "- {\"action\":\"enter\"} — submit/search the focused text field (after typing)\n" +
            "- {\"action\":\"back\"} or {\"action\":\"home\"}\n" +
            "- {\"action\":\"done\",\"summary\":\"what you accomplished\"} — when the task is complete\n" +
            "You begin from whatever is already on the screen — NOT necessarily the home screen; look before you act " +
            "(if the task is a continuation, carry on from here). " +
            "Only tap element numbers that appear in the list. To open ANY app, use open_app with its name " +
            "(e.g. {\"action\":\"open_app\",\"app\":\"Calculator\"}) from anywhere — do not hunt for its icon, and use " +
            "home or back to navigate when you need to. " +
            "To FIND something inside an app (an email, a message, a contact, a file, a setting), use the app's own " +
            "SEARCH — tap the search box or magnifier icon, type what you're looking for, and submit — instead of " +
            "scrolling a long list one screen at a time; only scroll when there is no search. " +
            "IMPORTANT: on a calculator you MUST tap the = button as the LAST step to compute the result — " +
            "even if a live preview already shows the answer — before using done. Likewise finish any other " +
            "task with its confirmation step (send / submit / OK) before done. " +
            "When you type a message or email to send, keep the VOICE and PRONOUNS consistent:\n" +
            "• DEFAULT — speak AS the owner in their own first person, as if they wrote it: natural, never " +
            "\"at my request\", never revealing an assistant wrote it.\n" +
            "• If the owner asks you to speak/sign AS Json (their AI assistant) or to introduce yourself: then " +
            "Json is \"I\", and the OWNER (named above) is THIRD person by name — write \"at <owner>'s request\", " +
            "\"the test <owner> is running\", NEVER \"at my request\" or \"the test I'm running\" for the owner's " +
            "own request or actions. Only Json's own act of sending/writing THIS message is first person. " +
            "E.g. \"Hi Tal, this is Json, <owner>'s personal AI assistant. At <owner>'s request I'm sending you " +
            "a test message, as part of the software testing <owner> is doing.\"\n" +
            "Let the owner's wording decide which voice. If the owner gave exact wording, type it verbatim."

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun nextAction(
        apiKey: String,
        model: String,
        userName: String,
        task: String,
        screen: String,
        history: String,
    ): AgentAction =
        withContext(Dispatchers.IO) {
            val userText = buildString {
                append("You are operating the phone on behalf of ")
                append(userName.ifBlank { "the user" }).append(" (the sender/owner of this phone).\n\n")
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
