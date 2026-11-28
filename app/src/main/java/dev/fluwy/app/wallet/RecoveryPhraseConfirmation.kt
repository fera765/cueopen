package dev.fluwy.app.wallet

/**
 * Compares backup confirmation without creating immutable String copies of secret input.
 * BIP-39 English words are case-insensitive; whitespace between words is normalized.
 */
internal object RecoveryPhraseConfirmation {
    fun matches(candidate: CharArray, expected: CharArray): Boolean {
        val left = normalize(candidate)
        val right = normalize(expected)
        return try {
            var difference = left.size xor right.size
            val count = maxOf(left.size, right.size)
            for (index in 0 until count) {
                val a = if (index < left.size) left[index].code else 0
                val b = if (index < right.size) right[index].code else 0
                difference = difference or (a xor b)
            }
            difference == 0
        } finally {
            left.fill('\u0000')
            right.fill('\u0000')
        }
    }

    private fun normalize(value: CharArray): CharArray {
        val normalized = CharArray(value.size)
        var written = 0
        var pendingSpace = false
        for (character in value) {
            if (character.isWhitespace()) {
                if (written > 0) pendingSpace = true
                continue
            }
            if (pendingSpace) {
                normalized[written++] = ' '
                pendingSpace = false
            }
            normalized[written++] = if (character in 'A'..'Z') {
                (character.code + ('a'.code - 'A'.code)).toChar()
            } else {
                character
            }
        }
        return normalized.copyOf(written).also { normalized.fill('\u0000') }
    }
}
