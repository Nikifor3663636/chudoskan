package ua.chudo.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import ua.chudo.shared.core.Core
import ua.chudo.shared.core.CoreException
import ua.chudo.shared.core.QueryKind
import java.io.File

/**
 * JSON-контракт Kotlin-моделей с настоящим Rust-ядром (chudo_call) на реальных ответах сайта.
 * Тот же JSON идёт на iOS через cinterop. Нужна собранная core/target/debug (cargo build).
 */
class CoreContractTest {
    companion object {
        private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "core/Cargo.toml").exists() }
        private val lib = File(root, "core/target/debug")

        @BeforeClass @JvmStatic fun setUp() {
            System.setProperty("jna.library.path", lib.absolutePath)
        }
    }

    private fun fixture(name: String) = File(root, "core/tests/fixtures/$name").readText()

    @Test fun classifyAndRegions() {
        assumeTrue(lib.listFiles().orEmpty().any { it.name.startsWith("chudo_core.") || it.name.startsWith("libchudo_core.") })
        assertEquals(QueryKind.EAN13, Core.classifyQuery("4820000000000").kind)
        assertTrue(Core.regions().any { it.code == "odesa" && it.name.isNotEmpty() })
        assertTrue(Core.shopsUrl(36501, "odesa").endsWith("/ua/multiwarehouse/shops/index/?id=36501&region=odesa"))
    }

    @Test fun parsesRealResponses() {
        assumeTrue(lib.exists())
        val found = Core.parseSearch(fixture("search_10698.json"), "10698")
        assertTrue(found.isNotEmpty() && found.all { it.urlKey.isNotEmpty() })
        val d = Core.parseDetails(fixture("details_64103.json"))
        assertTrue(d.name.isNotEmpty() && d.price != null)
        assertTrue(Core.parseShops(fixture("shops_kyiv_1233.json")).isNotEmpty())
        assertTrue(Core.upcCandidates(fixture("upc_5702015357197.json"), "5702015357197", 4).isNotEmpty() || Core.upcTitle(fixture("upc_5702015357197.json")) != null)
    }

    @Test(expected = CoreException::class) fun errorsBecomeExceptions() {
        assumeTrue(lib.exists())
        Core.parseDetails("not json")
    }
}
