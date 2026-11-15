package dev.cueopen.app.llm

/**
 * Provedores OpenAI-compatible prontos para ligar com uma chave (e OpenRouter também por login OAuth).
 * "free" descreve o plano gratuito do provedor no momento em que foi catalogado; limites mudam — confira no site dele.
 */
object ProviderCatalog {
    data class Preset(
        val id: String,
        val name: String,
        val baseUrl: String,
        val keyUrl: String,
        val free: String,
        val defaultModel: String = "",
        val needsKey: Boolean = true,
        val oauth: Boolean = false,
    )

    val all: List<Preset> = listOf(
        Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys",
            "Dezenas de modelos gratuitos (sufixo :free), com limite diário", "deepseek/deepseek-v4-flash", oauth = true),
        Preset("opencode-go", "OpenCode Go", "https://opencode.ai/inference/go/openai/v1", "https://opencode.ai/auth", "Modelos gratuitos do OpenCode (ex.: beam-free)"),
        Preset("opencode-zen", "OpenCode Zen", "https://opencode.ai/zen/v1", "https://opencode.ai/auth", "Alguns modelos gratuitos por tempo limitado"),
        Preset("groq", "Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys", "Gratuito sem cartão, muito rápido (limite por minuto e por dia)"),
        Preset("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "https://cloud.cerebras.ai", "Gratuito, ~1M tokens/dia (contexto menor no plano grátis)"),
        Preset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey", "Plano gratuito no AI Studio (indisponível em alguns países)"),
        Preset("mistral", "Mistral", "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys", "Plano gratuito de experimentação"),
        Preset("github", "GitHub Models", "https://models.github.ai/inference", "https://github.com/settings/tokens", "Gratuito para experimentação com token do GitHub (limites diários)"),
        Preset("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "https://build.nvidia.com", "Créditos gratuitos para testes"),
        Preset("huggingface", "Hugging Face", "https://router.huggingface.co/v1", "https://huggingface.co/settings/tokens", "Créditos mensais gratuitos"),
        Preset("sambanova", "SambaNova", "https://api.sambanova.ai/v1", "https://cloud.sambanova.ai/apis", "Plano gratuito com limites"),
        Preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys", "Pago, de baixo custo"),
        Preset("together", "Together AI", "https://api.together.xyz/v1", "https://api.together.xyz/settings/api-keys", "Alguns modelos gratuitos"),
        Preset("ollama", "Ollama (local)", "http://127.0.0.1:11434/v1", "https://ollama.com/download", "Grátis, roda num computador/servidor seu", needsKey = false),
        Preset("lmstudio", "LM Studio (local)", "http://127.0.0.1:1234/v1", "https://lmstudio.ai", "Grátis, roda num computador seu", needsKey = false),
    )

    private fun norm(url: String) = url.trim().trimEnd('/').lowercase()

    /** Preset correspondente a uma Base URL (null = personalizado). */
    fun byBaseUrl(url: String): Preset? = all.firstOrNull { norm(it.baseUrl) == norm(url) }

    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }

    /** Chave sob a qual a configuração de um provedor fica guardada em Settings.savedProviders. */
    fun storageKey(baseUrl: String): String = byBaseUrl(baseUrl)?.id ?: if (baseUrl.contains("openrouter", true)) "openrouter" else "custom"
}
