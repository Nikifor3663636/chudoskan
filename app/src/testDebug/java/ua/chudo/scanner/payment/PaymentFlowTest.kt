package ua.chudo.scanner.payment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.chudo.scanner.terminal.SaleStatus
import ua.chudo.scanner.terminal.SaleStatus.CANCELLED
import ua.chudo.scanner.terminal.SaleStatus.DECLINED
import ua.chudo.scanner.terminal.SaleStatus.DRAFT
import ua.chudo.scanner.terminal.SaleStatus.EXPORTED_1C
import ua.chudo.scanner.terminal.SaleStatus.FISCALIZED
import ua.chudo.scanner.terminal.SaleStatus.PAID
import ua.chudo.scanner.terminal.SaleStatus.PAYING
import ua.chudo.scanner.terminal.SaleStatus.REFUNDED
import ua.chudo.scanner.terminal.SaleStatus.SENT

class PaymentFlowTest {

    /** Як Firestore: перехід лише з поточного стану, кожен — у журнал. */
    private class FakeStore : SaleStore {
        val sales = LinkedHashMap<String, SaleRecord>()
        val payments = HashMap<String, PaymentRecord>()
        val log = mutableListOf<Triple<String, SaleStatus, SaleStatus>>()
        val refunds = mutableListOf<Pair<String, PaymentRecord>>()

        fun add(id: String, total: Long, status: SaleStatus = SENT) { sales[id] = SaleRecord(id, "22", "emp22", total, status) }
        fun status(id: String) = sales.getValue(id).status

        override suspend fun get(id: String) = sales[id]

        override suspend fun move(id: String, from: SaleStatus, to: SaleStatus, payment: PaymentRecord?) {
            val s = sales.getValue(id)
            check(s.status == from) { "стан ${s.status}, а не $from" }
            check(SaleStates.clientCan(from, to)) { "$from → $to" }
            sales[id] = s.copy(status = to)
            payment?.let { payments[id] = it }
            log += Triple(id, from, to)
        }

        override suspend fun paying(shopId: String, by: String) = sales.values.filter { it.status == PAYING && it.by == by }

        override suspend fun refunded(id: String, from: SaleStatus, refund: PaymentRecord) {
            move(id, from, REFUNDED)
            refunds += id to refund
        }
    }

    @Test fun `машина станів — лише вперед`() {
        val ok = listOf(DRAFT to SENT, SENT to PAYING, PAYING to PAID, PAYING to DECLINED, PAYING to CANCELLED,
            DECLINED to DRAFT, CANCELLED to DRAFT, PAID to REFUNDED, FISCALIZED to REFUNDED, DRAFT to CANCELLED)
        ok.forEach { (a, b) -> assertTrue("$a → $b", SaleStates.clientCan(a, b)) }
        val bad = listOf(DRAFT to PAYING, DRAFT to PAID, SENT to DRAFT, PAYING to SENT, PAID to PAYING, PAID to DRAFT,
            PAID to FISCALIZED, FISCALIZED to EXPORTED_1C, EXPORTED_1C to PAID, REFUNDED to PAID, DECLINED to PAID, SENT to PAID)
        bad.forEach { (a, b) -> assertFalse("$a → $b", SaleStates.clientCan(a, b)) }
        assertTrue(SaleStates.trustedCan(PAID, FISCALIZED))
        assertTrue(SaleStates.trustedCan(FISCALIZED, EXPORTED_1C))
        assertFalse(SaleStates.trustedCan(PAID, EXPORTED_1C))
        assertFalse(SaleStates.trustedCan(PAYING, PAID))
        // Кошик редагується лише в чернетці.
        assertTrue(SaleStates.editable(DRAFT))
        SaleStatus.entries.filter { it != DRAFT }.forEach { assertFalse("$it", SaleStates.editable(it)) }
    }

    @Test fun `схвалено — paid, сума з продажу, журнал повний`() = runBlocking {
        val store = FakeStore().apply { add("s1", 64_691, DRAFT) }
        val fake = FakePaymentProvider(FakePaymentProvider.Mode.APPROVE)
        val r = PaymentFlow(store, fake).pay("s1")
        assertTrue(r is PayOutcome.Paid)
        assertEquals(PAID, store.status("s1"))
        assertEquals(64_691, store.payments.getValue("s1").approved.amountKop)
        assertEquals("fake", store.payments.getValue("s1").provider)
        assertEquals(listOf(DRAFT to SENT, SENT to PAYING, PAYING to PAID), store.log.map { it.second to it.third })
        assertEquals(listOf("s1"), fake.payCalls)
    }

