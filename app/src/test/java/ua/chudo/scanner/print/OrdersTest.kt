package ua.chudo.scanner.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.chudo.scanner.data.DemoOrders
import ua.chudo.scanner.data.OrderStage
import ua.chudo.scanner.data.OrdersRepository
import ua.chudo.scanner.data.ScanResult
import ua.chudo.scanner.ui.orders.goods

/** Сборка заказа сканером: +1 к строке, лишнее и чужое не считается, всё собрано → «Готові». */
class OrdersTest {
    @Test fun pickingByScanner() {
        val repo = OrdersRepository(DemoOrders).apply { load("shop") }
        val current = repo.current()!!
        assertEquals("100045790", current.number)
        assertEquals(3 to 5, current.picked to current.items)

        assertEquals(ScanResult.NotInOrder, repo.scan("4006381333931"))
        assertEquals(ScanResult.AlreadyEnough, repo.scan("5702016911114")) // LEGO уже 2/2
        assertTrue(repo.scan("6903317562084") is ScanResult.Added)
        assertTrue(repo.scan("6903317562084") is ScanResult.Completed)
        assertEquals(OrderStage.READY, repo.orders.value.first { it.number == "100045790" }.stage)
        assertEquals(ScanResult.NothingPicking, repo.scan("6903317562084"))
    }

    @Test fun startAndIssue() {
        val repo = OrdersRepository(DemoOrders).apply { load("shop") }
        repo.scan("6903317562084"); repo.scan("6903317562084") // дособрали текущий
        repo.start("100045871")
        assertEquals("100045871", repo.current()!!.number)
        repo.issue("100045755")
        assertTrue(repo.orders.value.none { it.number == "100045755" })
    }

    @Test fun goodsPlural() {
        assertEquals("1 товар", goods(1))
        assertEquals("3 товари", goods(3))
        assertEquals("5 товарів", goods(5))
        assertEquals("11 товарів", goods(11))
    }
}
