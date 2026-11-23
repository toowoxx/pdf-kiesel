// SPDX-License-Identifier: Apache-2.0

package de.toowoxx.pdfkiesel.dsl

import de.toowoxx.pdfkiesel.model.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SPEC: Text is drawn at a font weight. Callers written before weights existed set a bold flag, and
 * that flag still draws their text bold or normal as before.
 */
// The bold flag is deprecated in favour of the weight, and this test exists to check that the old
// flag still works, so it has to call the deprecated API.
@Suppress("DEPRECATION")
class BoldFlagTest {

    @Test
    fun boldTextIsTextAtTheBoldWeight() {
        assertEquals(FontWeight.Bold, TextStyle().apply { bold = true }.weight)
        assertEquals(FontWeight.Bold, CellStyle().apply { bold = true }.weight)
    }

    @Test
    fun textAtTheBoldWeightReadsAsBold() {
        assertEquals(true, TextStyle().apply { weight = FontWeight.Bold }.bold)
    }

    @Test
    fun textAtAnotherWeightDoesNotReadAsBold() {
        assertEquals(false, TextStyle().apply { weight = FontWeight.SemiBold }.bold)
    }

    @Test
    fun turningBoldOffGoesBackToTheNormalWeight() {
        assertEquals(FontWeight.Normal, TextStyle().apply { weight = FontWeight.SemiBold; bold = false }.weight)
        assertEquals(FontWeight.Normal, CellStyle().apply { weight = FontWeight.SemiBold; bold = false }.weight)
    }

    @Test
    fun tableCellIsDrawnAtTheWeightOfItsStyle() {
        val row = TableRowBuilder().apply { cell("Fallzahlen") { weight = FontWeight.Medium } }

        assertEquals(FontWeight.Medium, (row.cells.single() as TextTableCell).weight)
    }
}
