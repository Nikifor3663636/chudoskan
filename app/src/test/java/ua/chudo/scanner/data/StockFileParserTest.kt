package ua.chudo.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Імпорт xlsx із 1С: назви частин zip у різному регістрі, лист читається потоково. */
class StockFileParserTest {

    @Before fun jvmParser() {
        StockFileParser.newParser = { KXmlParser() }
    }

    private fun xlsx(vararg parts: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            parts.forEach { (name, xml) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val strings = """<?xml version="1.0" encoding="UTF-8"?>
        <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="5" uniqueCount="5">
          <si><t>Артикул</t></si><si><t>Штрихкод</t></si><si><t>Номенклатура</t></si><si><t>Кількість</t></si>
          <si><t>LEGO Duplo Потяг</t></si>
        </sst>"""

    private val sheet = """<?xml version="1.0" encoding="UTF-8"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
          <row r="1"><c r="A1" t="inlineStr"><is><t>Залишки товарів</t></is></c></row>
          <row r="3"><c r="A3" t="s"><v>0</v></c><c r="B3" t="s"><v>1</v></c><c r="C3" t="s"><v>2</v></c><c r="D3" t="s"><v>3</v></c></row>
          <row r="4"><c r="A4"><v>10954</v></c><c r="B4"><v>5702017417912</v></c><c r="C4" t="s"><v>4</v></c><c r="D4"><v>3</v></c></row>
        </sheetData></worksheet>"""

    private fun assertLego(p: ParsedStock) {
        assertEquals(StockColumns("Артикул", "Штрихкод", "Номенклатура", "Кількість"), p.columns)
        assertEquals(listOf(StockRow("10954", "5702017417912", "LEGO Duplo Потяг", 3.0)), p.rows)
    }

    @Test fun oneCSharedStringsWithCapitalS() {
        // Як пише 1С: SharedStrings.xml з великої S, і лист у zip раніше за рядки.
        assertLego(StockFileParser.parse(xlsx("xl/worksheets/sheet1.xml" to sheet, "xl/SharedStrings.xml" to strings)))
    }

    @Test fun excelLowercaseNames() {
        assertLego(StockFileParser.parse(xlsx("xl/sharedStrings.xml" to strings, "xl/worksheets/sheet1.xml" to sheet)))
    }

    @Test fun firstSheetByNumberAnyCase() {
        val other = sheet.replace("10954", "99999")
        assertLego(StockFileParser.parse(xlsx(
            "xl/worksheets/Sheet2.xml" to other, "xl/worksheets/Sheet1.xml" to sheet, "XL/SHAREDSTRINGS.XML" to strings,
        )))
    }
}
