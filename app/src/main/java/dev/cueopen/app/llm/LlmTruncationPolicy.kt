package dev.cueopen.app.llm

/** Keeps textual output from a length-limited reply and bounds safe continuation attempts. */
internal class LlmTruncationPolicy(
    private val maxFragments: Int = 3,
) {
    enum class Decision { CONTINUE, STOP }

    private val partial = StringBuilder()
    private var fragmentCount = 0
    private var lastFragmentSignature: String? = null

    init {
        require(maxFragments >= 1)
    }

    /** Stores a text-only fragment; repeated or empty fragments stop without duplicating output. */
    fun accept(fragment: String?): Decision {
        val text = fragment.orEmpty()
        val signature = text.trim()
        if (signature.isEmpty() || signature == lastFragmentSignature) return Decision.STOP

        partial.append(text)
        lastFragmentSignature = signature
        fragmentCount++
        return if (fragmentCount < maxFragments) Decision.CONTINUE else Decision.STOP
    }

    /** Joins the finished continuation to its saved prefix, then clears this turn's state. */
    fun complete(content: String?): String {
        val result = partial.toString() + content.orEmpty()
        partial.clear()
        fragmentCount = 0
        lastFragmentSignature = null
        return result
    }

    fun partialText(): String = partial.toString()
}
