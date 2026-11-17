package ua.chudo.scanner.print

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.chudo.scanner.ui.print.printButtonText
import java.io.ByteArrayOutputStream

class PrinterLanguagesTest {

    // ---------- EAN-13 ----------

    @Test fun ean13CheckDigit() {
        assertEquals(4, Ean13.checkDigit("570201691111")) // LEGO с макета: 5702016911114
        assertEquals(1, Ean13.checkDigit("400638133393")) // пример из GS1: 4006381333931
        assertEquals(0, Ean13.checkDigit("482000000000"))
        assertEquals(3, Ean13.checkDigit("200099900000")) // макет TagBigPromo: 2000999000003
    }

    @Test fun ean13NormalizeAddsOrChecksDigit() {
        assertEquals("5702016911114", Ean13.normalize("570201691111"))
        assertEquals("5702016911114", Ean13.normalize("5702016911114"))
        assertNull(Ean13.normalize("5702016911115")) // неверная контрольная
        assertNull(Ean13.normalize("12345"))
    }

    @Test fun ean13GuardsAndLength() {
        val m = Ean13.modules("5702016911114")
        assertEquals(95, m.size)
        // Края 101, середина 01010.
        assertEquals(listOf(true, false, true), m.take(3))
        assertEquals(listOf(false, true, false, true, false), m.slice(45..49))
        assertEquals(listOf(true, false, true), m.takeLast(3))
        assertEquals(210, Ean13.lengthDots(2)) // Godex: 105 модулей × 2 точки
        assertEquals(315, Ean13.lengthDots(3)) // Brother 300 dpi
    }

    /** Те же отрезки, что рисует LabelRenderer (целые точки, без сглаживания), читаются ZXing как тот же код. */
    @Test fun barcodeDecodesWithZxing() {
        for (code in listOf("5702016911114", "4823077601231", "2000999000003", "6903317562084")) {
            for (module in listOf(2, 3)) {
                val img = barcodeImage(code, module, height = 40 * module)
                assertEquals("модуль $module", code, decode(img))
            }
        }
    }

    @Test fun barcodeBarsAreWholeModules() {
        val bars = Ean13.bars("5702016911114", 2)
        assertTrue(bars.all { it.first % 2 == 0 && (it.last + 1) % 2 == 0 })
        assertEquals(Ean13.QUIET * 2, bars.first().first) // штрихи начинаются после тихой зоны
    }

    private fun barcodeImage(code: String, module: Int, height: Int): MonoImage {
        val bars = Ean13.bars(code, module)
        return MonoImage.of(Ean13.lengthDots(module), height) { x, _ -> bars.any { x in it } }
    }

