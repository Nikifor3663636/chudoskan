package ua.chudo.scanner.print

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScanTest {
    private val net = "192.168.1.0/24"

    @Test fun firstWifiConnectionScans() = assertTrue(AutoScan.due(now = 10_000, last = 0, subnet = net, lastSubnet = null))

    @Test fun noWifiNoScan() = assertFalse(AutoScan.due(now = 10_000_000, last = 0, subnet = null, lastSubnet = null))

    @Test fun sameNetworkWaitsTwoMinutes() {
        assertFalse(AutoScan.due(now = 60_000, last = 0, subnet = net, lastSubnet = net))
        assertTrue(AutoScan.due(now = AutoScan.MIN_GAP_MS, last = 0, subnet = net, lastSubnet = net))
    }

    @Test fun otherNetworkScansRightAway() = assertTrue(AutoScan.due(now = 1_000, last = 0, subnet = "10.0.0.0/24", lastSubnet = net))
}
