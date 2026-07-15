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
    val url: String? = null,
    val number: String? = null,
    val query: String? = null,
    val recipient: String? = null,
    val subject: String? = null,
    val body: String? = null,
)

/** The agent's own call at a step checkpoint: extend and keep going, or stop. */
data class ContinueDecision(
    val cont: Boolean,
    val steps: Int,
    val reason: String,
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
            "- {\"action\":\"open_url\",\"url\":\"https://…\"} — open a web page or search (shortcut)\n" +
            "- {\"action\":\"dial\",\"number\":\"…\"} — open the dialer with a number (shortcut)\n" +
            "- {\"action\":\"maps\",\"query\":\"place or address\"} — open maps (shortcut)\n" +
            "- {\"action\":\"email\",\"to\":\"…\",\"subject\":\"…\",\"body\":\"…\"} — open a pre-filled email (shortcut)\n" +
            "- {\"action\":\"whatsapp\",\"number\":\"+972…\",\"text\":\"…\"} — open a WhatsApp chat pre-filled (shortcut, needs a phone number)\n" +
            "- {\"action\":\"swipe\",\"direction\":\"up|down|left|right\"}\n" +
            "- {\"action\":\"enter\"} — submit/search the focused text field (after typing)\n" +
            "- {\"action\":\"back\"} or {\"action\":\"home\"}\n" +
            "- {\"action\":\"note\",\"text\":\"...\"} — remember something you READ on screen, to use later (does not touch the phone)\n" +
            "- {\"action\":\"done\",\"summary\":\"what you did, or the answer/summary the user asked for\"} — when the task is complete\n" +
            "You start on the phone's home screen — the Json assistant app that gave you this task is now in the " +
            "background. NEVER operate the Json app itself (its chat, its input box, its Send button); always open " +
            "the target app with open_app and do the work there. " +
            "Only tap element numbers that appear in the list. To open ANY app, use open_app with its name " +
            "(e.g. {\"action\":\"open_app\",\"app\":\"Calculator\"}) from anywhere — do not hunt for its icon, and use " +
            "home or back to navigate when you need to. " +
            "SHORTCUTS: for these common actions PREFER the direct shortcut (open_url, dial, maps, email, whatsapp) — " +
            "it jumps straight to the app already filled in, skipping navigation. If a shortcut can't run, its result " +
            "will say so and you then open the app and do it by hand instead. After a shortcut opens the app, still " +
            "finish with the confirmation step (tap Send / Call). whatsapp needs a phone NUMBER; if you only have a " +
            "name, open WhatsApp and search the contact. " +
            "To FIND something inside an app (an email, a message, a contact, a file, a setting), use the app's own " +
            "SEARCH: tap the search box or magnifier icon, type your query, then use the enter action to RUN the " +
            "search. Read the results only AFTER they load — never swipe or scroll before you have run the search " +
            "with enter; typing alone does not run it. Prefer search over scrolling a long list one screen at a time. " +
            "When the task is to GATHER or SUMMARIZE information across several items (e.g. read the last 10 emails " +
            "and summarize what each one charges, flagging duplicate or unusual expenses): open each item, read the " +
            "text shown, use note to record the key facts, go back, and repeat for the next item. Be THOROUGH — when " +
            "the task is to find or summarize ALL of something, try several relevant search terms and scroll through " +
            "the results, and keep opening and noting items until you have genuinely covered the recent/relevant " +
            "emails; do NOT stop after only the first few. Use done ONLY once the whole task is finished, and then put " +
            "a COMPLETE, organized summary of everything you found in done's summary. Your notes are given back to you " +
            "every turn, so record everything you'll need before moving on. " +
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

    private const val REVIEW_SYSTEM =
        "You are operating an Android phone to accomplish a task and have reached a step checkpoint. Decide " +
            "whether to keep going. Reply with ONLY a JSON object (no prose): " +
            "{\"continue\":true,\"steps\":10,\"reason\":\"...\"}. Set continue=true ONLY if the task is genuinely " +
            "still making progress and you can finish it with more steps — set steps to how many more you need " +
            "(5-20). Set continue=false if the task is already done, is stuck, or is looping without progress; in " +
            "reason, give a short wrap-up of what you accomplished."

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
        context: String,
        screen: String,
        history: String,
        notes: String,
    ): AgentAction {
        val userText = buildString {
            append("You are operating the phone on behalf of ")
            append(userName.ifBlank { "the user" }).append(" (the sender/owner of this phone).\n\n")
            if (context.isNotBlank()) {
                append("EARLIER IN THIS CHAT (for reference — e.g. if the task says \"continue\"):\n")
                append(context).append("\n\n")
            }
            append("TASK: ").append(task).append("\n\n")
            if (history.isNotBlank()) append("STEPS SO FAR:\n").append(history).append("\n")
            if (notes.isNotBlank()) append("NOTES YOU'VE TAKEN:\n").append(notes).append("\n")
            append("CURRENT SCREEN:\n").append(screen)
        }
        val text = callGemini(apiKey, model, SYSTEM, userText)
            ?: return AgentAction(action = "done", summary = "Couldn't reach the model.")
        return runCatching { parse(text) }
            .getOrElse { AgentAction(action = "done", summary = "Couldn't understand the next step.") }
    }

    /** At a step checkpoint, the agent decides for itself whether to extend and by how much. */
    suspend fun reviewProgress(
        apiKey: String,
        model: String,
        task: String,
        history: String,
        notes: String,
        stepsUsed: Int,
    ): ContinueDecision {
        val user = buildString {
            append("TASK: ").append(task).append("\n\n")
            append("You have used ").append(stepsUsed).append(" steps so far.\n\n")
            if (history.isNotBlank()) append("STEPS SO FAR:\n").append(history).append("\n")
            if (notes.isNotBlank()) append("NOTES YOU'VE TAKEN:\n").append(notes).append("\n")
            append("Should you continue?")
        }
        val text = callGemini(apiKey, model, REVIEW_SYSTEM, user)
            ?: return ContinueDecision(false, 0, "I couldn't check whether to keep going, so I stopped.")
        return runCatching {
            val json = text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)
            val o = JSONObject(json)
            ContinueDecision(o.optBoolean("continue", false), o.optInt("steps", 10), o.optString("reason"))
        }.getOrElse { ContinueDecision(false, 0, "I stopped at the checkpoint.") }
    }

    /** One Gemini generateContent round-trip (thinking off, JSON out). Returns the text, or null on failure. */
    private suspend fun callGemini(apiKey: String, model: String, system: String, user: String): String? =
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
                    if (!resp.isSuccessful) return@withContext null
                    JSONObject(resp.body!!.string())
                        .getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                        .getString("text")
                }
            }.getOrNull()
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
            url = o.optString("url").takeIf { it.isNotEmpty() },
            number = o.optString("number").takeIf { it.isNotEmpty() },
            query = o.optString("query").takeIf { it.isNotEmpty() },
            recipient = o.optString("to").takeIf { it.isNotEmpty() },
            subject = o.optString("subject").takeIf { it.isNotEmpty() },
            body = o.optString("body").takeIf { it.isNotEmpty() },
        )
    }
}
