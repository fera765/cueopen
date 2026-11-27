package dev.fluwy.app.llm

import dev.fluwy.app.data.Settings

/**
 * Provedores OpenAI-compatible prontos para ligar com uma chave (e OpenRouter também por login OAuth).
 * "free" descreve o plano gratuito do provedor no momento em que foi catalogado; limites mudam — confira no site dele.
 */
object ProviderCatalog {
    /** Login por código (OAuth Device Authorization, RFC 8628) com um app público registrado pelo próprio usuário. */
    data class DeviceAuth(val deviceUrl: String, val tokenUrl: String, val scope: String, val registerUrl: String, val howTo: String)

    data class Preset(
        val id: String,
        val name: String,
        val baseUrl: String,
        val keyUrl: String,
        val free: String,
        val defaultModel: String = "",
        val needsKey: Boolean = true,
        val oauth: Boolean = false,
        val device: DeviceAuth? = null,
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
        Preset("github", "GitHub Models", "https://models.github.ai/inference", "https://github.com/settings/tokens", "Gratuito para experimentação com a conta do GitHub (limites diários)",
            oauth = true, device = DeviceAuth("https://github.com/login/device/code", "https://github.com/login/oauth/access_token", "",
                "https://github.com/settings/apps/new",
                "Crie um GitHub App (grátis): marque \"Enable Device Flow\", em Account permissions dê Models → Read-only, salve e copie o Client ID.")),
        Preset("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "https://build.nvidia.com", "Créditos gratuitos para testes"),
        Preset("huggingface", "Hugging Face", "https://router.huggingface.co/v1", "https://huggingface.co/settings/tokens", "Créditos mensais gratuitos",
            oauth = true, device = DeviceAuth("https://huggingface.co/oauth/device", "https://huggingface.co/oauth/token", "openid inference-api",
                "https://huggingface.co/settings/applications/new",
                "Crie um app OAuth público (sem secret) com o escopo inference-api e copie o Client ID.")),
        Preset("sambanova", "SambaNova", "https://api.sambanova.ai/v1", "https://cloud.sambanova.ai/apis", "Plano gratuito com limites"),
        Preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys", "Pago, de baixo custo"),
        Preset("pollinations", "Pollinations", "https://gen.pollinations.ai/v1", "https://enter.pollinations.ai", "Chave gratuita com limite por tempo"),
        Preset("together", "Together AI", "https://api.together.xyz/v1", "https://api.together.xyz/settings/api-keys", "Alguns modelos gratuitos"),
        Preset("ollama", "Ollama (local)", "http://127.0.0.1:11434/v1", "https://ollama.com/download", "Grátis, roda num computador/servidor seu", needsKey = false),
        Preset("lmstudio", "LM Studio (local)", "http://127.0.0.1:1234/v1", "https://lmstudio.ai", "Grátis, roda num computador seu", needsKey = false),
    )

    private fun norm(url: String) = url.trim().trimEnd('/').lowercase()

    /** Preset correspondente a uma Base URL (null = personalizado). */
    fun byBaseUrl(url: String): Preset? = all.firstOrNull { norm(it.baseUrl) == norm(url) }

    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }

    /** Provedor ativo: o escolhido na página de Provedores ou, em instalações antigas, o deduzido da Base URL. */
    fun activeId(s: Settings): String = s.activeProvider.takeIf { it == "custom" || byId(it) != null } ?: storageKey(s.baseUrl)

    /** Ordem da página: login (OAuth) primeiro — é a forma preferida de conectar —, depois os demais. */
    val ordered: List<Preset> get() = all.sortedBy { if (it.oauth) 0 else 1 }

    /** Chave sob a qual a configuração de um provedor fica guardada em Settings.savedProviders. */
    fun storageKey(baseUrl: String): String = byBaseUrl(baseUrl)?.id ?: if (baseUrl.contains("openrouter", true)) "openrouter" else "custom"
}
