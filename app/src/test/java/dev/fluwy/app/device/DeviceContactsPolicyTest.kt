package dev.fluwy.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceContactsPolicyTest {
    @Test fun requiresSpecificSearchInsteadOfListingTheWholeAddressBook() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            DeviceContactsPolicy.normalizeQuery("%")
        }
        assertTrue(error.message.orEmpty().contains("agenda inteira"))
    }

    @Test fun trimsAndBoundsSearchTerms() {
        assertEquals("Ana", DeviceContactsPolicy.normalizeQuery(" Ana "))
        assertEquals(DeviceContactsPolicy.MAX_QUERY_LENGTH,
            DeviceContactsPolicy.normalizeQuery("x".repeat(200)).length)
        assertEquals(10, DeviceContactsPolicy.MAX_RESULTS)
        assertEquals("%Ana%", DeviceContactsPolicy.likePattern("Ana"))
        assertEquals("%\\%\\_a%", DeviceContactsPolicy.likePattern("%_a"))
    }
}
