package com.jellowbeanz.json.agent

import com.jellowbeanz.json.JsonAccessibilityService
import com.jellowbeanz.json.Logger

/** The observe → think → act loop that drives the phone via the accessibility service. */
object PhoneAgent {
    private const val MAX_STEPS = 16

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
            service.press("home")
            service.waitUntilIdle(quietMs = 400, maxMs = 2000)
            val history = StringBuilder()
            var prevScreen = ""
            var prevKey = ""
            var stuck = 0
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
                val action = AgentBrain.nextAction(apiKey, model, userName, task, screen, history.toString())
                Logger.d("agent ${step + 1}: ${action.action} ${action.index ?: action.app ?: action.text ?: action.direction ?: ""}")

                if (action.action == "done") return action.summary ?: "Done."

                // Stuck guard: if the model repeats the same action on an unchanged screen, stop rather than flail.
                val key = "${action.action}|${action.index}|${action.app}|${action.text}|${action.direction}"
                if (screen == prevScreen && key == prevKey) stuck++ else stuck = 0
                if (stuck >= 2) return "I kept repeating the same step without making progress, so I stopped — the task may be only partly done."
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
            return "I reached the step limit — the task may be only partly done."
        } finally {
            service.hideControlOverlay()
        }
    }
}
