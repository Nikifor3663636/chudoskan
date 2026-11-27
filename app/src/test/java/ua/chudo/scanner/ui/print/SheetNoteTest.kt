package ua.chudo.scanner.ui.print

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetNoteTest {
    @Test fun describesSheetLikeDesign() {
        assertEquals("20 на аркуші A4, альбомна · 1 аркуш", sheetNote(20, true, 1))
        assertEquals("21 на аркуші A4, книжна · 3 аркуші", sheetNote(21, false, 3))
        assertEquals("4 на аркуші A4, альбомна · 12 аркушів", sheetNote(4, true, 12))
    }
}
