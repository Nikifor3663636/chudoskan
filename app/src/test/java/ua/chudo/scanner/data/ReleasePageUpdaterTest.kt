package ua.chudo.scanner.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleasePageUpdaterTest {
    @Test
    fun onlyAcceptsStableTagsFromTheProjectReleasePage() {
        val repo = "https://github.com/Nikifor3663636/chudoskan/releases"
        assertEquals("v3.3.10", stableReleaseTag("$repo/tag/v3.3.10".toHttpUrl()))
        assertNull(stableReleaseTag("$repo/latest".toHttpUrl()))
        assertNull(stableReleaseTag("$repo/tag/v3.3.10-beta".toHttpUrl()))
        assertNull(stableReleaseTag("https://example.com/Nikifor3663636/chudoskan/releases/tag/v3.3.10".toHttpUrl()))
    }
}