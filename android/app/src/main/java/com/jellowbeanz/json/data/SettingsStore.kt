package com.jellowbeanz.json.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("json_settings")

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
    val useLocal: Boolean = false,
    val localUrl: String = "",
    val localModel: String = "",
    val memoryEnabled: Boolean = true,
    val memories: List<String> = emptyList(),
)

/** DataStore-backed preferences: theme, model, personalization, style. */
class SettingsStore(private val context: Context) {

    val theme: Flow<String> = context.dataStore.data.map { it[THEME] ?: "system" }
    val model: Flow<String> = context.dataStore.data.map { it[MODEL] ?: DEFAULT_MODEL }
    val userName: Flow<String> = context.dataStore.data.map { it[USER_NAME] ?: "" }
    val about: Flow<String> = context.dataStore.data.map { it[ABOUT] ?: "" }
    val style: Flow<String> = context.dataStore.data.map { it[STYLE] ?: "default" }
    val instructions: Flow<String> = context.dataStore.data.map { it[INSTRUCTIONS] ?: "" }
    val useLocal: Flow<Boolean> = context.dataStore.data.map { it[USE_LOCAL] ?: false }
    val localUrl: Flow<String> = context.dataStore.data.map { it[LOCAL_URL] ?: "" }
    val localModel: Flow<String> = context.dataStore.data.map { it[LOCAL_MODEL] ?: "" }
    val memoryEnabled: Flow<Boolean> = context.dataStore.data.map { it[MEMORY_ENABLED] ?: true }
    val memories: Flow<List<String>> = context.dataStore.data.map { (it[MEMORIES] ?: emptySet()).toList() }

    suspend fun setTheme(v: String) = context.dataStore.edit { it[THEME] = v }
    suspend fun setModel(v: String) = context.dataStore.edit { it[MODEL] = v }
    suspend fun setUserName(v: String) = context.dataStore.edit { it[USER_NAME] = v }
    suspend fun setAbout(v: String) = context.dataStore.edit { it[ABOUT] = v }
    suspend fun setStyle(v: String) = context.dataStore.edit { it[STYLE] = v }
    suspend fun setInstructions(v: String) = context.dataStore.edit { it[INSTRUCTIONS] = v }
    suspend fun setUseLocal(v: Boolean) = context.dataStore.edit { it[USE_LOCAL] = v }
    suspend fun setLocalUrl(v: String) = context.dataStore.edit { it[LOCAL_URL] = v }
    suspend fun setLocalModel(v: String) = context.dataStore.edit { it[LOCAL_MODEL] = v }
    suspend fun setMemoryEnabled(v: Boolean) = context.dataStore.edit { it[MEMORY_ENABLED] = v }
    suspend fun addMemory(text: String) {
        val t = text.trim()
        if (t.isNotBlank()) context.dataStore.edit { it[MEMORIES] = (it[MEMORIES] ?: emptySet()) + t }
    }
    suspend fun removeMemory(text: String) =
        context.dataStore.edit { it[MEMORIES] = (it[MEMORIES] ?: emptySet()) - text }
    suspend fun clearMemories() = context.dataStore.edit { it.remove(MEMORIES) }

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
            useLocal = p[USE_LOCAL] ?: false,
            localUrl = p[LOCAL_URL] ?: "",
            localModel = p[LOCAL_MODEL] ?: "",
            memoryEnabled = p[MEMORY_ENABLED] ?: true,
            memories = (p[MEMORIES] ?: emptySet()).toList(),
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
        private val USE_LOCAL = booleanPreferencesKey("use_local")
        private val LOCAL_URL = stringPreferencesKey("local_url")
        private val LOCAL_MODEL = stringPreferencesKey("local_model")
        private val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        private val MEMORIES = stringSetPreferencesKey("memories")

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
                    "Use markdown (bold, lists, fenced code blocks) when it improves readability.",
            )
            if (s.userName.isNotBlank()) sb.append("\nThe user's name is ${s.userName.trim()}.")
            if (s.about.isNotBlank()) sb.append("\nAbout the user: ${s.about.trim()}")
            STYLES.firstOrNull { it.id == s.style }?.prompt?.takeIf { it.isNotBlank() }
                ?.let { sb.append("\nStyle: $it") }
            if (s.instructions.isNotBlank()) sb.append("\nUser instructions: ${s.instructions.trim()}")
            if (s.memoryEnabled && s.memories.isNotEmpty()) {
                sb.append("\n\nWhat you remember about the user (from past chats):")
                s.memories.forEach { sb.append("\n- ").append(it) }
            }
            return sb.toString()
        }
    }
}
