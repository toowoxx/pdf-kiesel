// SPDX-License-Identifier: Apache-2.0

package de.toowoxx.pdfkiesel.dsl

import de.toowoxx.pdfkiesel.model.PdfElement
import de.toowoxx.pdfkiesel.model.PdfFontDef
import de.toowoxx.pdfkiesel.model.TreeDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * SPEC: Text without a font is set in the default font of the document, so the same document
 * looks the same each time it is rendered. The document sets that font, or else it is the font
 * registered first. A default font that the document does not register is a mistake in the
 * document and fails when the document is built. So does a document with fonts and without a
 * default font.
 */
class DefaultFontTest {
    private val fontData = byteArrayOf(0)

    @Test
    fun defaultFontIsTheFontTheDocumentSets() {
        val document = pdfDocument {
            registerFont("Inter", fontData)
            registerFont("Outfit", fontData)
            defaultFont("Outfit")
        }

        assertEquals("Outfit", document.defaultFont)
    }

    @Test
    fun defaultFontIsTheFontRegisteredFirstWhenTheDocumentSetsNone() {
        val names = listOf("RobotoMono", "Inter", "Outfit", "Alpha", "Zeta")

        val document = pdfDocument { names.forEach { registerFont(it, fontData) } }

        assertEquals("RobotoMono", document.defaultFont)
    }

    @Test
    fun defaultFontMayBeSetBeforeItsFontIsRegistered() {
        val document = pdfDocument {
            defaultFont("Outfit")
            registerFont("Inter", fontData)
            registerFont("Outfit", fontData)
        }

        assertEquals("Outfit", document.defaultFont)
    }

    @Test
    fun documentWithoutFontsHasNoDefaultFont() {
        assertNull(pdfDocument {}.defaultFont)
    }

    @Test
    fun defaultFontThatIsNotRegisteredFailsTheBuild() {
        assertFailsWith<IllegalArgumentException> {
            pdfDocument {
                registerFont("Inter", fontData)
                defaultFont("Intre")
            }
        }
    }

    @Test
    fun documentWithFontsAndWithoutADefaultFontCannotBeCreated() {
        assertFailsWith<IllegalArgumentException> {
            TreeDocument(fonts = mapOf("Inter" to PdfFontDef(data = "AA==")), defaultFont = null)
        }
    }

    @Test
    fun canvasTextWithoutAFontLeavesTheChoiceToTheDocument() {
        val scope = CanvasScope(0f, 0f, 100f, 100f).apply { text("12", x = 50f, y = 50f) }

        assertEquals("", (scope.elements.single() as PdfElement.Text).font)
    }
}
