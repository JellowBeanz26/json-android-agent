package com.jellowbeanz.json.agent

import com.jellowbeanz.json.JsonAccessibilityService
import com.jellowbeanz.json.Logger
import kotlinx.coroutines.delay

/** The observe → think → act loop that drives the phone via the accessibility service. */
object PhoneAgent {
    private const val MAX_STEPS = 12

    /** [onAction] streams each step's description to the UI. Returns the final summary. */
    suspend fun run(
        apiKey: String,
        model: String,
        task: String,
        service: JsonAccessibilityService,
        onAction: suspend (String) -> Unit,
    ): String {
        service.showControlOverlay()
        try {
            service.press("home")
            delay(900)
            val history = StringBuilder()
            repeat(MAX_STEPS) { step ->
                val elements = service.readScreen()
                val screen = service.renderScreen(elements)
                val action = AgentBrain.nextAction(apiKey, model, task, screen, history.toString())
                Logger.d("agent ${step + 1}: ${action.action} ${action.index ?: action.app ?: action.text ?: action.direction ?: ""}")

                if (action.action == "done") return action.summary ?: "Done."
                val desc = try {
                    when (action.action) {
                        "tap" -> service.tapIndex(elements, action.index ?: -1)
                        "type" -> service.typeText(action.text ?: "")
                        "open_app" -> service.openApp(action.app ?: "")
                        "swipe" -> service.swipe(action.direction ?: "down")
                        "back" -> service.press("back")
                        "home" -> service.press("home")
                        else -> "Unknown action: ${action.action}"
                    }
                } catch (e: Exception) {
                    Logger.e("action ${action.action} failed", e)
                    "That step failed (${e.message}); I'll try another way."
                }
                onAction(desc)
                history.append("${step + 1}. ${action.action}: $desc\n")
                delay(1300)
            }
            return "I reached the step limit — the task may be only partly done."
        } finally {
            service.hideControlOverlay()
        }
    }
}
