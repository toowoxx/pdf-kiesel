package de.toowoxx.pdfkiesel.dsl

import de.toowoxx.pdfkiesel.model.PdfFonts

/**
 * Approximate text measurement using standard Helvetica character widths. Widths are sourced from
 * the Helvetica AFM (Adobe Font Metrics) file, expressed in units of 1/1000 em.
 */
object TextMeasure {

    // Helvetica regular widths for ASCII 32..126 (95 entries)
    private val HELVETICA_WIDTHS =
        intArrayOf(
            278,
            278,
            355,
            556,
            556,
            889,
            667,
            222,
            333,
            333, // 32-41  space ! " # $ % & ' ( )
            389,
            584,
            278,
            333,
            278,
            278,
            556,
            556,
            556,
            556, // 42-51  * + , - . / 0 1 2 3
            556,
            556,
            556,
            556,
            556,
            556,
            278,
            278,
            584,
            584, // 52-61  4 5 6 7 8 9 : ; < =
            584,
            556,
            1015,
            667,
            667,
            722,
            722,
            667,
            611,
            778, // 62-71 > ? @ A B C D E F G
            722,
            278,
            500,
            667,
            556,
            833,
            722,
            778,
            667,
            778, // 72-81  H I J K L M N O P Q
            722,
            667,
            611,
            722,
            667,
            944,
            667,
            667,
            611,
            278, // 82-91  R S T U V W X Y Z [
            278,
            278,
            469,
            556,
            222,
            556,
            556,
            500,
            556,
            556, // 92-101 \ ] ^ _ ` a b c d e
            278,
            556,
            556,
            222,
            222,
            500,
            222,
            833,
            556,
            556, // 102-111 f g h i j k l m n o
            556,
            556,
            333,
            500,
            278,
            556,
            500,
            722,
            500,
            500, // 112-121 p q r s t u v w x y
            500,
            334,
            260,
            334,
            584, // 122-126 z { | } ~
        )

    private const val DEFAULT_WIDTH = 556

    fun charWidth(char: Char, font: String, fontSize: Float): Float {
        val code = char.code
        val unitWidth =
            if (code in 32..126) {
                HELVETICA_WIDTHS[code - 32]
            } else {
                DEFAULT_WIDTH
            }
        val boldFactor =
            when (font) {
                PdfFonts.HELVETICA_BOLD,
                PdfFonts.HELVETICA_BOLD_OBLIQUE -> 1.05f
                else -> 1.0f
            }
        return unitWidth * fontSize * boldFactor / 1000f
    }

    fun measureWidth(text: String, font: String, fontSize: Float): Float {
        return text.fold(0f) { acc, ch -> acc + charWidth(ch, font, fontSize) }
    }

    fun wrapText(text: String, font: String, fontSize: Float, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")

        val words = text.split(' ')
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var currentWidth = 0f
        val spaceWidth = charWidth(' ', font, fontSize)

        for (word in words) {
            val wordWidth = measureWidth(word, font, fontSize)
            when {
                current.isEmpty() -> {
                    current.append(word)
                    currentWidth = wordWidth
                }
                currentWidth + spaceWidth + wordWidth <= maxWidth -> {
                    current.append(' ').append(word)
                    currentWidth += spaceWidth + wordWidth
                }
                else -> {
                    lines.add(current.toString())
                    current.clear().append(word)
                    currentWidth = wordWidth
                }
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }
}
