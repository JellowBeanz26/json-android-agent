package com.jellowbeanz.json

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
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

/**
 * The app's eyes and hands: reads the accessibility tree and performs gestures.
 * A companion instance lets the rest of the app reach the running service.
 */
class JsonAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* unused for now */ }

    override fun onInterrupt() { /* required override */ }

    /** The "eyes": the current screen as a flat list of meaningful elements. */
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

    /** The "hands": tap a screen coordinate via a dispatched gesture. */
    fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    companion object {
        @Volatile
        var instance: JsonAccessibilityService? = null
            private set
    }
}
