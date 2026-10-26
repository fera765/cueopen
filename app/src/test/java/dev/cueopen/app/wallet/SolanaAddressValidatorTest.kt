package dev.cueopen.app.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaAddressValidatorTest {
    @Test
    fun knownSystemProgramAddressHasValidEncodingAndLength() {
        assertTrue(SolanaAddressValidator.isValidAddress("11111111111111111111111111111111"))
    }

    @Test
    fun wrongLengthAndInvalidAlphabetAreRejected() {
        assertFalse(SolanaAddressValidator.isValidAddress("1111111111111111111111111111111"))
        assertFalse(SolanaAddressValidator.isValidAddress("0OIl111111111111111111111111111111"))
        assertFalse(SolanaAddressValidator.isValidAddress(""))
    }
}
