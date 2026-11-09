package ua.chudo.shared

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.chudo.shared.auth.AuthRepository
import ua.chudo.shared.auth.Sha1
import java.security.MessageDigest

/** id магазина на iOS должен совпадать с Android (там java.security SHA-1). */
class Sha1Test {
    private fun javaSha1(s: ByteArray) = MessageDigest.getInstance("SHA-1").digest(s).joinToString("") { "%02x".format(it) }

    @Test fun matchesJava() {
        listOf("", "abc", "вул. Дерибасівська, 5/2, ТЦ \"Gagarinn Plaza\"", "x".repeat(55), "y".repeat(56), "z".repeat(64), "q".repeat(1000))
            .forEach { assertEquals(it, javaSha1(it.encodeToByteArray()), Sha1.hex(it.encodeToByteArray())) }
    }

    @Test fun shopIdLikeAndroid() {
        val shop = "  вул. Дерибасівська,   5/2  "
        val norm = shop.trim().split(Regex("\\s+")).joinToString(" ").lowercase()
        assertEquals("odesa_" + javaSha1(norm.toByteArray()).take(12), AuthRepository.shopId("odesa", shop))
    }
}
