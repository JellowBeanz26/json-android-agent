package com.jellowbeanz.json

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** One on-screen element — the Kotlin twin of the Python agent's Element. */
data class Element(
    val index: Int,
    val text: String,
    val desc: String,
    val className: String,
    val clickable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val bounds: Rect,
) {
    val label: String get() = text.ifBlank { desc.ifBlank { className.substringAfterLast('.') } }
}

/** The app's eyes and hands: reads the accessibility tree and performs actions. */
class JsonAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* unused */ }

    override fun onInterrupt() { /* required */ }

    // ---- eyes ----

    fun readScreen(): List<Element> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<Element>()
        walk(root, elements)
        return elements
    }

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<Element>) {
        if (node == null) return
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (node.isClickable || text.isNotBlank() || desc.isNotBlank()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out.add(
                Element(
                    index = out.size,
                    text = text,
                    desc = desc,
                    className = node.className?.toString().orEmpty(),
                    clickable = node.isClickable,
                    checkable = node.isCheckable,
                    checked = node.isChecked,
                    bounds = rect,
                ),
            )
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), out)
    }

    /** A numbered element list for the model to reason over. */
    fun renderScreen(elements: List<Element>): String {
        if (elements.isEmpty()) return "(no interactive elements found)"
        return elements.joinToString("\n") { e ->
            val kind = if (e.clickable) "clickable" else "info"
            val state = if (e.checkable) (if (e.checked) " [ON]" else " [OFF]") else ""
            "[${e.index}] ${e.className.substringAfterLast('.')} \"${e.label}\" ($kind)$state"
        }
    }

    // ---- hands ----

    fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build(),
            null,
            null,
        )
    }

    fun tapIndex(elements: List<Element>, index: Int): String {
        val e = elements.getOrNull(index) ?: return "No element with index $index."
        tap(e.bounds.centerX(), e.bounds.centerY())
        return "Tapped [$index] ${e.label}"
    }

    fun typeText(text: String): String {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "No text field is focused (tap one first)."
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return "Typed \"$text\""
    }

    fun swipe(direction: String): String {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val dx = dm.widthPixels / 3f
        val dy = dm.heightPixels / 3f
        val path = Path()
        when (direction) {
            "up" -> { path.moveTo(cx, cy - dy); path.lineTo(cx, cy + dy) }
            "left" -> { path.moveTo(cx + dx, cy); path.lineTo(cx - dx, cy) }
            "right" -> { path.moveTo(cx - dx, cy); path.lineTo(cx + dx, cy) }
            else -> { path.moveTo(cx, cy + dy); path.lineTo(cx, cy - dy) } // "down"
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build(),
            null,
            null,
        )
        return "Swiped $direction"
    }

    fun press(key: String): String = when (key) {
        "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); "Pressed back" }
        "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); "Pressed home" }
        else -> "Unknown key: $key"
    }

    fun openApp(query: String): String {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull { pm.getApplicationLabel(it).toString().contains(query, ignoreCase = true) }
            ?: apps.firstOrNull { it.packageName.contains(query, ignoreCase = true) }
            ?: return "No installed app matching \"$query\"."
        val intent = pm.getLaunchIntentForPackage(match.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return "Can't launch ${match.packageName}."
        startActivity(intent)
        return "Opened ${pm.getApplicationLabel(match)}"
    }

    companion object {
        @Volatile
        var instance: JsonAccessibilityService? = null
            private set
    }
}
