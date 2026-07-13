package com.jellowbeanz.json

import android.content.Context

/** Stores the user's API key on-device (private SharedPreferences). */
object KeyStore {
    private const val PREFS = "json_prefs"
    private const val KEY = "api_key"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun set(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, apiKey.trim()).apply()
    }
}
