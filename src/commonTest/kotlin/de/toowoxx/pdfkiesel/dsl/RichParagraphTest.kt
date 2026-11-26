// SPDX-License-Identifier: Apache-2.0

package de.toowoxx.pdfkiesel.dsl

import de.toowoxx.pdfkiesel.model.DocumentNode
import de.toowoxx.pdfkiesel.model.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SPEC: A paragraph with Markdown marking is set like any other paragraph: in the weight and the
 * alignment its style names, so a heading or a centred line can carry bold and italic words too.
 */
class RichParagraphTest {
    private fun richParagraph(block: TextStyle.() -> Unit) =
        pdfDocument { page { richParagraph("**Fett** und *kursiv*", block) } }
            .pages.single().content.single() as DocumentNode.Paragraph

    @Test
    fun keepsTheWeightAndAlignmentOfItsStyle() {
        val paragraph = richParagraph {
            weight = FontWeight.SemiBold
            align = TextAlign.CENTER
        }

        assertEquals(FontWeight.SemiBold, paragraph.weight)
        assertEquals(TextAlign.CENTER.name, paragraph.align.name)
        assertTrue(paragraph.markdown)
    }

    @Test
    fun isNormalAndLeftAlignedByDefault() {
        val paragraph = richParagraph {}

        assertEquals(FontWeight.Normal, paragraph.weight)
        assertEquals(TextAlign.LEFT.name, paragraph.align.name)
    }
}
