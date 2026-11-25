// SPDX-License-Identifier: Apache-2.0

package de.toowoxx.pdfkiesel.dsl

import de.toowoxx.pdfkiesel.model.DocumentNode
import de.toowoxx.pdfkiesel.model.PdfElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC: A document that runs over several pages can carry a footer on every page, for example to
 * say which document it is and which page the reader holds ("Seite 2 von 5"). The footer is
 * declared once for the whole document and numbers the pages across all of it. Lines can be drawn
 * dashed, so that series in a chart can be told apart in a black-and-white print.
 */
class FooterTest {
    @Test
    fun documentWithoutFooterHasNone() {
        val document = pdfDocument { page { text("Inhalt") } }

        assertNull(document.footer)
        assertTrue(document.toJson().contains("\"footer\":null"))
    }

    @Test
    fun footerKeepsItsOffsetContentAndPlaceholders() {
        val document = pdfDocument {
            pagedContent { text("Inhalt") }
            footer(offset = 12f) {
                text("Seite ${DocumentBuilder.PAGE_PLACEHOLDER} von ${DocumentBuilder.PAGES_PLACEHOLDER}")
            }
        }

        val footer = document.footer!!
        assertEquals(12f, footer.offset)
        assertEquals("Seite {page} von {pages}", (footer.content.single() as DocumentNode.Text).content)
    }

    @Test
    fun footerBuilderReportsTheGivenWidth() {
        var reported = 0f
        pdfDocument { footer(width = 700f) { reported = contentWidth } }

        assertEquals(700f, reported)
    }

    @Test
    fun dashedLinesKeepTheirPattern() {
        val document = pdfDocument {
            page {
                canvas(height = 10f) {
                    line(0f, 0f, 10f, 0f) { dash = listOf(9f, 5f) }
                    polyline(listOf(0f to 0f, 5f to 5f)) { dash = listOf(2f, 1f) }
                    line(0f, 5f, 10f, 5f)
                }
            }
        }

        val elements = (document.pages.single().content.single() as DocumentNode.Canvas).elements
        assertEquals(listOf(9f, 5f), (elements[0] as PdfElement.Line).dash)
        assertEquals(listOf(2f, 1f), (elements[1] as PdfElement.Polyline).dash)
        assertNull((elements[2] as PdfElement.Line).dash)
    }
}
