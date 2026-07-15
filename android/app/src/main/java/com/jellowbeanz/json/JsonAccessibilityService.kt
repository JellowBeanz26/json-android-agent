package com.jellowbeanz.json

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView

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
        overlay?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        overlay = null
        if (instance === this) instance = null
    }

    // ---- "Json is controlling your phone" overlay (the visible sign) ----

    private var overlay: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun showControlOverlay() = mainHandler.post {
        if (overlay != null) return@post
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val accent = 0xFFD97757.toInt()

        val root = FrameLayout(this)
        root.background = GradientDrawable().apply {
            setStroke(dp(4), accent)
            cornerRadius = dp(22).toFloat()
            setColor(0x00000000)
        }
        val pill = TextView(this).apply {
            text = "✦  Json is controlling your phone"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(22).toFloat() }
        }
        root.addView(
            pill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(52) },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching {
            wm.addView(root, params)
            overlay = root
        }
    }

    fun hideControlOverlay() = mainHandler.post {
        overlay?.let { v -> runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } }
        overlay = null
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
            // Only keep elements that are actually on-screen (avoid off-screen/negative bounds).
            if (rect.width() > 0 && rect.height() > 0 && rect.left >= 0 && rect.top >= 0) {
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
        if (x < 0 || y < 0) return
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
        val x = e.bounds.centerX()
        val y = e.bounds.centerY()
        if (x < 0 || y < 0) return "Element $index is off-screen."
        tap(x, y)
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
        val q = query.trim()
        // All launchable apps (accurate + only apps that can actually be opened).
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        )
        val match = launchers.firstOrNull { it.loadLabel(pm).toString().equals(q, ignoreCase = true) }
            ?: launchers.firstOrNull { it.loadLabel(pm).toString().contains(q, ignoreCase = true) }
            ?: launchers.firstOrNull { it.activityInfo.packageName.contains(q, ignoreCase = true) }
            ?: return "No installed app matching \"$query\"."
        val pkg = match.activityInfo.packageName
        val intent = pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return "Can't launch $pkg."
        startActivity(intent)
        return "Opened ${match.loadLabel(pm)}"
    }

    companion object {
        @Volatile
        var instance: JsonAccessibilityService? = null
            private set
    }
}
