package com.jellowbeanz.json

/** Auto-detects the LLM provider from an API key's prefix (Kotlin twin of the Python apikeys). */
data class ProviderInfo(val id: String, val name: String)

fun detectProvider(key: String): ProviderInfo? {
    val k = key.trim()
    return when {
        k.startsWith("sk-ant-") -> ProviderInfo("anthropic", "Anthropic (Claude)")
        k.startsWith("sk-or-") -> ProviderInfo("openrouter", "OpenRouter")
        k.startsWith("sk-proj-") || k.startsWith("sk-svcacct-") -> ProviderInfo("openai", "OpenAI")
        k.startsWith("gsk_") -> ProviderInfo("groq", "Groq")
        k.startsWith("xai-") -> ProviderInfo("xai", "xAI (Grok)")
        k.startsWith("AIza") || k.startsWith("AQ.") -> ProviderInfo("google", "Google Gemini")
        else -> null
    }
}
