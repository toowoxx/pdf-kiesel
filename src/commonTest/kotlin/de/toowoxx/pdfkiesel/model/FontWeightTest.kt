// SPDX-License-Identifier: Apache-2.0

package de.toowoxx.pdfkiesel.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json

/**
 * SPEC: A document sets each text at a font weight on the scale of CSS, so a PDF can draw the
 * semibold or medium text of the screen it reproduces. The renderer reads the weight as a bare
 * number, and a weight outside the range of CSS cannot be created.
 */
class FontWeightTest {

    @Test
    fun weightIsWrittenAsABareNumber() {
        assertEquals("600", Json.encodeToString(FontWeight.SemiBold))
    }

    @Test
    fun weightOfATextNodeSurvivesTheJsonOfTheDocument() {
        val document = TreeDocument(
            pages = listOf(TreePage(content = listOf(DocumentNode.Text("Fallzahlen", weight = FontWeight.Medium)))),
        )

        val read = Json.decodeFromString<TreeDocument>(document.toJson())

        assertEquals(document, read)
    }

    @Test
    fun endsOfTheCssRangeAreWeights() {
        assertEquals(listOf(1, 1000), listOf(FontWeight(1), FontWeight(1000)).map { it.value })
    }

    @Test
    fun weightOutsideTheCssRangeCannotBeCreated() {
        listOf(0, 1001, -400).forEach { value ->
            assertFailsWith<IllegalArgumentException>("the weight $value was created") { FontWeight(value) }
        }
    }
}
