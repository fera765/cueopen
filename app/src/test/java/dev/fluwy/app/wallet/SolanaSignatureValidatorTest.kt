package dev.fluwy.app.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaSignatureValidatorTest {
    private val validSignatureA =
        "67rpwLCuS5DGA8KGZXKsVQ7dnPb9goRLoKfgGbLfQg9WoLUgNY77E2jT11fem3coV9nAkguBACzrU1iyZM4B8roQ"
    private val validSignatureB =
        "5n4yLmzWc14iDPJ3vZAszSSXTu1wWQzX5ZtZnVBcrAUHDKBNHEZmUdR9oQpg7JukPQfHqWWN5j8bZwounf3xrsM7"

    @Test
    fun acceptsBase58EncodingsOfExactly64Bytes() {
        assertTrue(SolanaSignatureValidator.isValid(validSignatureA))
        assertTrue(SolanaSignatureValidator.isValid(validSignatureB))
    }

    @Test
    fun rejectsWrongByteLengthAndInvalidAlphabet() {
        assertFalse(SolanaSignatureValidator.isValid("1".repeat(63)))
        assertFalse(SolanaSignatureValidator.isValid("z".repeat(88)))
        assertFalse(SolanaSignatureValidator.isValid("0".repeat(88)))
        assertFalse(SolanaSignatureValidator.isValid(""))
    }
}
