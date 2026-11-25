package ua.chudo.scanner.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Оновлення: яка версія новіша (тег релізу «v3.2.0» проти versionName застосунку). */
class VersionsTest {
    @Test fun newer() {
        assertTrue(Versions.newer("v3.2.0", "3.1.0"))
        assertTrue(Versions.newer("3.10.0", "3.9.9"))
        assertTrue(Versions.newer("v3.1.1", "3.1"))
        assertTrue(Versions.newer("4.0", "3.99.99"))
    }

    @Test fun notNewer() {
        assertFalse(Versions.newer("v3.1.0", "3.1.0"))
        assertFalse(Versions.newer("3.1", "3.1.0"))
        assertFalse(Versions.newer("v3.0.3", "3.1.0"))
        assertFalse(Versions.newer("3.2.0-beta", "3.2.0"))
        assertFalse(Versions.newer("", "3.1.0"))
    }
}
