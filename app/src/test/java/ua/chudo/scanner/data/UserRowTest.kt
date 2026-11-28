package ua.chudo.scanner.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserRowTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun temporaryPasswordFlagComesFromTheDatabase() {
        val row = json.decodeFromString(UserRow.serializer(), """{"uid":"u1","name":"Олена","approved":true,"must_change_password":true}""")
        assertTrue(row.employee("+380671234567").mustChangePassword)
    }

    @Test fun olderRowsWithoutTheColumnDoNotForceAChange() {
        val row = json.decodeFromString(UserRow.serializer(), """{"uid":"u1","approved":true}""")
        assertFalse(row.employee("").mustChangePassword)
    }
}
