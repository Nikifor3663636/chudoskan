package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OwnByArticleTest {
    @Test fun ownShopQuantityByArticle() {
        val items = listOf(
            NetItem("LEGO DUPLO", "BOC1", "10954", mapOf("22" to 3.0, "62" to 5.0)),
            NetItem("М’яч синій", "BOC2", "A-3", mapOf("22" to 1.0)),
            NetItem("М’яч рожевий", "BOC3", "a 3", mapOf("22" to 2.0)),
            NetItem("Пазл", "BOC4", "SM22009", mapOf("62" to 4.0)),
            NetItem("Без артикула", "BOC5", "", mapOf("22" to 9.0)),
        )
        val own = ownByArticle(items, "22")
        assertEquals(3.0, own.getValue("10954"), 0.0)
        assertEquals("кольори одного артикула сумуються", 3.0, own.getValue("a3"), 0.0)
        assertEquals("є в 1С, але не в цьому магазині — 0", 0.0, own.getValue("sm22009"), 0.0)
        assertFalse(own.containsKey(""))
    }

    @Test fun articleKeyIgnoresCaseSpacesAndDashes() {
        assertEquals("sm22009", stockArticleKey(" SM-22 009 "))
        assertEquals("", stockArticleKey(null))
    }
}
