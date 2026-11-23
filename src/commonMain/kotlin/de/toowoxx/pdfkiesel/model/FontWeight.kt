// SPDX-License-Identifier: Apache-2.0

package de.toowoxx.pdfkiesel.model

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * The weight text is drawn at, on the scale of CSS: 400 is normal, 700 is bold. A variable font
 * with a weight axis draws exactly this weight.
 *
 * The range and the named weights are those of `font-weight` in CSS Fonts Level 4,
 * https://www.w3.org/TR/css-fonts-4/#font-weight-prop.
 */
@Serializable
@JvmInline
value class FontWeight(val value: Int) {
    init {
        require(value in 1..1000) { "A font weight lies between 1 and 1000, $value does not" }
    }

    companion object {
        val Normal = FontWeight(400)
        val Medium = FontWeight(500)
        val SemiBold = FontWeight(600)
        val Bold = FontWeight(700)

        /** The weight of text drawn with the bold flag, which predates [FontWeight]. */
        fun of(bold: Boolean) = if (bold) Bold else Normal
    }
}
