package com.jellowbeanz.json

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.net.Uri
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.delay

/** One on-screen element read from the accessibility tree. */
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
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        borderOverlay?.let { runCatching { wm.removeView(it) } }
        borderOverlay = null
        controlBar?.let { runCatching { wm.removeView(it) } }
        controlBar = null
        if (instance === this) instance = null
    }

    // ---- "Json is controlling your phone" overlay (the visible sign) ----

    private var borderOverlay: View? = null
    private var controlBar: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set true when the user taps Stop on the control bar; the agent loop polls it and halts. */
    @Volatile
    var stopRequested = false
        private set

    /** True only while the agent's own gesture is being injected — so its taps can't trigger Stop. */
    @Volatile
    private var agentGestureInFlight = false

    fun showControlOverlay() = mainHandler.post {
        if (borderOverlay != null) return@post
        stopRequested = false
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val accent = 0xFFD97757.toInt()

        // 1) Full-screen accent border — purely visual, never intercepts touches.
        val border = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setStroke(dp(4), accent)
                cornerRadius = dp(22).toFloat()
                setColor(0x00000000)
            }
        }
        val borderParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )

        // 2) A small touchable bar with the label + a Stop button the user can tap at any time.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(22).toFloat() }
            setPadding(dp(16), dp(7), dp(7), dp(7))
        }
        val label = TextView(this).apply {
            text = "⠿  ✦ Json is controlling"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
        }
        val stop = TextView(this).apply {
            text = "Stop"
            setTextColor(accent)
            textSize = 13f
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(16).toFloat() }
            setOnClickListener {
                if (agentGestureInFlight) return@setOnClickListener // never let the agent's injected tap trip Stop
                stopRequested = true
                text = "Stopping…"
            }
        }
        bar.addView(label)
        bar.addView(
            stop,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(12) },
        )
        // The pill floats and can be dragged (touch the label, not Stop) out of the way, so it never sits
        // over something Json needs to tap. Stop stays a normal button.
        bar.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val lp = bar.layoutParams as? WindowManager.LayoutParams ?: return false
                return when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = e.rawX; downRawY = e.rawY; startX = lp.x; startY = lp.y; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = startX + (e.rawX - downRawX).toInt()
                        lp.y = startY + (e.rawY - downRawY).toInt()
                        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(bar, lp) }
                        true
                    }
                    else -> false
                }
            }
        })
        val barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(44) }

        runCatching {
            wm.addView(border, borderParams)
            borderOverlay = border
            wm.addView(bar, barParams)
            controlBar = bar
        }
    }

    fun hideControlOverlay() = mainHandler.post {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        borderOverlay?.let { runCatching { wm.removeView(it) } }
        borderOverlay = null
        controlBar?.let { runCatching { wm.removeView(it) } }
        controlBar = null
    }

    /**
     * Dispatches an agent gesture so it passes THROUGH the Stop bar to the app behind, and can never
     * trigger Stop: the bar is made non-touchable for the gesture's duration, and Stop taps are ignored
     * while a gesture is in flight. A delayed restore guarantees Stop is never left disabled.
     */
    private fun dispatchAgentGesture(gesture: GestureDescription) {
        val restore = Runnable {
            agentGestureInFlight = false
            setBarTouchable(true)
        }
        agentGestureInFlight = true
        setBarTouchable(false)
        mainHandler.postDelayed(restore, 1500)
        // Let the non-touchable flag take effect before the injected tap lands, so the pill never eats it.
        mainHandler.postDelayed({
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) = restore.run()
                    override fun onCancelled(g: GestureDescription?) = restore.run()
                },
                mainHandler,
            )
        }, 50)
    }

    /** Toggle whether the Stop bar intercepts touches (false = the agent's taps pass through to the app behind). */
    private fun setBarTouchable(touchable: Boolean) {
        val bar = controlBar ?: return
        val lp = bar.layoutParams as? WindowManager.LayoutParams ?: return
        val newFlags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (newFlags != lp.flags) {
            lp.flags = newFlags
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(bar, lp) }
        }
    }

    /** Bumped on every UI event; lets [waitUntilIdle] wait exactly as long as the screen keeps changing. */
    @Volatile
    private var lastEventTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventTime = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() { /* required */ }

    /**
     * Adaptive settle: suspend until the screen has been quiet for [quietMs] (no accessibility events),
     * capped at [maxMs]. Returns fast when a screen settles quickly, but waits out a slow app launch —
     * replacing crude fixed sleeps between agent steps.
     */
    suspend fun waitUntilIdle(quietMs: Long = 350, maxMs: Long = 1600) {
        val start = SystemClock.uptimeMillis()
        while (true) {
            delay(60)
            val now = SystemClock.uptimeMillis()
            if ((now - lastEventTime >= quietMs && now - start >= quietMs) || now - start >= maxMs) return
        }
    }

    // ---- eyes ----

    fun readScreen(): List<Element> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<Element>()
        walk(root, elements)
        return elements
    }

    /** True when the foreground app is Json itself — the agent must never operate on its own UI. */
    fun isOnOwnApp(): Boolean = rootInActiveWindow?.packageName?.toString() == packageName

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
        dispatchAgentGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build(),
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
        val root = rootInActiveWindow ?: return "No screen to type into."
        // Prefer the focused field, but fall back to the first editable field — many search bars aren't
        // reported as "focused" right after a tap, which used to make typing fail in a loop.
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(root)
            ?: return "No text field on this screen to type into."
        if (!target.isFocused) runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) "Typed \"$text\"" else "Couldn't type into the field."
    }

    /** First editable node in the tree (a text field), whether or not it currently holds focus. */
    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i))?.let { return it }
        }
        return null
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
        dispatchAgentGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build(),
        )
        return "Swiped $direction"
    }

    fun press(key: String): String = when (key) {
        "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); "Pressed back" }
        "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); "Pressed home" }
        "enter" -> pressEnter()
        else -> "Unknown key: $key"
    }

    private fun pressEnter(): String {
        val root = rootInActiveWindow ?: return "No screen to submit."
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(root)
            ?: return "No text field to submit."
        if (!field.isFocused) runCatching { field.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        return if (android.os.Build.VERSION.SDK_INT >= 30 &&
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        ) {
            "Pressed enter"
        } else {
            "Couldn't submit"
        }
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

    // ---- fast-path shortcuts: jump straight to a pre-filled app via an Intent, skipping navigation.
    // Each first checks an app can actually handle it; the caller falls back to normal navigation if not. ----

    /** Fires [intent] only if some app can handle it; returns false so the agent can fall back. */
    private fun launch(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageManager.queryIntentActivities(intent, 0).isEmpty()) return false
        return runCatching { startActivity(intent); true }.getOrDefault(false)
    }

    fun openUrl(url: String): String {
        val u = url.trim().ifBlank { return "No link to open." }
        val uri = if (u.startsWith("http")) u else "https://$u"
        return if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))) "Opened $uri" else "Couldn't open the link."
    }

    fun dial(number: String): String {
        val n = number.trim().ifBlank { return "No number to dial." }
        return if (launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(n)}")))) "Opened dialer for $n" else "No dialer app."
    }

    fun openMaps(query: String): String {
        val q = query.trim().ifBlank { return "No place to search." }
        return if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(q)}")))) "Opened maps for \"$q\"" else "No maps app."
    }

    fun email(recipient: String, subject: String, body: String): String {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            if (recipient.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient.trim()))
            if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
        }
        return if (launch(intent)) "Opened an email to ${recipient.ifBlank { "compose" }}" else "No email app."
    }

    fun whatsApp(number: String, text: String): String {
        val n = number.filter { it.isDigit() || it == '+' }
        if (n.isBlank()) return "The WhatsApp shortcut needs a phone number; search the contact instead."
        val uri = "whatsapp://send?phone=${Uri.encode(n)}" + if (text.isNotBlank()) "&text=${Uri.encode(text)}" else ""
        return if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))) "Opened a WhatsApp chat with $n" else "WhatsApp isn't available."
    }

    companion object {
        @Volatile
        var instance: JsonAccessibilityService? = null
            private set
    }
}
