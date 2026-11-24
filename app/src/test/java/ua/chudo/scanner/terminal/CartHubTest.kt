package ua.chudo.scanner.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CartHubTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun item(key: String = "4820000000017", price: Long? = 64900) =
        cartItemOf("LEGO Duplo", "10954", price, key, "BOC1", "lego-duplo")

    @Test fun addFromProductGoesToTheSameCartAndFile() {
        val file = tmp.newFile("cart.txt")
        val hub = CartHub(CartStore(file))
        assertEquals(CartHub.AddResult.Added(1, 1), hub.add(item()))
        assertEquals(CartHub.AddResult.Added(2, 2), hub.add(item()))
        assertEquals(1, hub.cart.value.items.size)
        // Перезапуск застосунку: кошик із файлу.
        assertEquals(2, CartHub(CartStore(file)).cart.value.count)
    }

    @Test fun lockedDuringPaymentChangesNothing() {
        val hub = CartHub(null)
        hub.add(item())
        hub.locked.value = true
        assertEquals(CartHub.AddResult.Locked, hub.add(item("other")))
        assertFalse(hub.edit { it.inc("4820000000017") })
        assertEquals(1, hub.cart.value.count)
    }

    @Test fun closedCartRejectsOutsideAddsButTerminalStillEdits() {
        val hub = CartHub(null)
        hub.add(item())
        hub.closed.value = true
        assertEquals(CartHub.AddResult.Locked, hub.add(item("other")))
        assertTrue(hub.edit { it.inc("4820000000017") })
        hub.clear()
        assertFalse(hub.closed.value)
        assertTrue(hub.add(item("other")) is CartHub.AddResult.Added)
    }

    @Test fun changingCartDropsSaleButBindingKeepsIt() {
        val file = tmp.newFile("cart.txt")
        val hub = CartHub(CartStore(file))
        hub.add(item())
        hub.bindSale("sale-1")
        assertEquals("sale-1", CartHub(CartStore(file)).saleId)
        hub.add(item())
        assertNull(hub.saleId)
    }

    @Test fun clearEmptiesCartAndFile() {
        val file = tmp.newFile("cart.txt")
        val hub = CartHub(CartStore(file))
        hub.add(item())
        hub.clear()
        assertTrue(hub.cart.value.items.isEmpty())
        assertTrue(CartHub(CartStore(file)).cart.value.items.isEmpty())
    }

    @Test fun itemWithoutSitePriceAsksForManualPrice() {
        val noPrice = item(price = null)
        assertTrue(noPrice.manualPrice)
        assertEquals(0, noPrice.priceKop)
        assertFalse(item().manualPrice)
        // Без штрихкоду — ключ за кодом 1С, без коду — за сторінкою сайту.
        assertEquals("BOC1", cartItemOf("A", null, 100, null, "BOC1", "a").key)
        assertEquals("site:a", cartItemOf("A", null, 100, "", "", "a").key)
    }

    @Test fun cartLimitReportedNotThrown() {
        val hub = CartHub(null)
        repeat(Cart.MAX_ITEMS) { hub.add(item("k$it")) }
        assertTrue(hub.add(item("over")) is CartHub.AddResult.Failed)
    }
}
