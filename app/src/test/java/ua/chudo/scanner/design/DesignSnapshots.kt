package ua.chudo.scanner.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ua.chudo.scanner.print.LabelFormat
import ua.chudo.scanner.print.LabelItem
import ua.chudo.scanner.print.LabelKind
import ua.chudo.scanner.print.LabelRenderer
import ua.chudo.scanner.print.Link
import ua.chudo.scanner.print.MonoImage
import ua.chudo.scanner.print.PROMO_PAPER
import ua.chudo.scanner.print.PrinterRecord
import ua.chudo.scanner.print.PrinterStatus
import ua.chudo.scanner.print.Template
import ua.chudo.scanner.data.DemoOrders
import ua.chudo.scanner.data.OrderStage
import ua.chudo.scanner.data.problem
import ua.chudo.scanner.ui.auth.LoginMineForm
import ua.chudo.scanner.ui.common.MainTab
import ua.chudo.scanner.ui.common.MineBottomBar
import ua.chudo.scanner.ui.orders.OrdersContent
import ua.chudo.scanner.ui.orders.OrdersUi
import ua.chudo.scanner.ui.auth.AuthPage
import ua.chudo.scanner.ui.print.PrintSheetContent
import ua.chudo.scanner.ui.print.PrintSheetUi
import ua.chudo.scanner.ui.print.PrinterLine
import ua.chudo.scanner.ui.print.PrintersActions
import ua.chudo.scanner.ui.print.PrintersContent
import ua.chudo.scanner.ui.print.PrintersUi
import ua.chudo.scanner.ui.profile.ThemePicker
import ua.chudo.scanner.ui.theme.ChudoTheme
import ua.chudo.scanner.ui.theme.Mine
import ua.chudo.scanner.ui.theme.ThemeMode

/**
 * Снимки экранов «Mine» и ценников для сверки с PNG из design/screens (папка снимков: app/src/test/snapshots).
 * Плюс главное для печати: штрихкод из настоящего Bitmap LabelRenderer после порога в 1 бит читается ZXing.
 */
class DesignSnapshots {
    @get:Rule
    val paparazzi = Paparazzi(
        // ≈ 393 dp в ширину — как макеты (390 dp).
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material.NoActionBar",
        maxPercentDifference = 0.0,
    )

    private val mfc = PrinterRecord("mfc", "Brother MFC-L2700DW", LabelKind.PLAIN, ip = "192.0.2.250")
    private val godexPc = PrinterRecord("pc", "Godex G500", LabelKind.STICKY, transport = Link.AGENT, agentId = "pc-kasa", printerName = "Godex G500")

    @Test fun printersMine() = paparazzi.snapshot {
        ChudoTheme(dark = true) {
            Surface(color = Mine.colors.bg) {
                PrintersContent(
                    PrintersUi(
                        records = listOf(mfc, godexPc), usb = emptyList(), agents = emptyList(), unknown = emptyList(),
                        status = mapOf("pc" to PrinterStatus(true, "Онлайн через комп'ютер"), "mfc" to PrinterStatus(false, "Не відповідає. Перевір, що принтер увімкнений, і натисни «Знайти знову».")),
                        main = mapOf(LabelKind.STICKY to "pc", LabelKind.PLAIN to null), scanning = false, testing = null,
                    ),
                    PrintersActions(onBatch = {}),
                )
            }
        }
    }

    @Test fun labelBatchRedesign() = paparazzi.snapshot {
        ChudoTheme(dark = false) {
            val item = ua.chudo.scanner.data.NetItem("Конструктор LEGO DUPLO", "BOC001", "10954", emptyMap())
            val first = ua.chudo.scanner.data.NetAvailability("5702016911114", item, null, null, emptyList(), null, null)
            val second = first.copy(barcode = "4820000000103", item = item.copy(name = "М’яч гумовий", article = "A-3"))
            ua.chudo.scanner.ui.stock.LabelBatchContent(listOf(first, second), setOf(first.barcode), Template.TAG_68x38)
        }
    }

