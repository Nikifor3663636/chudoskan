package ua.chudo.scanner.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import ua.chudo.scanner.ui.auth.LoginMineForm
import ua.chudo.scanner.ui.common.MainTab
import ua.chudo.scanner.ui.common.MineBottomBar
import ua.chudo.scanner.ui.orders.OrdersContent
import ua.chudo.scanner.ui.orders.OrdersUi
import ua.chudo.scanner.ui.auth.LoginMineShell
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
                    PrintersActions(),
                )
            }
        }
    }

    @Test fun printSheetMine() = paparazzi.snapshot {
        ChudoTheme(dark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0C))) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = Mine.colors.sheet, contentColor = Mine.colors.text,
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

    @Test fun loginMineLight() = login(dark = false)

    private fun login(dark: Boolean) = paparazzi.snapshot {
        ChudoTheme(dark = dark) {
            LoginMineShell(onToggleTheme = {}) {
                LoginMineForm("671234567", "", null, false, {}, {}, {}, {}, {})
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
            androidx.compose.material3.Scaffold(
                containerColor = Mine.colors.bg,
                bottomBar = { MineBottomBar(MainTab.ORDERS, newOrders = 3, onTab = {}, onScan = {}) },
            ) { inner ->
                Box(Modifier.padding(inner)) {
                    OrdersContent(OrdersUi(shop = "Gagarin Plaza", orders = DemoOrders.load(""), tab = OrderStage.NEW, demo = true))
                }
            }
        }
    }

    private val recent = listOf(
        ua.chudo.scanner.data.HistoryItem("lego", "LEGO Duplo Потяг із цифрами: вчимося рахувати", "10954", null),
        ua.chudo.scanner.data.HistoryItem("hw", "Hot Wheels Форсаж, в асортименті", "HNW46", null),
        ua.chudo.scanner.data.HistoryItem("xs", "X-Shot бластер Purple Blaze", "36882R", null),
    )

    @Test fun homeLight() = home(false)

    @Test fun homeDark() = home(true)

    private fun home(dark: Boolean) = paparazzi.snapshot {
        ChudoTheme(dark = dark) {
            androidx.compose.material3.Scaffold(
                containerColor = Mine.colors.bg,
                bottomBar = { MineBottomBar(MainTab.SEARCH, newOrders = 3, onTab = {}, onScan = {}) },
            ) { inner ->
                Box(Modifier.padding(inner)) {
                    ua.chudo.scanner.ui.home.HomeContent(
                        ua.chudo.scanner.ui.home.HomeUi("Gagarin Plaza", "Вакаренко Микита", java.util.Date(), "", recent, 3),
                    )
                }
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
                        name = "Вакаренко Микита", phone = "+380 96 232 09 33", city = "Одеса", shop = "Gagarin Plaza",
                        stock = "2 418 позицій", theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, admin = true,
                    ),
                )
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
