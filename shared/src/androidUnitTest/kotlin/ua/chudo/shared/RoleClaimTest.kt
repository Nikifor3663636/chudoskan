package ua.chudo.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.chudo.shared.auth.AuthRepository
import java.util.Base64

/** Supabase приймає Firebase ID token лише з role = "authenticated" — iOS / веб перевіряють, чи треба ensure-role. */
class RoleClaimTest {
    private fun jwt(payload: String): String {
        val e = Base64.getUrlEncoder().withoutPadding()
        return e.encodeToString("""{"alg":"RS256"}""".toByteArray()) + "." + e.encodeToString(payload.toByteArray()) + ".sig"
    }

    @Test fun roleClaim() {
        assertTrue(AuthRepository.hasRole(jwt("""{"sub":"u1","role":"authenticated"}""")))
        assertFalse(AuthRepository.hasRole(jwt("""{"sub":"u1"}""")))
        assertFalse(AuthRepository.hasRole(jwt("""{"role":"service_role"}""")))
        assertFalse(AuthRepository.hasRole("зламаний"))
        // Довжина payload, що потребувала б «=» у звичайному Base64.
        assertTrue(AuthRepository.hasRole(jwt("""{"role":"authenticated","x":"ab"}""")))
    }
}
