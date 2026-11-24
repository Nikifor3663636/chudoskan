package ua.chudo.scanner.payment

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** :app:testReleaseUnitTest — у release-збірці фейкового провайдера немає. */
class ReleaseProvidersTest {
    @Test fun `release без FakeProvider`() {
        assertTrue(BuildProviders.all().keys.none { it == "fake" })
        assertNull(PaymentRegistry.forBuild().forShop(ShopPayment("fake")))
        assertTrue(runCatching { Class.forName("ua.chudo.scanner.payment.FakePaymentProvider") }.isFailure)
    }
}
