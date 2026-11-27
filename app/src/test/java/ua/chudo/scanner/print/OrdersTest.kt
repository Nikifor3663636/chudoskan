package ua.chudo.scanner.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.chudo.scanner.data.DemoOrders
import ua.chudo.scanner.data.OrderStage
import ua.chudo.scanner.data.OrdersRepository
import ua.chudo.scanner.data.OrdersSource
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

    @Test fun manualMarkCompletesAndUnmarks() {
        val repo = OrdersRepository(DemoOrders).apply { load("shop") }
        val order = repo.current()!!
        val open = order.lines.indexOfFirst { !it.done }
        val marked = repo.togglePicked(order.number, open)!!
        assertEquals(OrderStage.READY, marked.stage)
        assertEquals(null, repo.current())
        // Готове замовлення вже не змінюється вручну.
        assertEquals(null, repo.togglePicked(order.number, open))

        val other = OrdersRepository(DemoOrders).apply { load("shop") }
        val done = order.lines.indexOfFirst { it.done }
        val unmarked = other.togglePicked(order.number, done)!!
        assertEquals(0, unmarked.lines[done].picked)
        assertEquals(OrderStage.PICKING, unmarked.stage)
    }

    @Test fun startAndIssue() {
        val repo = OrdersRepository(DemoOrders).apply { load("shop") }
        repo.scan("6903317562084"); repo.scan("6903317562084") // дособрали текущий
        repo.start("100045871")
        assertEquals("100045871", repo.current()!!.number)
        repo.issue("100045755")
        assertTrue(repo.orders.value.none { it.number == "100045755" })
    }

    @Test fun switchingOrdersKeepsTheScanOnTheSelectedOrder() {
        val repo = OrdersRepository(DemoOrders).apply { load("shop") }
        repo.start("100045871")
        assertTrue(repo.scan("5702016911114") is ScanResult.Added)
        assertEquals(1, repo.current()?.picked)
        repo.start("100045790")
        assertEquals(3, repo.current()?.picked)
        assertEquals(false, repo.issue("100045790"))
    }

    @Test fun oneScanOnlyUpdatesOneOfTheDuplicateLines() {
        val order = DemoOrders.load("shop").first()
        val line = order.lines.first()
        val repo = OrdersRepository(object : OrdersSource {
            override val demo = true
            override fun load(shopId: String) = listOf(order.copy(lines = listOf(line, line)))
        }).apply { load("shop"); start(order.number) }
        assertTrue(repo.scan(line.barcode) is ScanResult.Added)
        assertEquals(listOf(1, 0), repo.current()?.lines?.map { it.picked })
        assertTrue(repo.scan(line.barcode) is ScanResult.Completed)
    }

    @Test fun unpaidOrderRequiresExplicitPaymentConfirmation() {
        val repo = OrdersRepository(DemoOrders).apply { load("shop") }
        assertEquals(false, repo.issue("100045731"))
        assertEquals(true, repo.issue("100045731", paymentReceived = true))
    }

    @Test fun goodsPlural() {
        assertEquals("1 товар", goods(1))
        assertEquals("3 товари", goods(3))
        assertEquals("5 товарів", goods(5))
        assertEquals("11 товарів", goods(11))
    }
}
