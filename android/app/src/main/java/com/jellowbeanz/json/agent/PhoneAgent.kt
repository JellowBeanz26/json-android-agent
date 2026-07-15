package com.jellowbeanz.json.agent

import com.jellowbeanz.json.JsonAccessibilityService
import com.jellowbeanz.json.Logger

/** The observe → think → act loop that drives the phone via the accessibility service. */
object PhoneAgent {
    private const val INITIAL_STEPS = 40 // steps before the first self-extension checkpoint
    private const val EXTENSION_MAX = 20 // most the agent may grant itself at one checkpoint
    private const val HARD_MAX = 150 // absolute ceiling even with self-extension (Stop is always available)

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
            val stoppedMessage =
                "You stopped me, so I halted here. Everything I did up to this point is above — " +
                    "ask me about any of it, or tell me to continue."
            val history = StringBuilder()
            val notes = StringBuilder()
            var prevScreen = ""
            var prevKey = ""
            var repeatCount = 0
            var lastWasNote = false
            var budget = INITIAL_STEPS
            var step = 0
            while (step < budget) {
                if (service.stopRequested) return stoppedMessage

                // Read the screen, retrying briefly if we caught it mid-transition (empty tree).
                var elements = service.readScreen()
                var probe = 0
                while (elements.isEmpty() && probe < 3) {
                    service.waitUntilIdle(quietMs = 300, maxMs = 1000)
                    elements = service.readScreen()
                    probe++
                }
                val screen = service.renderScreen(elements)

                // If the last *phone* action changed nothing, tell the model — it knows the task, so it can judge
                // whether that's expected (a deliberate revisit) or a cue to try a different approach. A note never
                // touches the phone, so don't flag an unchanged screen after one.
                if (step > 0 && !lastWasNote && screen == prevScreen) {
                    history.append("   (note: the previous step did not change the screen)\n")
                }

                val action = AgentBrain.nextAction(apiKey, model, userName, task, screen, history.toString(), notes.toString())
                Logger.d("agent ${step + 1}: ${action.action} ${action.index ?: action.app ?: action.text ?: action.direction ?: ""}")

                if (service.stopRequested) return stoppedMessage
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

                val desc: String
                if (action.action == "note") {
                    // Record what the model read on screen, to synthesize later — no phone interaction.
                    val n = action.text.orEmpty().trim()
                    if (n.isNotEmpty()) notes.append("- ").append(n).append("\n")
                    desc = if (n.isEmpty()) "Noted." else "Noted: $n"
                    lastWasNote = true
                } else {
                    desc = try {
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
                    lastWasNote = false
                }
                onAction(desc)
                history.append("${step + 1}. ${action.action}: $desc\n")

                // Adaptive settle for real actions; a note changed nothing, so move straight on.
                when (action.action) {
                    "note" -> {}
                    "open_app" -> service.waitUntilIdle(quietMs = 500, maxMs = 2800)
                    else -> service.waitUntilIdle(quietMs = 350, maxMs = 1600)
                }

                step++

                // Self-extension checkpoint: at the budget edge, the agent decides whether to grant itself more.
                if (step >= budget && budget < HARD_MAX) {
                    if (service.stopRequested) return stoppedMessage
                    val decision = AgentBrain.reviewProgress(apiKey, model, task, history.toString(), notes.toString(), step)
                    if (!decision.cont) {
                        return decision.reason.ifBlank { "I stopped here; the task may be only partly done." }
                    }
                    val add = decision.steps.coerceIn(1, EXTENSION_MAX)
                    budget = (budget + add).coerceAtMost(HARD_MAX)
                    onAction("Continuing — giving myself $add more steps (${decision.reason}).")
                }
            }
            return "I reached my hard limit of $HARD_MAX steps and stopped so I don't run on forever. " +
                "Everything I did is above — tell me to continue if the task isn't done."
        } finally {
            service.hideControlOverlay()
        }
    }
}
