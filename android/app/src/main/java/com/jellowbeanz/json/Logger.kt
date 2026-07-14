package com.jellowbeanz.json

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Log as AndroidLog

/** Tiny in-app logger: keeps the last N lines in memory (for the Debug screen) + mirrors to logcat (tag "Json"). */
object Logger {
    private const val CAP = 400
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun d(message: String) {
        AndroidLog.d("Json", message)
        add("· $message")
    }

    fun e(message: String, t: Throwable? = null) {
        AndroidLog.e("Json", message, t)
        add("✗ $message${t?.message?.let { " — $it" } ?: ""}")
    }

    private fun add(text: String) {
        val line = "${fmt.format(Date())}  $text"
        _lines.value = (_lines.value + line).takeLast(CAP)
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
