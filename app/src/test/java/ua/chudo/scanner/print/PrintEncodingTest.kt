package ua.chudo.scanner.print

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Байты команд сверены с описаниями ZPL II, TSPL, EPL2, ESC/POS и SNMP v1 (RFC 1157). */
class PrintEncodingTest {
    /** 8×1 точек: левые 4 чёрные. */
    private val img = MonoImage(8, 1, byteArrayOf(0xF0.toByte()))
    private val t = LabelTemplate.STICKY_58x40

    private fun bytes(vararg parts: Any): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach {
            when (it) {
                is String -> out.write(it.toByteArray(Charsets.US_ASCII))
                is Int -> out.write(it)
                is ByteArray -> out.write(it)
            }
        }
        return out.toByteArray()
    }

    @Test fun zpl() {
        assertEquals(
            "^XA^PW8^LL1^LH0,0^FO0,0^GFA,1,1,1,F0^FS^PQ2^XZ\n",
            String(LabelEncoder.encode(PrintLang.ZPL, listOf(img to 2), t), Charsets.US_ASCII),
        )
    }

    @Test fun tsplInvertsBits() {
        assertArrayEquals(
            bytes("SIZE 58 mm,40 mm\r\nGAP 2 mm,0 mm\r\nCLS\r\nBITMAP 0,0,1,1,0,", 0x0F, "\r\nPRINT 1,3\r\n"),
            LabelEncoder.encode(PrintLang.TSPL, listOf(img to 3), t),
        )
    }

    @Test fun eplInvertsBits() {
        assertArrayEquals(
            bytes("\nN\nq8\nQ1,24\nGW0,0,1,1,", 0x0F, "\nP1\n"),
            LabelEncoder.encode(PrintLang.EPL, listOf(img to 1), t),
        )
    }

    @Test fun escPosRaster() {
        assertArrayEquals(
            bytes(0x1B, 0x40, 0x1D, 0x76, 0x30, 0, 1, 0, 1, 0, 0xF0, 0x1B, 0x64, 4),
            LabelEncoder.encode(PrintLang.ESCPOS, listOf(img to 1), t),
        )
    }

    @Test fun escPosSplitsTallImagesIntoBands() {
        val tall = MonoImage(8, 300, ByteArray(300))
        val out = LabelEncoder.encode(PrintLang.ESCPOS, listOf(tall to 1), t)
        // ESC @ + (8 + 256) + (8 + 44) + ESC d 4
        assertEquals(2 + 264 + 52 + 3, out.size)
        assertEquals(0, out[2 + 264 + 7].toInt()) // yH второй полосы
        assertEquals(44, out[2 + 264 + 6].toInt()) // yL второй полосы
    }

    @Test fun snmpGetRequestMatchesNetSnmp() {
        val expected = intArrayOf(
            0x30, 0x27, 0x02, 0x01, 0x00, 0x04, 0x06, 0x70, 0x75, 0x62, 0x6c, 0x69, 0x63,
            0xa0, 0x1a, 0x02, 0x02, 0x02, 0xc1, 0x02, 0x01, 0x00, 0x02, 0x01, 0x00,
            0x30, 0x0e, 0x30, 0x0c, 0x06, 0x08, 0x2b, 0x06, 0x01, 0x02, 0x01, 0x01, 0x01, 0x00, 0x05, 0x00,
        ).map { it.toByte() }.toByteArray()
        assertArrayEquals(expected, Snmp.request(Snmp.SYS_DESCR))
    }

    @Test fun snmpResponseParsing() {
        val descr = "ZTC ZD421-203dpi ZPL"
        val varbind = bytes(0x30, 10 + 2 + descr.length, 0x06, 0x08, byteArrayOf(0x2b, 6, 1, 2, 1, 1, 1, 0), 0x04, descr.length, descr)
        val list = bytes(0x30, varbind.size, varbind)
        val pdu = bytes(0xa2, 10 + list.size, 0x02, 0x02, 0x02, 0xc1, 0x02, 0x01, 0x00, 0x02, 0x01, 0x00, list)
        val msg = bytes(0x30, 3 + 8 + pdu.size, 0x02, 0x01, 0x00, 0x04, 0x06, "public", pdu)
        assertEquals(listOf("public", descr), Snmp.octetStrings(msg))
    }

    @Test fun guessLanguageByModel() {
        assertEquals(LabelKind.STICKY to PrintLang.ZPL, guessByModel("Zebra ZD421"))
        assertEquals(LabelKind.STICKY to PrintLang.ZPL, guessByModel("GoDEX G500"))
        assertEquals(LabelKind.STICKY to PrintLang.TSPL, guessByModel("Xprinter XP-365B"))
        assertEquals(LabelKind.STICKY to PrintLang.TSPL, guessByModel("TSC TE200"))
        assertEquals(LabelKind.PLAIN to PrintLang.PDF, guessByModel("Brother HL-L2350DW series"))
        assertEquals(LabelKind.PLAIN to PrintLang.PDF, guessByModel("HP LaserJet MFP M28w"))
        assertEquals(null, guessByModel("Unknown device"))
    }
}