    private fun decode(img: MonoImage): String {
        val px = IntArray(img.width * img.height) { i -> if (img.isBlack(i % img.width, i / img.width)) 0xFF000000.toInt() else -1 }
        val bitmap = BinaryBitmap(GlobalHistogramBinarizer(RGBLuminanceSource(img.width, img.height, px)))
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13), DecodeHintType.TRY_HARDER to true)
        return MultiFormatReader().decode(bitmap, hints).text
    }

    // ---------- EZPL (Godex) ----------

    @Test fun ezplHeaderBytes() {
        // Самотест Godex магазина: ^W40 ^Q24,2. Плотность и скорость не задавали — команд ^H/^S нет.
        assertEquals("^Q24,2$CRLF^W40$CRLF^P2$CRLF^L$CRLF", EzplGraphic().header(2))
        assertEquals("^Q24,2$CRLF^W40$CRLF^H05$CRLF^S2$CRLF^P1$CRLF^L$CRLF", EzplGraphic(EzplSettings(darkness = 5, speed = 2)).header(1))
        assertEquals("^Q24,0,0$CRLF^W40$CRLF^P1$CRLF^L$CRLF", EzplGraphic(EzplSettings(gapMm = 0)).header(1))
    }

    @Test fun ezplLabelIsHeaderGraphicAndEnd() {
        val img = MonoImage(8, 1, byteArrayOf(0xF0.toByte()))
        val expected = ByteArrayOutputStream().apply {
            write("^Q24,2$CRLF^W40$CRLF^P3$CRLF^L${CRLF}Q0,0,1,1$CRLF".toByteArray())
            write(0xF0) // бит 1 — точка печатается
            write("${CRLF}E$CRLF".toByteArray())
        }.toByteArray()
        assertArrayEquals(expected, EzplGraphic().render(PrintJob(listOf(PrintPage(img, 3)))))
    }

    @Test fun ezplClampsSettings() {
        assertTrue(EzplGraphic(EzplSettings(darkness = 40, speed = 99)).header(0).contains("^H19$CRLF^S7$CRLF^P1$CRLF"))
    }

    @Test fun ezplFullLabelSize() {
        // 40×24 мм при 203 dpi: 320×192 точек = 40 байт × 192 строки.
        val img = MonoImage(320, 192, ByteArray(40 * 192))
        val out = EzplGraphic().render(PrintJob(listOf(PrintPage(img))))
        val head = "^Q24,2$CRLF^W40$CRLF^P1$CRLF^L${CRLF}Q0,0,40,192$CRLF"
        assertEquals(head.length + 7680 + "${CRLF}E$CRLF".length, out.size)
        assertEquals(head, String(out, 0, head.length, Charsets.US_ASCII))
    }

    // ---------- PCL (Brother) ----------

    @Test fun packBitsMatchesAppleExample() {
        // Apple TN1023 / TIFF 6.0, раздел 9: пример PackBits.
        val raw = hex("AAAAAA80002AAAAAAAAA80002A22AAAAAAAAAAAAAAAAAAAA")
        assertArrayEquals(hex("FEAA0280002AFDAA0380002A22F7AA"), PackBits.encode(raw))
    }

    @Test fun pclPageStructure() {
        // Лист 80×3 точек: строка 0 пустая, строка 1 — точка 72 (после логического отступа 71 → бит 1), строка 2 пустая.
        val img = MonoImage.of(80, 3) { x, y -> y == 1 && x == 72 }
        val s = String(PclRaster().render(PrintJob(listOf(PrintPage(img)))), Charsets.ISO_8859_1)
        val esc = "\u001B"
        assertTrue(s.startsWith("$esc%-12345X@PJL JOB NAME=\"ChudoScan\"$CRLF@PJL ENTER LANGUAGE=PCL$CRLF${esc}E$esc&l26A$esc&l0O$esc&l0E$esc*t300R"))
        assertTrue(s.contains("$esc&l1X$esc*p0x0Y$esc*r9S$esc*r3T$esc*b2M$esc*r1A"))
        // Пустая строка 0 пропущена через Y-сдвиг, затем строка из 1 байта 0x40 (литерал: 00 40).
        assertTrue(s.contains("$esc*b1Y$esc*b2W\u0000@$esc*rC\u000C"))
        assertTrue(s.endsWith("${esc}E$esc%-12345X@PJL EOJ NAME=\"ChudoScan\"$CRLF$esc%-12345X"))
    }

    // ---------- Поиск ----------

    @Test fun mdnsTxtModel() {
        assertEquals("Brother MFC-L2700DW series", MdnsTxt.model(txt("ty" to "Brother MFC-L2700DW series", "product" to "(MFC-L2700DW)")))
        assertEquals("Brother MFC-L2700DW series", MdnsTxt.model(txt("product" to "(Brother MFC-L2700DW series)")))
        assertEquals("Brother HL-L2360D", MdnsTxt.model(txt("usb_MFG" to "Brother", "usb_MDL" to "Brother HL-L2360D")))
        assertEquals("GODEX G500", MdnsTxt.model(txt("USB_MFG" to "GODEX", "USB_MDL" to "G500")))
        assertNull(MdnsTxt.model(txt("txtvers" to "1", "ty" to "")))
    }

    private fun txt(vararg kv: Pair<String, String>): Map<String, ByteArray?> = kv.associate { it.first to it.second.toByteArray() }

    @Test fun snmpSysDescrParsing() {
        // GetResponse с sysDescr Brother: значение длиннее 127 байт — длина в длинной форме (0x81 xx).
        val descr = "Brother NC-340h, Firmware Ver.1.04  (16.02.26),MID 8C5-E19,FID 2" + " ".repeat(70)
        val msg = response(byteArrayOf(0x2b, 6, 1, 2, 1, 1, 1, 0), descr.toByteArray())
        val sys = Snmp.value(msg)
        assertEquals(descr, sys)
        // Для показа hrDeviceDescr лучше; без него — очищенный sysDescr.
        assertEquals("Brother HL-L2360D series", Snmp.pick("Brother HL-L2360D series", sys))
        assertEquals(descr.trim(), Snmp.pick(null, sys))
        assertEquals(LabelKind.PLAIN to PrintLang.PCL, guessByModel(sys!!))
    }

    @Test fun snmpIfPhysAddressIsMac() {
        // ifPhysAddress.1 приходит двоичным OCTET STRING из 6 байт — MAC из диапазона для документации (RFC 7042).
        val mac = byteArrayOf(0x00, 0x00, 0x5E, 0x00, 0x53, 0xAA.toByte())
        val msg = response(byteArrayOf(0x2b, 6, 1, 2, 1, 2, 2, 1, 6, 1), mac)
        assertEquals("00:00:5E:00:53:AA", Snmp.formatMac(Snmp.octets(msg).last()))
        assertNull(Snmp.formatMac(ByteArray(6))) // нулевой адрес — нет MAC
        assertNull(Snmp.formatMac(byteArrayOf(1, 2, 3)))
    }

    /** SNMP v1 GetResponse с одним varbind [oid] = OCTET STRING [value]. */
    private fun response(oid: ByteArray, value: ByteArray): ByteArray {
        val v = when {
            value.size < 0x80 -> byteArrayOf(0x04, value.size.toByte()) + value
            else -> byteArrayOf(0x04, 0x81.toByte(), value.size.toByte()) + value
        }
        val varbind = seq(0x30, byteArrayOf(0x06, oid.size.toByte()) + oid + v)
        val pdu = seq(0xA2, byteArrayOf(0x02, 0x02, 0x02, 0xC1.toByte(), 0x02, 0x01, 0x00, 0x02, 0x01, 0x00) + seq(0x30, varbind))
        return seq(0x30, byteArrayOf(0x02, 0x01, 0x00, 0x04, 0x06) + "public".toByteArray() + pdu)
    }

    private fun seq(tag: Int, body: ByteArray): ByteArray = when {
        body.size < 0x80 -> byteArrayOf(tag.toByte(), body.size.toByte()) + body
        body.size < 0x100 -> byteArrayOf(tag.toByte(), 0x81.toByte(), body.size.toByte()) + body
        else -> byteArrayOf(tag.toByte(), 0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
    }

    @Test fun godexWebPage() {
        assertEquals("Godex G500", HttpProbe.godexModel("HTTP/1.0 200 OK\r\n\r\n<title>GoDEX G500 Web Setting</title>"))
        assertEquals("Godex", HttpProbe.godexModel("<html>GODEX printer</html>"))
        assertNull(HttpProbe.godexModel("<html>Brother MFC-L2700DW</html>"))
        assertNull(HttpProbe.godexModel(null))
    }

    @Test fun printButtonPlural() {
        assertEquals("Друкувати 1 цінник", printButtonText(Template.TAG_68x38, 1))
        assertEquals("Друкувати 2 цінники", printButtonText(Template.TAG_68x38, 2))
        assertEquals("Друкувати 5 цінників", printButtonText(Template.BIG, 5))
        assertEquals("Друкувати 12 цінників", printButtonText(Template.BIG, 12))
        assertEquals("Друкувати 21 етикетку", printButtonText(Template.LABEL_40x24, 21))
        assertEquals("Друкувати 3 етикетки", printButtonText(Template.LABEL_40x24, 3))
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val CRLF = "\r\n"
    }
}