    @Test fun printSheetMine() = paparazzi.snapshot {
        ChudoTheme(dark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0C))) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp), color = Mine.colors.sheet, contentColor = Mine.colors.text,
                ) {
                    Column(Modifier.padding(top = 28.dp)) {
                        PrintSheetContent(
                            PrintSheetUi(
                                subtitle = "LEGO Duplo Потяг із цифрами, 649 ₴", format = Template.TAG_68x38, promo = true,
                                oldPrice = "969", qty = 2, printer = PrinterLine("Brother MFC-L2700DW", true),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test fun loginMineDark() = login(dark = true)

    @Test fun printSheetPreferredOffline() = paparazzi.snapshot {
        ChudoTheme(dark = true) {
            Surface(color = Mine.colors.sheet) {
                PrintSheetContent(PrintSheetUi(
                    subtitle = "LEGO Duplo Потяг із цифрами, 649 ₴", format = Template.TAG_68x38,
                    promo = true, oldPrice = "969", qty = 2,
                    printer = PrinterLine("Brother MFC-L2700DW · 192.168.22.250", false),
                    error = "Brother MFC-L2700DW · 192.168.22.250 не відповідає",
                    alternative = "Brother HL-L2360DN · 192.168.22.251",
                ))
            }
        }
    }

    @Test fun loginMineLight() = login(dark = false)

    @Test fun authPending() = paparazzi.snapshot {
        ChudoTheme(dark = false) { ua.chudo.scanner.ui.auth.PendingPage("ЧО 22 · ТРЦ Gagarin Plaza") {} }
    }

    @Test fun authSplash() = paparazzi.snapshot {
        ChudoTheme(dark = false) { ua.chudo.scanner.ui.auth.AuthSplash("3.3.13") }
    }

    private val demoStores = mapOf(
        "22" to ua.chudo.scanner.data.StoreDoc("ЧО 22 · ТРЦ Gagarin Plaza", "Одеса", "s22"),
        "62" to ua.chudo.scanner.data.StoreDoc("ЧО 62 · ТЦ Рів’єра", "Одеса", null),
        "77" to ua.chudo.scanner.data.StoreDoc("ЧО 77 · ТЦ Острів", "Одеса", null),
        "69" to ua.chudo.scanner.data.StoreDoc("ЧО 69 · ТЦ Маяк", "Одеса", null),
        "5" to ua.chudo.scanner.data.StoreDoc("ЧО 5 · ТРЦ Victoria Gardens", "Львів", null),
    )

    @Test fun historyRedesign() = paparazzi.snapshot {
        val zone = java.time.ZoneId.of("Europe/Kyiv")
        val today = java.time.LocalDate.of(2026, 11, 26)
        fun at(day: java.time.LocalDate, h: Int, min: Int) = day.atTime(h, min).atZone(zone).toInstant().toEpochMilli()
        val events = listOf(
            ua.chudo.scanner.data.ShopActivity(ua.chudo.scanner.data.ActivityKind.PRINT, "Надруковано 2 цінники 68×38", "LEGO DUPLO Потяг · «Каса»", at(today, 10, 42)),
            ua.chudo.scanner.data.ShopActivity(ua.chudo.scanner.data.ActivityKind.ORDER, "Зібрано №100045790", "3 товари", at(today, 10, 18)),
            ua.chudo.scanner.data.ShopActivity(ua.chudo.scanner.data.ActivityKind.SCAN, "Скан: Crackin’ Eggs динозавр", "у магазині 12 шт", at(today, 10, 5)),
            ua.chudo.scanner.data.ShopActivity(ua.chudo.scanner.data.ActivityKind.PRINT, "Пакет: 34 цінники", "після завантаження 1С · «Каса»", at(today.minusDays(1), 18, 20)),
        )
        ChudoTheme(dark = false) { ua.chudo.scanner.ui.profile.ActivityContent(events, 0, false, {}, {}, zone, today) }
    }

    @Test fun myShopRedesign() = paparazzi.snapshot {
        fun member(uid: String, name: String, approved: Boolean, admin: Boolean = false) =
            ua.chudo.scanner.data.StaffMember(uid, name, "+380675551218", "s22", "ЧО 22", "odesa", approved, admin)
        val staff = listOf(
            member("1", "Ірина Савчук", false),
            member("2", "Олена Коваль", true, admin = true),
            member("3", "Андрій Мельник", true),
            member("4", "Світлана Бондар", true),
        )
        ChudoTheme(dark = false) {
            ua.chudo.scanner.ui.admin.MyShopContent("ЧО 22 · ТРЦ Gagarin Plaza", staff, null, null, false, { false }, {}, {}, {}, {})
        }
    }

    @Test fun temporaryPassword() = paparazzi.snapshot {
        ChudoTheme(dark = false) {
            ua.chudo.scanner.ui.auth.TemporaryPasswordForm("Олена Коваль", "", "", false, null, {}, {}, {}, {})
        }
    }

    @Test fun forgotRedesign() = paparazzi.snapshot {
        ChudoTheme(dark = false) {
            ua.chudo.scanner.ui.auth.ForgotForm(ua.chudo.scanner.ui.auth.ForgotUi("671234567", "ЧО 22 · ТРЦ Gagarin Plaza", false, null, false), {}, {}, {}, {})
        }
    }

    @Test fun forgotSent() = paparazzi.snapshot {
        ChudoTheme(dark = false) {
            ua.chudo.scanner.ui.auth.ForgotForm(ua.chudo.scanner.ui.auth.ForgotUi("671234567", "ЧО 22 · ТРЦ Gagarin Plaza", false, null, true), {}, {}, {}, {})
        }
    }

    @Test fun forgotTooSoon() = paparazzi.snapshot {
        ChudoTheme(dark = false) {
            ua.chudo.scanner.ui.auth.ForgotForm(
                ua.chudo.scanner.ui.auth.ForgotUi(
                    "671234567", "ЧО 22 · ТРЦ Gagarin Plaza", false,
                    ua.chudo.scanner.data.ResetRequestResult.TOO_SOON.problem(), false,
                ),
                {}, {}, {}, {},
            )
        }
    }

    @Test fun myShopResets() = paparazzi.snapshot {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.util.Date.from(java.time.LocalDate.now(zone).atTime(10, 42).atZone(zone).toInstant())
        val resets = listOf(
            ua.chudo.scanner.data.PasswordRequest("r1", "+380671234567", now, "u3", "Андрій Мельник"),
            ua.chudo.scanner.data.PasswordRequest("r2", "+380509998877", now, null, null),
        )
        val staff = listOf(ua.chudo.scanner.data.StaffMember("u3", "Андрій Мельник", "+380671234567", "s22", "ЧО 22", "odesa", true, false))
        ChudoTheme(dark = false) {
            ua.chudo.scanner.ui.admin.MyShopContent(
                "ЧО 22 · ТРЦ Gagarin Plaza", staff, null, null, false, { false }, {}, {}, {}, {}, resets = resets,
            )
        }
    }

    @Test fun myShopLog() = paparazzi.snapshot {
        val zone = java.time.ZoneId.systemDefault()
        fun at(daysAgo: Long, h: Int, min: Int) = java.util.Date.from(java.time.LocalDate.now(zone).minusDays(daysAgo).atTime(h, min).atZone(zone).toInstant())
        fun member(uid: String, name: String, admin: Boolean = false) =
            ua.chudo.scanner.data.StaffMember(uid, name, "+380675551218", "s22", "ЧО 22", "odesa", true, admin)
        val staff = listOf(member("a", "Олена Коваль", admin = true), member("d", "Денис Т."), member("i", "Ірина Савчук"))
        val log = listOf(
            ua.chudo.scanner.data.AdminLogEntry("1", "a", "approve", "d", "s22", "", at(0, 10, 42)),
            ua.chudo.scanner.data.AdminLogEntry("2", "a", "passwordReset", "i", "s22", "", at(0, 9, 15)),
            ua.chudo.scanner.data.AdminLogEntry("3", "sup", "pairPc", "КАСА-22", "s22", "", at(1, 18, 20)),
        )
        ChudoTheme(dark = false) {
            ua.chudo.scanner.ui.admin.MyShopContent(
                "ЧО 22 · ТРЦ Gagarin Plaza", staff, null, null, false, { false }, {}, {}, {}, {}, log = log,
            )
        }
    }

    @Test fun tempPasswordDialog() = paparazzi.snapshot {
        ChudoTheme(dark = false) {
            Box(Modifier.fillMaxSize().background(Mine.colors.bg)) {
                ua.chudo.scanner.ui.admin.TempPasswordDialog(
                    ua.chudo.scanner.data.PasswordRequest("r1", "+380671234567", null, "u3", "Андрій Мельник"), "kP7mQ2xRta",
                ) {}
            }
        }
    }
    private val demoCategories = listOf(
        "Конструктори" to 2234, "Ляльки і пупси" to 1554, "Дитячий транспорт" to 1965, "М’які іграшки" to 1601,
        "Іграшки для творчості" to 2111, "Настільні ігри та пазли" to 1632, "Для активного відпочинку" to 1143, "Для малюків" to 2422,
    ).mapIndexed { i, (name, n) -> uniffi.chudo_core.Category("c$i", name, "k$i", n.toUInt(), emptyList()) }

    private fun catalog(state: ua.chudo.scanner.ui.results.CatalogState, dark: Boolean = false) = paparazzi.snapshot {
        ChudoTheme(dark = dark) { ua.chudo.scanner.ui.results.CatalogContent(state, {}, {}, {}) }
    }

    @Test fun catalogRedesign() = catalog(ua.chudo.scanner.ui.results.CatalogState.Done(demoCategories))
    @Test fun catalogRedesignDark() = catalog(ua.chudo.scanner.ui.results.CatalogState.Done(demoCategories), dark = true)
    @Test fun catalogOffline() = catalog(
        ua.chudo.scanner.ui.results.CatalogState.Failed(ua.chudo.scanner.ui.common.UiError("Немає інтернету", "Перевір підключення і спробуй ще раз.")),
    )

    @Test fun orderPickRedesign() = paparazzi.snapshot {
        val order = DemoOrders.load("").first { it.stage == OrderStage.PICKING }
        ChudoTheme(dark = false) { ua.chudo.scanner.ui.orders.OrderPickContent(order, {}, {}, {}, {}, {}, {}) }
    }

    @Test fun networkAvailabilityRedesign() = paparazzi.snapshot {
        val item = ua.chudo.scanner.data.NetItem(
            "LEGO DUPLO Потяг із цифрами", "BOC022446", "10954", mapOf("22" to 3.0, "62" to 5.0, "77" to 2.0, "69" to 1.0, "5" to 6.0),
        )
        ChudoTheme(dark = false) {
            ua.chudo.scanner.ui.stock.NetworkAvailabilityContent(
                ua.chudo.scanner.ui.stock.networkCities(item, demoStores, "22", "ЧО 22 · ТРЦ Gagarin Plaza"),
                "LEGO DUPLO Потяг із цифрами", todayAt930(), {},
            )
        }
    }

    @Test fun stockOverviewRedesign() = paparazzi.snapshot {
        val rows = listOf(
            ua.chudo.scanner.data.SbStockRow("5702016911114", "LEGO DUPLO Потяг із цифрами", article = "10954", q = mapOf("22" to 1.0, "62" to 5.0)),
            ua.chudo.scanner.data.SbStockRow("6903317562084", "Crackin’ Eggs динозавр у яйці", article = "SK014D2", q = mapOf("62" to 4.0)),
        )
        ChudoTheme(dark = false) {
            Column(Modifier.fillMaxSize().background(Mine.colors.bg)) {
                ua.chudo.scanner.ui.stock.StockTitle("ЧО 22 · ТРЦ Gagarin Plaza")
                ua.chudo.scanner.ui.stock.StockBanner(
                    ua.chudo.scanner.data.ShopStockInfo(todayAt930(), "Марина", 21870), 46, Modifier.padding(horizontal = 16.dp),
                )
                ua.chudo.scanner.ui.stock.StockSegments(
                    ua.chudo.scanner.ui.stock.StockTab.LOW,
                    mapOf(ua.chudo.scanner.ui.stock.StockTab.PROMO to 48L, ua.chudo.scanner.ui.stock.StockTab.NEW to 12L), {},
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
                )
                ua.chudo.scanner.ui.stock.lowStock(rows, "22", demoStores).forEach { ua.chudo.scanner.ui.stock.LowStockRow(it) {} }
                ua.chudo.scanner.ui.stock.StockSection("Для адміна залишків")
                ua.chudo.scanner.ui.stock.StockLinkRow(ua.chudo.scanner.R.drawable.ic_redesign_file_spreadsheet, "Завантажити звіт 1С", "«Ведомость по партиям», .xlsx") {}
            }
        }
    }

    private fun login(dark: Boolean) = paparazzi.snapshot {
        ChudoTheme(dark = dark) {
            AuthPage {
                LoginMineForm("671234567", "chudo2026", null, false, {}, {}, {}, {}, {})
            }
        }
    }

    @Test fun themeMine() = paparazzi.snapshot {
        ChudoTheme(dark = true) {
            Surface(color = Mine.colors.bg) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                    Text("Профіль", fontSize = 26.sp, color = Mine.colors.text)
                    Box(Modifier.padding(top = 24.dp)) { ThemePicker(ThemeMode.DARK) {} }
                }
            }
        }
    }

    @Test fun ordersDark() = orders(dark = true)

    @Test fun ordersLight() = orders(dark = false)

    private fun orders(dark: Boolean) = paparazzi.snapshot {
        ChudoTheme(dark = dark) {
            Surface(color = Mine.colors.bg) {
                OrdersContent(OrdersUi(shop = "Gagarin Plaza", orders = DemoOrders.load(""), tab = OrderStage.NEW, demo = true))
            }
        }
    }

    private val recent = listOf(
        ua.chudo.scanner.data.HistoryItem("lego", "LEGO Duplo Потяг із цифрами: вчимося рахувати", "10954", null, 64900),
        ua.chudo.scanner.data.HistoryItem("hw", "Hot Wheels Форсаж, в асортименті", "HNW46", null, 33900),
        ua.chudo.scanner.data.HistoryItem("xs", "X-Shot бластер Purple Blaze", "36882R", null, 79900),
        ua.chudo.scanner.data.HistoryItem("trefl", "Пазл Trefl «Венеція» 1000", "10744", null, 38900),
    )

    /** Кошик покупця: акція, кілька штук, товар без ціни, ще шукаємо, більше ніж є в магазині. */
    @Test fun cartMine() = paparazzi.snapshot {
        ChudoTheme(dark = true) {
            Surface(color = Mine.colors.bg) {
                ua.chudo.scanner.ui.cart.CartContent(listOf(
                    ua.chudo.scanner.data.CartLine("4823077601999", "Шукаю…", loading = true),
                    ua.chudo.scanner.data.CartLine(
                        "4823077601234", "Конструктор LEGO Duplo Потяг із цифрами – вчимося рахувати (10954)", article = "10954",
                        code1C = "BOC022446", priceKop = 64900, regularKop = 96900, qty = 2, inShop = 5.0,
                    ),
                    ua.chudo.scanner.data.CartLine("4820000000103", "М'яч гумовий 23 см, рожевий", article = "A-3", code1C = "BOC100003", priceKop = 12950, qty = 3, inShop = 1.0),
                    ua.chudo.scanner.data.CartLine("4820000000999", "Пазл 1000 елементів", code1C = "ODR000123"),
                ))
            }
        }
    }

    @Test fun cartRedesignEmpty() = paparazzi.snapshot {
        ChudoTheme(dark = false) {
            ua.chudo.scanner.ui.cart.CartContent(emptyList())
        }
    }

    @Test fun cartRedesignCashier() = paparazzi.snapshot {
        ChudoTheme(dark = true) {
            ua.chudo.scanner.ui.cart.CashierContent(
                line = ua.chudo.scanner.data.CartLine("5702016911114", "Конструктор LEGO DUPLO", qty = 2),
                index = 0, count = 2, onClose = {}, onPrevious = null, onNext = {},
            )
        }
    }

    /** Залишки мережі: свій магазин великим шрифтом і магазини міста — як після скану. */
    @Test fun networkStockMine() = paparazzi.snapshot {
        val item = ua.chudo.scanner.data.NetItem(
            "Конструктор LEGO Duplo Потяг із цифрами", "BOC022446", "10954",
            mapOf("22" to 3.0, "62" to 5.0, "69" to 1.0, "77" to 2.0),
        )
        val av = ua.chudo.scanner.data.NetAvailability(
            "5702017417912", item, ownCode = "22", own = 3.0,
            city = listOf(
                ua.chudo.scanner.data.StoreQty("62", "ЧО 62 Одеса (ТЦ Рів'єра)", 5.0),
                ua.chudo.scanner.data.StoreQty("77", "ЧО 77 Одеса (ТЦ Острів)", 2.0),
                ua.chudo.scanner.data.StoreQty("69", "ЧО 69 Одеса (ТЦ Маяк)", 1.0),
            ),
            cityName = "Одеса", updatedAt = java.util.Calendar.getInstance().apply { set(2026, 10, 25, 9, 30, 0) }.time,
        )
        ChudoTheme(dark = true) {
            Surface(color = Mine.colors.bg) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                    Text("Залишки магазину", fontSize = 26.sp, color = Mine.colors.text)
                    Text("ЧО 22 · ТРЦ Gagarin Plaza", fontSize = 15.sp, color = Mine.colors.textSecondary)
                    ua.chudo.scanner.ui.stock.NetworkStockCard(av, Modifier.padding(top = 16.dp))
                }
            }
        }
    }

    @Test fun scannerReady() = scanChrome(hit = false)

    @Test fun scannerRecognized() = scanChrome(hit = true)

    private fun scanChrome(hit: Boolean) = paparazzi.snapshot {
        ChudoTheme(dark = true) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF49453D))) {
                ua.chudo.scanner.ui.scan.ScannerChrome(
                    hit = hit,
                    onClose = {},
                    onTorch = {},
                    onManual = {},
                    onSearch = {},
                    onCartMode = {},
                    onPriceMode = {},
                )
            }
        }
    }

    @Test fun newProductsRedesign() = paparazzi.snapshot {
        ChudoTheme(dark = false) {
            Surface(color = Mine.colors.bg) {
                Column(Modifier.fillMaxSize()) {
                    ua.chudo.scanner.ui.stock.HighlightsHeader(
                        ua.chudo.scanner.data.HighlightKind.NEW, onBack = {},
                    )
                    ua.chudo.scanner.ui.stock.HighlightProductRow(
                        ua.chudo.scanner.data.ProductHighlight(
                            barcode = "5702016911114", article = "10954", name = "Конструктор LEGO DUPLO",
                        ),
                        kind = ua.chudo.scanner.data.HighlightKind.NEW, onClick = {},
                    )
                }
            }
        }
    }

    @Test fun promoRedesign() = paparazzi.snapshot {
        ChudoTheme(dark = true) {
            Surface(color = Mine.colors.bg) {
                Column(Modifier.fillMaxSize()) {
                    ua.chudo.scanner.ui.stock.HighlightsHeader(
                        ua.chudo.scanner.data.HighlightKind.PROMO, onBack = {},
                    )
                    ua.chudo.scanner.ui.stock.PromoCampaignRow(
                        ua.chudo.scanner.data.PromoCampaign(
                            "−20% на конструктори LEGO City та Friends", "LEGO", "Дистриб’ютор", "20.11.2026", "30.11.2026", "Діє",
                            listOf("Умови" to "Знижка на касі, не сумується з іншими акціями", "Механіка" to "Ціна на цінник — акційна"),
                        ),
                        Modifier.padding(16.dp), onClick = {},
                    )
                    ua.chudo.scanner.ui.stock.PromoCampaignRow(
                        ua.chudo.scanner.data.PromoCampaign(
                            "Знижки на конструктори", "LEGO", "", "01.11.2026", "15.11.2026", "Завершено", emptyList(),
                        ),
                        Modifier.padding(horizontal = 16.dp), onClick = {},
                    )
                }
            }
        }
    }

    @Test fun recentProducts() = paparazzi.snapshot {
        ChudoTheme(dark = false) {
            ua.chudo.scanner.ui.home.HistoryContent(recent, onBack = {}, onClear = {}, onOpenProduct = {})
        }
    }

    @Test fun recentProductsEmpty() = paparazzi.snapshot {
        ChudoTheme(dark = true) {
            ua.chudo.scanner.ui.home.HistoryContent(emptyList(), onBack = {}, onClear = {}, onOpenProduct = {})
        }
    }

    @Test fun homeLight() = home(false)

    @Test fun homeDark() = home(true)

    private fun todayAt930(): java.util.Date = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 30); set(java.util.Calendar.SECOND, 0)
    }.time

    /** Пакет цінників із «Залишків»: кількість — для кожного товару. */
    @Test fun printSheetBatch() = paparazzi.snapshot {
        ChudoTheme(dark = true) {
            Surface(color = Mine.colors.sheet) {
                PrintSheetContent(PrintSheetUi(
                    subtitle = "Товарів: 12 · кількість — для кожного", format = Template.TAG_68x38, promo = false, oldPrice = null,
                    qty = 1, printer = PrinterLine("Brother MFC-L2700DW", true), items = 12,
                ))
            }
        }
    }

    private fun home(dark: Boolean) = paparazzi.snapshot {
        ChudoTheme(dark = dark) {
            androidx.compose.material3.Scaffold(
                containerColor = Mine.colors.bg,
                bottomBar = { MineBottomBar(MainTab.HOME, onTab = {}, onScan = {}) },
            ) { inner ->
                Box(Modifier.padding(inner)) {
                    ua.chudo.scanner.ui.home.HomeContent(
                        // Сьогодні о 09:30 — щоб знімок не залежав від часу запуску.
                        ua.chudo.scanner.ui.home.HomeUi("Одеса", "ЧО 22 · ТРЦ Gagarin Plaza", todayAt930(), "", recent, 3, cartUnits = 4, newCount = 12, promoCount = 28, greeting = "Доброго ранку, Олено"),
                        onHighlights = {},
                    )
                }
            }
        }
    }

    @Test fun scannerSettingsRedesign() = paparazzi.snapshot {
        ChudoTheme(dark = true) {
            ua.chudo.scanner.ui.profile.ScannerSettingsContent(
                options = ua.chudo.scanner.data.ScannerOptions(sound = false, vibrate = true, continuous = true),
                onBack = {}, onOptions = {}, onOffline = {},
                offline = ua.chudo.scanner.ui.profile.OfflineSettingsUi(rows = 21870, loading = false),
            )
        }
    }

    @Test fun teamLight() = team(false)

    @Test fun teamDark() = team(true)

    private fun team(dark: Boolean) = paparazzi.snapshot {
        ChudoTheme(dark = dark) {
            Surface(color = Mine.colors.bg) {
                val people = listOf(
                    ua.chudo.scanner.data.TeamMember("1", "Олена Коваль", ua.chudo.scanner.data.JobTitle.MANAGER, admin = true),
                    ua.chudo.scanner.data.TeamMember("2", "Дмитро Савчук", ua.chudo.scanner.data.JobTitle.SENIOR, admin = false),
                    ua.chudo.scanner.data.TeamMember("3", "Ірина Мельник", ua.chudo.scanner.data.JobTitle.SENIOR, admin = true),
                    ua.chudo.scanner.data.TeamMember("4", "Андрій Бондар", ua.chudo.scanner.data.JobTitle.SELLER, admin = false),
                    ua.chudo.scanner.data.TeamMember("5", "Марія Ткачук", ua.chudo.scanner.data.JobTitle.SELLER, admin = false),
                    ua.chudo.scanner.data.TeamMember("6", "Денис Тарасенко", null, admin = false),
                )
                ua.chudo.scanner.ui.profile.TeamContent(
                    "ЧО 22 · ТРЦ Gagarin Plaza", meUid = "4",
                    ui = ua.chudo.scanner.ui.profile.TeamUi.Ready(ua.chudo.scanner.data.Team.groups(people), people.size),
                )
            }
        }
    }

    @Test fun profileDark() = profile(true)

    @Test fun profileLight() = profile(false)

    private fun profile(dark: Boolean) = paparazzi.snapshot {
        ChudoTheme(dark = dark) {
            Surface(color = Mine.colors.bg) {
                ua.chudo.scanner.ui.profile.ProfileContent(
                    ua.chudo.scanner.ui.profile.ProfileUi(
                        name = "Олена Коваль", phone = "+380 67 555 12 18", city = "Одеса",
                        shop = "вул. Генуезька, 5/2, ТРЦ \"Gagarinn Plaza\", друга черга, 3 поверх",
                        theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, admin = true, role = "Старший продавець · Адмін магазину",
                    ),
                )
            }
        }
    }

    @Test fun tabletOrdersRedesign() {
        paparazzi.unsafeUpdateConfig(deviceConfig = tabletDevice())
        paparazzi.snapshot {
            ChudoTheme(dark = false) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                    ua.chudo.scanner.ui.common.TabletRail(MainTab.ORDERS, printing = false, onTab = {}, onScan = {}, onPrint = {})
                    Box(Modifier.weight(1f)) {
                        ua.chudo.scanner.ui.orders.TabletOrders(
                            OrdersUi(shop = "Gagarin Plaza", orders = DemoOrders.load(""), tab = OrderStage.NEW, demo = true),
                            onTab = {}, onOrder = {}, onScan = {},
                        )
                    }
                }
            }
        }
    }

    private fun tabletDevice() = DeviceConfig.PIXEL_5.copy(
        screenWidth = 1180, screenHeight = 820, density = com.android.resources.Density.MEDIUM,
        orientation = com.android.resources.ScreenOrientation.LANDSCAPE,
    )

    @Test fun productLikeSite() = productSnapshot(tablet = false)

    @Test fun tabletProductRedesign() {
        paparazzi.unsafeUpdateConfig(deviceConfig = tabletDevice())
        productSnapshot(tablet = true)
    }

    private fun productSnapshot(tablet: Boolean) = paparazzi.snapshot {
        val lego = uniffi.chudo_core.ProductDetails(
            1L, "10954", "Конструктор LEGO Duplo Потяг із цифрами – вчимося рахувати (10954)", "lego", ".html",
            uniffi.chudo_core.StockStatus.IN_STOCK, null, uniffi.chudo_core.Price(649.0, 969.0, 33.0, "UAH"), emptyList(),
            "Цей ігровий набір для дошкільнят включає рухомий потяг із 10 кольоровими цифровими цеглинками, 2 фігурками та безліччю аксесуарів.",
            listOf("Для малюків", "Дитячі іграшки", "Розвиваючі іграшки"),
        )
        val shops = ua.chudo.scanner.ui.product.ShopsState.Loaded(
            listOf(
                uniffi.chudo_core.ShopAvailability("вул. Генуезька, 5/2, ТРЦ Gagarinn Plaza", true),
                uniffi.chudo_core.ShopAvailability("с. Фонтанка, Південна дорога, 101А, ТРЦ RIVIERA", true),
                uniffi.chudo_core.ShopAvailability("вул. Новощіпний ряд 2, ТРЦ Острів", false),
            ),
        )
        ChudoTheme(dark = false) {
            Surface(color = Mine.colors.bg) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                    if (tablet) {
                        ua.chudo.scanner.ui.common.TabletRail(MainTab.HOME, printing = false, onTab = {}, onScan = {}, onPrint = {})
                        Box(Modifier.size(width = 380.dp, height = 820.dp).background(Mine.colors.card)) {
                            Text("Пошук", Modifier.padding(16.dp), color = Mine.colors.text)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f)) {
                        ua.chudo.scanner.ui.product.ProductPage(
                            lego, shops, "вул. Генуезька, 5/2, ТРЦ Gagarinn Plaza",
                            ua.chudo.scanner.data.StockItem("10954", "5702016911114", "LEGO Duplo", 9.0), "Одеса", "5702016911114",
                            tablet = tablet,
                            printPanel = if (tablet) ({
                                PrintSheetContent(
                                    PrintSheetUi(
                                        subtitle = "LEGO DUPLO · 649 ₴", format = Template.TAG_68x38, promo = true,
                                        oldPrice = "969", qty = 1, printer = PrinterLine("Brother · Каса", true),
                                    ),
                                    scroll = false,
                                )
                            }) else null,
                            net = ua.chudo.scanner.data.NetAvailability(
                                barcode = "5702016911114",
                                item = ua.chudo.scanner.data.NetItem(
                                    "LEGO Duplo", "BOC001", "10954", mapOf("22" to 9.0, "8" to 1.0, "15" to 4.0),
                                ),
                                ownCode = "22",
                                own = 9.0,
                                city = listOf(
                                    ua.chudo.scanner.data.StoreQty("15", "ЧО 15 · ТРЦ RIVIERA", 4.0),
                                    ua.chudo.scanner.data.StoreQty("8", "ЧО 8 · ТРЦ Острів", 1.0),
                                ),
                                cityName = "Одеса",
                                updatedAt = todayAt930(),
                            ),
                        )
                    }
                        ua.chudo.scanner.ui.product.ProductActions(onPrint = {}, onCart = {})
                    }
                }
            }
        }
    }

    @Test fun shelfTagPrices() = paparazzi.snapshot {
        ChudoTheme(dark = true) {
            Surface(color = Mine.colors.bg) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    ua.chudo.scanner.ui.common.ShelfTagPrice(649.0, null)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                    ua.chudo.scanner.ui.common.ShelfTagPrice(649.0, 969.0)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                    ua.chudo.scanner.ui.common.ShelfTagPrice(17999.0, 24075.0)
                }
            }
        }
    }

    // ---------- Ценники: превью = печать ----------

    private val lego = LabelItem(
        barcode = "5702016911114", article = "10954", brand = "LEGO",
        name = "Конструктор Duplo Потяг із цифрами, вчимося рахувати", price = 649.0,
    )
    private val crayola = LabelItem(barcode = "2000999159305", article = "75-7170", code = "BOC334610", brand = "Crayola", name = "CW Набір для творчості «Gabby's Dollhouse»", price = 1599.0)
    private val eggs = LabelItem(
        barcode = "6903317562084", article = "SK014D2", code = "BOC293254", brand = "Crackin Eggs",
        name = "М'яка іграшка SK014D2 динозавр Jurassic Friends, міні, у яйці, 4 види, 6 шт. в диспл., бат.", price = 339.0, oldPrice = 559.0,
    )
    private val jeep = LabelItem(
        barcode = "6903317612383", article = "JJ2088EBLR-2(4WD)", code = "BOC307585", brand = "Bambi Racer",
        name = "Джип JJ2088EBLR-2(4WD) 2.4G, передн. 2*25W, задн. 2*35W, 1 акум.*12V9AH, колеса EVA, шкір. сид.", price = 15885.0,
    )
    private val jeepPromo = LabelItem(
        barcode = "2000999000003", article = "JS3168EBLR-18(24V)", brand = "Bambi Racer",
        name = "Джип JS3168EBLR-18(24V) 2,4G, 2 мотори*120W, 1 акум.*24V7AH, колеса EVA, шкір. сид., чорний камуфл.", price = 17999.0, oldPrice = 24075.0,
    )

    @Test fun label40x24() = tag(lego, LabelFormat.Label40x24)

    @Test fun tag68x38() = tag(crayola, LabelFormat.Tag68x38)

    @Test fun tag68x38Promo() = tag(eggs, LabelFormat.Tag68x38Promo)

    @Test fun tagBig() = tag(jeep, LabelFormat.TagBig)

    @Test fun tagBigPromo() = tag(jeepPromo, LabelFormat.TagBigPromo)

    @Test fun tagBig140x100() = tag(jeep, LabelFormat.TagBig140)

    @Test fun tagBig140x100Promo() = tag(jeepPromo, LabelFormat.TagBig140Promo)

    /** Альбомний аркуш A4 68×38: 4×5 = 20 цінників у друкованій області Brother. */
    @Test fun sheet68x38Landscape() = sheet(Template.TAG_68x38, List(20) { crayola })

    /** Альбомний аркуш A4 140×100: 2×2 = 4. */
    @Test fun sheet140x100Landscape() = sheet(Template.BIG_140x100, List(4) { jeep })

    private fun sheet(t: Template, cells: List<LabelItem>) {
        val bmp = LabelRenderer(paparazzi.context).sheetPreview(cells, t, promo = false, dpi = 60f, landscape = true).asImageBitmap()
        paparazzi.snapshot {
            Box(Modifier.background(Color(0xFF808080)).padding(8.dp)) { Image(bmp, null) }
        }
    }

    /** Ценник в масштабе макета (8 px/мм) на бумаге: белой или жёлтой для акции. */
    private fun tag(item: LabelItem, f: LabelFormat) {
        val r = LabelRenderer(paparazzi.context)
        // Для сверки 1:1 с PNG макета (они в 2×): тот же рендер на 406.4 dpi с бумагой — в build/design-compare.
        val big = r.render(item, f, 406.4f)
        val paper = android.graphics.Bitmap.createBitmap(big.width, big.height, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(paper).apply { drawColor(if (f.promo) PROMO_PAPER else android.graphics.Color.WHITE); drawBitmap(big, 0f, 0f, null) }
        val dir = java.io.File("build/design-compare").apply { mkdirs() }
        java.io.FileOutputStream(java.io.File(dir, "${f.name}.png")).use { paper.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val bmp = r.render(item, f, 203.2f).asImageBitmap()
        paparazzi.snapshot {
            Box(Modifier.background(Color(0xFF808080)).padding(8.dp)) {
                Image(bmp, null, Modifier.clip(RoundedCornerShape(0.dp)).background(if (f.promo) Color(PROMO_PAPER) else Color.White))
            }
        }
    }

    /** Настоящий Bitmap печати (Godex 203 dpi, Brother 300 dpi) → 1 бит → ZXing читает тот же EAN-13. */
    @Test fun printedBarcodesDecode() {
        val r = LabelRenderer(paparazzi.context)
        val cases = listOf(
            Triple(lego, LabelFormat.Label40x24, LabelRenderer.GODEX_DPI),
            Triple(crayola, LabelFormat.Tag68x38, LabelRenderer.SHEET_DPI),
            Triple(eggs, LabelFormat.Tag68x38Promo, LabelRenderer.SHEET_DPI),
            Triple(jeep, LabelFormat.TagBig, LabelRenderer.SHEET_DPI),
            Triple(jeepPromo, LabelFormat.TagBigPromo, LabelRenderer.SHEET_DPI),
            Triple(jeep, LabelFormat.TagBig140, LabelRenderer.SHEET_DPI),
        )
        for ((item, f, dpi) in cases) {
            val mono = r.mono(item, f, dpi)
            // На ценниках A4 штрихкод вертикальный (читается снизу вверх): поворачиваем на 90° по часовой.
            val img = if (f == LabelFormat.Label40x24) mono else rotateClockwise(mono)
            assertEquals("$f @ $dpi dpi", item.barcode, decode(img))
        }
    }

    private fun rotateClockwise(src: MonoImage): MonoImage =
        MonoImage.of(src.height, src.width) { x, y -> src.isBlack(y, src.height - 1 - x) }

    private fun decode(img: MonoImage): String {
        val px = IntArray(img.width * img.height) { i -> if (img.isBlack(i % img.width, i / img.width)) 0xFF000000.toInt() else -1 }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(img.width, img.height, px)))
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13), DecodeHintType.TRY_HARDER to true)
        return MultiFormatReader().decode(bitmap, hints).text
    }
}
