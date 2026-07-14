package com.jellowbeanz.json.llm

/** Routes an API key to the right provider by its prefix. */
object Llm {
    val providers = listOf(GeminiProvider, AnthropicProvider, OpenAiProvider)

    fun forKey(key: String): LlmProvider? {
        val k = key.trim()
        return when {
            k.startsWith("sk-ant-") -> AnthropicProvider
            k.startsWith("sk-proj-") || k.startsWith("sk-svcacct-") || k.startsWith("sk-") -> OpenAiProvider
            k.startsWith("AIza") || k.startsWith("AQ.") -> GeminiProvider
            else -> null
        }
    }

    /** Keep the saved model if it belongs to the provider, otherwise fall back to its default. */
    fun resolveModel(provider: LlmProvider, saved: String): String =
        if (provider.models.any { it.id == saved }) saved else provider.defaultModel
}
