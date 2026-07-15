package com.jellowbeanz.json.agent

import com.jellowbeanz.json.JsonAccessibilityService
import com.jellowbeanz.json.Logger

/** The observe → think → act loop that drives the phone via the accessibility service. */
object PhoneAgent {
    // A generous backstop, not the real safety net — the progress guards below stop a runaway far sooner.
    private const val MAX_STEPS = 30

    /** [onAction] streams each step's description to the UI. Returns the final summary. */
    suspend fun run(
        apiKey: String,
        model: String,
        userName: String,
        task: String,
        service: JsonAccessibilityService,
        onAction: suspend (String) -> Unit,
    ): String {
        service.showControlOverlay()
        try {
            service.waitUntilIdle(quietMs = 300, maxMs = 1200) // let the current screen settle before the first look
            val history = StringBuilder()
            var prevScreen = ""
            var prevKey = ""
            var repeatCount = 0
            repeat(MAX_STEPS) { step ->
                // Read the screen, retrying briefly if we caught it mid-transition (empty tree).
                var elements = service.readScreen()
                var probe = 0
                while (elements.isEmpty() && probe < 3) {
                    service.waitUntilIdle(quietMs = 300, maxMs = 1000)
                    elements = service.readScreen()
                    probe++
                }
                val screen = service.renderScreen(elements)

                // If the last action changed nothing, tell the model — it knows the task, so it can judge whether
                // that's expected (a deliberate revisit / repeated action) or a cue to try a different approach.
                if (step > 0 && screen == prevScreen) {
                    history.append("   (note: the previous step did not change the screen)\n")
                }

                val action = AgentBrain.nextAction(apiKey, model, userName, task, screen, history.toString())
                Logger.d("agent ${step + 1}: ${action.action} ${action.index ?: action.app ?: action.text ?: action.direction ?: ""}")

                if (action.action == "done") return action.summary ?: "Done."

                // The only heuristic hard stop: the EXACT same action on the EXACT same screen several times over —
                // unambiguously stuck, with the model getting no feedback either. Revisiting a screen or cycling
                // between apps never trips this, because the screen differs from one step to the next.
                val key = "${action.action}|${action.index}|${action.app}|${action.text}|${action.direction}"
                repeatCount = if (screen == prevScreen && key == prevKey) repeatCount + 1 else 0
                if (repeatCount >= 3) {
                    return "I repeated the same step several times with no effect, so I stopped — " +
                        "the task may be only partly done. Tell me to continue and I'll pick up from here."
                }
                prevScreen = screen
                prevKey = key

                val desc = try {
                    when (action.action) {
                        "tap" -> service.tapIndex(elements, action.index ?: -1)
                        "type" -> service.typeText(action.text ?: "")
                        "open_app" -> service.openApp(action.app ?: "")
                        "swipe" -> service.swipe(action.direction ?: "down")
                        "back" -> service.press("back")
                        "home" -> service.press("home")
                        "enter" -> service.press("enter")
                        else -> "Unknown action: ${action.action}"
                    }
                } catch (e: Exception) {
                    Logger.e("action ${action.action} failed", e)
                    "That step failed (${e.message}); I'll try another way."
                }
                onAction(desc)
                history.append("${step + 1}. ${action.action}: $desc\n")

                // Adaptive settle: launching an app needs longer to load than a tap; both return early once quiet.
                if (action.action == "open_app") service.waitUntilIdle(quietMs = 500, maxMs = 2800)
                else service.waitUntilIdle(quietMs = 350, maxMs = 1600)
            }
            return "I've taken $MAX_STEPS steps, so I paused here rather than running on indefinitely. " +
                "If the task isn't finished, tell me to continue and I'll pick up from this screen."
        } finally {
            service.hideControlOverlay()
        }
    }
}
