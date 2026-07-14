package com.jellowbeanz.json.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("json_settings")

/** A model the user can pick. */
data class ModelOption(val id: String, val label: String, val note: String)

/** A response-style preset. */
data class StyleOption(val id: String, val label: String, val prompt: String)

/** Snapshot of every preference, read once when building a request. */
data class Settings(
    val theme: String = "system",
    val model: String = SettingsStore.DEFAULT_MODEL,
    val userName: String = "",
    val about: String = "",
    val style: String = "default",
    val instructions: String = "",
)

/** DataStore-backed preferences: theme, model, personalization, style. */
class SettingsStore(private val context: Context) {

    val theme: Flow<String> = context.dataStore.data.map { it[THEME] ?: "system" }
    val model: Flow<String> = context.dataStore.data.map { it[MODEL] ?: DEFAULT_MODEL }
    val userName: Flow<String> = context.dataStore.data.map { it[USER_NAME] ?: "" }
    val about: Flow<String> = context.dataStore.data.map { it[ABOUT] ?: "" }
    val style: Flow<String> = context.dataStore.data.map { it[STYLE] ?: "default" }
    val instructions: Flow<String> = context.dataStore.data.map { it[INSTRUCTIONS] ?: "" }

    suspend fun setTheme(v: String) = context.dataStore.edit { it[THEME] = v }
    suspend fun setModel(v: String) = context.dataStore.edit { it[MODEL] = v }
    suspend fun setUserName(v: String) = context.dataStore.edit { it[USER_NAME] = v }
    suspend fun setAbout(v: String) = context.dataStore.edit { it[ABOUT] = v }
    suspend fun setStyle(v: String) = context.dataStore.edit { it[STYLE] = v }
    suspend fun setInstructions(v: String) = context.dataStore.edit { it[INSTRUCTIONS] = v }

    /** One consistent read for building an outgoing request. */
    suspend fun snapshot(): Settings {
        val p = context.dataStore.data.first()
        return Settings(
            theme = p[THEME] ?: "system",
            model = p[MODEL] ?: DEFAULT_MODEL,
            userName = p[USER_NAME] ?: "",
            about = p[ABOUT] ?: "",
            style = p[STYLE] ?: "default",
            instructions = p[INSTRUCTIONS] ?: "",
        )
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        private val THEME = stringPreferencesKey("theme")
        private val MODEL = stringPreferencesKey("model")
        private val USER_NAME = stringPreferencesKey("user_name")
        private val ABOUT = stringPreferencesKey("about_you")
        private val STYLE = stringPreferencesKey("style")
        private val INSTRUCTIONS = stringPreferencesKey("instructions")

        val MODELS = listOf(
            ModelOption("gemini-2.5-flash", "Flash", "Fast, great for everyday chat"),
            ModelOption("gemini-2.5-pro", "Pro", "Most capable, slower & deeper"),
            ModelOption("gemini-2.0-flash", "Flash 2.0", "Lightweight and quick"),
        )

        val STYLES = listOf(
            StyleOption("default", "Default", ""),
            StyleOption("concise", "Concise", "Keep answers short and to the point."),
            StyleOption("detailed", "Detailed", "Give thorough, well-structured explanations."),
            StyleOption("friendly", "Friendly", "Be warm, casual, and encouraging."),
            StyleOption("professional", "Professional", "Be formal, precise, and professional."),
        )

        /** Builds the system prompt from the base persona + the user's personalization. */
        fun systemPrompt(s: Settings): String {
            val sb = StringBuilder(
                "You are Json, a helpful AI assistant living on the user's Android phone. " +
                    "Reply in the user's language. Be clear and natural. " +
                    "Use markdown (bold, lists, fenced code blocks) when it improves readability. " +
                    "For every substantive message you MUST begin your reply with your reasoning wrapped " +
                    "in <think> and </think> tags — a few short first-person sentences, in the user's " +
                    "language, about how you'll answer — then write the final answer after </think>. " +
                    "Never skip the <think> block for a real question; only a bare greeting like \"hi\" may skip it. " +
                    "Example: <think>The user wants X. I'll cover Y and Z.</think>Here is the answer…",
            )
            if (s.userName.isNotBlank()) sb.append("\nThe user's name is ${s.userName.trim()}.")
            if (s.about.isNotBlank()) sb.append("\nAbout the user: ${s.about.trim()}")
            STYLES.firstOrNull { it.id == s.style }?.prompt?.takeIf { it.isNotBlank() }
                ?.let { sb.append("\nStyle: $it") }
            if (s.instructions.isNotBlank()) sb.append("\nUser instructions: ${s.instructions.trim()}")
            return sb.toString()
        }
    }
}