    @Test fun `відхилено — declined, можна повторити`() = runBlocking {
        val store = FakeStore().apply { add("s1", 1_000) }
        val fake = FakePaymentProvider(FakePaymentProvider.Mode.DECLINE)
        val flow = PaymentFlow(store, fake)
        assertEquals(PayOutcome.Declined("Недостатньо коштів"), flow.pay("s1"))
        assertEquals(DECLINED, store.status("s1"))
        fake.mode = FakePaymentProvider.Mode.APPROVE
        assertTrue(flow.pay("s1") is PayOutcome.Paid)
        assertEquals(PAID, store.status("s1"))
        assertEquals(2, fake.payCalls.size)
    }

    @Test fun `Unknown і перезапуск — status() знаходить оплату, другого pay немає`() = runBlocking {
        val store = FakeStore().apply { add("s1", 2_490) }
        val fake = FakePaymentProvider(FakePaymentProvider.Mode.UNKNOWN)
        val r = PaymentFlow(store, fake).pay("s1")
        assertEquals(PayOutcome.CheckBank("Перевірте оплату в застосунку банку"), r)
        assertEquals(PAYING, store.status("s1"))

        // «Перезапуск»: новий потік, той самий банк.
        fake.mode = FakePaymentProvider.Mode.APPROVE
        val recovered = PaymentFlow(store, fake).recover("22", "emp22")
        assertTrue(recovered.getValue("s1") is PayOutcome.Paid)
        assertEquals(PAID, store.status("s1"))
        assertEquals(listOf("s1"), fake.payCalls) // pay лише один раз
    }

    @Test fun `таймаут без списання — після перезапуску cancelled, і повтор платить один раз`() = runBlocking {
        val store = FakeStore().apply { add("s1", 2_490) }
        val fake = FakePaymentProvider(FakePaymentProvider.Mode.TIMEOUT)
        assertTrue(PaymentFlow(store, fake).pay("s1") is PayOutcome.CheckBank)
        assertEquals(PayOutcome.Cancelled, PaymentFlow(store, fake).recover("22", "emp22").getValue("s1"))
        assertEquals(CANCELLED, store.status("s1"))
        fake.mode = FakePaymentProvider.Mode.APPROVE
        assertTrue(PaymentFlow(store, fake).pay("s1") is PayOutcome.Paid)
        assertEquals(2, fake.payCalls.size)
    }

    @Test fun `повторний pay у paying не платить двічі — спершу status()`() = runBlocking {
        val store = FakeStore().apply { add("s1", 2_490) }
        val fake = FakePaymentProvider(FakePaymentProvider.Mode.UNKNOWN)
        val flow = PaymentFlow(store, fake)
        flow.pay("s1")
        // Продавець тисне «Оплатити» ще раз — банк уже списав, отже лише фіксуємо оплату.
        assertTrue(flow.pay("s1") is PayOutcome.Paid)
        assertEquals(1, fake.payCalls.size)
    }

    @Test fun `банк провів іншу суму — не ставимо paid`() = runBlocking {
        val store = FakeStore().apply { add("s1", 2_490) }
        val other = object : PaymentProvider by FakePaymentProvider() {
            override suspend fun pay(saleId: String, amountKop: Long) =
                PaymentResult.Approved("1", "2", "3", amountKop - 1, java.util.Date())
            override suspend fun status(saleId: String) = PaymentResult.Cancelled
        }
        assertTrue(PaymentFlow(store, other).pay("s1") is PayOutcome.CheckBank)
        assertEquals(PAYING, store.status("s1"))
    }

    @Test fun `повернення — окремий документ і refunded`() = runBlocking {
        val store = FakeStore().apply { add("s1", 2_490) }
        val fake = FakePaymentProvider()
        val flow = PaymentFlow(store, fake)
        val paid = (flow.pay("s1") as PayOutcome.Paid).payment
        assertTrue(flow.refund("s1", paid.rrn, 2_490) is PayOutcome.Paid)
        assertEquals(REFUNDED, store.status("s1"))
        assertEquals("s1", store.refunds.single().first)
        assertTrue(runCatching { flow.refund("s1", paid.rrn, 2_490) }.isFailure)
    }

    @Test fun `provider none — оплати карткою немає, fake лише в debug`() {
        val reg = PaymentRegistry.forBuild()
        assertNull(reg.forShop(ShopPayment(ShopPayment.NONE)))
        assertNull(reg.forShop(null))
        assertNull(reg.forShop(ShopPayment("privat-nfcpos")))
        // Цей тест іде на debug-збірці — фейк тут є.
        assertEquals("fake", reg.forShop(ShopPayment("fake"))?.id)
    }
}
