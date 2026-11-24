package ua.chudo.scanner.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import ua.chudo.scanner.data.NetItem
import ua.chudo.scanner.data.NetworkAvailability
import ua.chudo.scanner.data.StoreDoc
import ua.chudo.scanner.terminal.Cart
import ua.chudo.scanner.terminal.CartItem
import ua.chudo.scanner.terminal.Discount
import ua.chudo.scanner.ui.common.MainTab
import ua.chudo.scanner.ui.common.MineBottomBar
import ua.chudo.scanner.ui.common.MineHeader
import ua.chudo.scanner.ui.stock.NetworkStockCard
import ua.chudo.scanner.ui.terminal.TerminalContent
import ua.chudo.scanner.ui.theme.ChudoTheme
import ua.chudo.scanner.ui.theme.Mine
import ua.chudo.scanner.ui.theme.ThemeMode
import ua.chudo.scanner.ui.update.RequiredUpdateScreen
import ua.chudo.scanner.ui.update.UpdateBanner
import ua.chudo.scanner.update.UpdateInfo
import java.util.Calendar

/**
 * Знімки для README і посібників (docs/img): лише знеособлені дані — вигадані імена, телефони, залишки й ціни.
 * Оновити: ./gradlew :app:recordPaparazziDebug --tests "*DocsSnapshots*" і скопіювати docs_* у docs/img.
 */
class DocsSnapshots {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, theme = "android:Theme.Material.NoActionBar")

    private val at930 = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 24, 9, 30, 0) }.time

    private val stores = mapOf(
        "22" to StoreDoc("ЧО 22 Одеса (ТРЦ «Приклад»)", "Одеса", "shop-22"),
        "23" to StoreDoc("ЧО 23 Одеса (ТЦ «Зразок»)", "Одеса", null),
        "31" to StoreDoc("ЧО 31 Одеса (ТЦ «Демо»)", "Одеса", null),
    )
    private val bag = NetItem("Пакет з петлевою ручкою 40×50 см", "BOC000001", "PK-0001", mapOf("22" to 12.0, "23" to 5.0, "31" to 2.0))

    private val cart = Cart()
        .add(CartItem("4820000000017", "4820000000017", "М'яч гумовий 22 см", "BUK000002", "MB-22", 2, 24_990, false))
        .add(CartItem("2000000000013", "2000000000013", "Пакет з петлевою ручкою 40×50 см", "BOC000001", "PK-0001", 1, 1_500, true))
        .add(CartItem("4820000000024", "4820000000024", "Повітряний змій «Веселка»", "DPK000003", "", 1, 8_999, false))
        .withDiscount(Discount.Percent(10))

    @Test fun docs_home_light() = home(false)
    @Test fun docs_home_dark() = home(true)

    private fun home(dark: Boolean) = paparazzi.snapshot {
        ChudoTheme(dark = dark) {
            Scaffold(containerColor = Mine.colors.bg, bottomBar = { MineBottomBar(MainTab.SEARCH, newOrders = 2, onTab = {}, onScan = {}) }) { inner ->
                Box(Modifier.padding(inner)) {
                    ua.chudo.scanner.ui.home.HomeContent(
                        ua.chudo.scanner.ui.home.HomeUi("ТРЦ «Приклад»", "Олена Приклад", at930, "", listOf(
                            ua.chudo.scanner.data.HistoryItem("ball", "М'яч гумовий 22 см", "MB-22", null),
                            ua.chudo.scanner.data.HistoryItem("kite", "Повітряний змій «Веселка»", "KT-01", null),
                        ), 2),
                    )
                }
            }
        }
    }

    @Test fun docs_terminal_light() = terminal(false)
    @Test fun docs_terminal_dark() = terminal(true)

    private fun terminal(dark: Boolean) = paparazzi.snapshot {
        ChudoTheme(dark = dark) {
            Scaffold(containerColor = Mine.colors.bg, bottomBar = { MineBottomBar(MainTab.TERMINAL, newOrders = 0, onTab = {}, onScan = {}) }) { inner ->
                Box(Modifier.padding(inner)) {
                    TerminalContent(
                        cart, loading = null, card = false, checkBank = false, sending = false, paying = false,
                        query = "", onQuery = {}, onSubmit = {}, received = "1000", onReceived = {}, onScan = {},
                    )
                }
            }
        }
    }

    @Test fun docs_stock1c_light() = stock(false)
    @Test fun docs_stock1c_dark() = stock(true)

    private fun stock(dark: Boolean) = paparazzi.snapshot {
        ChudoTheme(dark = dark) {
            Surface(color = Mine.colors.bg) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    MineHeader("Товар з 1С", {})
                    NetworkStockCard(NetworkAvailability.of("2000000000013", bag, "22", stores, at930), Modifier.padding(top = 12.dp))
                }
            }
        }
    }

    @Test fun docs_update_required() = paparazzi.snapshot {
        ChudoTheme(dark = false) {
            RequiredUpdateScreen(UpdateInfo(1050, "3.2", "https://x", "0".repeat(64)), "Ця версія застаріла. Оновіть застосунок, щоб продовжити роботу.", null, null, {}, {})
        }
    }

    @Test fun docs_update_banner() = paparazzi.snapshot {
        ChudoTheme(dark = false) {
            Scaffold(containerColor = Mine.colors.bg, bottomBar = {
                Column {
                    UpdateBanner(UpdateInfo(1050, "3.2", "https://x", "0".repeat(64)), null, null) {}
                    MineBottomBar(MainTab.SEARCH, newOrders = 0, onTab = {}, onScan = {})
                }
            }) { inner ->
                Box(Modifier.padding(inner)) {
                    ua.chudo.scanner.ui.home.HomeContent(ua.chudo.scanner.ui.home.HomeUi("ТРЦ «Приклад»", "Олена Приклад", at930, "", emptyList(), 0))
                }
            }
        }
    }

    @Test fun docs_profile_light() = paparazzi.snapshot {
        ChudoTheme(dark = false) {
            Surface(color = Mine.colors.bg) {
                ua.chudo.scanner.ui.profile.ProfileContent(
                    ua.chudo.scanner.ui.profile.ProfileUi(
                        name = "Олена Приклад", phone = "+380 00 000 00 00", city = "Одеса", shop = "ТРЦ «Приклад», 2 поверх",
                        stock = "2 400 позицій", theme = ThemeMode.LIGHT, admin = true, version = "3.2",
                    ),
                )
            }
        }
    }
}
