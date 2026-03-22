package de.toowoxx.pdfkiesel.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PdfColor(val r: Float, val g: Float, val b: Float) {
    companion object {
        val BLACK = PdfColor(0f, 0f, 0f)
        val WHITE = PdfColor(1f, 1f, 1f)
        val GRAY = PdfColor(0.5f, 0.5f, 0.5f)
        val LIGHT_GRAY = PdfColor(0.85f, 0.85f, 0.85f)

        fun rgb(r: Float, g: Float, b: Float) = PdfColor(r, g, b)
    }
}

/** Well-known built-in PDF font names. */
object PdfFonts {
    const val HELVETICA = "Helvetica"
    const val HELVETICA_BOLD = "Helvetica-Bold"
    const val HELVETICA_OBLIQUE = "Helvetica-Oblique"
    const val HELVETICA_BOLD_OBLIQUE = "Helvetica-BoldOblique"
    const val COURIER = "Courier"
    const val COURIER_BOLD = "Courier-Bold"

    /** Names of all built-in fonts (Type1, no embedding needed). */
    val BUILTIN =
        setOf(
            HELVETICA,
            HELVETICA_BOLD,
            HELVETICA_OBLIQUE,
            HELVETICA_BOLD_OBLIQUE,
            COURIER,
            COURIER_BOLD,
        )
}

@Serializable data class PdfPoint(val x: Float, val y: Float)

@Serializable
data class PdfFontDef(
    val data: String // base64-encoded TTF/OTF bytes
)

@Serializable
sealed interface PdfElement {

    @Serializable
    @SerialName("text")
    data class Text(
        val content: String,
        val x: Float,
        val y: Float,
        val fontSize: Float = 12f,
        val font: String = "",
        val color: PdfColor = PdfColor.BLACK,
    ) : PdfElement

    @Serializable
    @SerialName("rect")
    data class Rect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val fillColor: PdfColor? = null,
        val strokeColor: PdfColor? = null,
        val strokeWidth: Float = 1f,
        val cornerRadius: Float = 0f,
    ) : PdfElement

    @Serializable
    @SerialName("line")
    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val color: PdfColor = PdfColor.BLACK,
        val strokeWidth: Float = 1f,
        val ripple: Float = 0f,
        val thicknessRipple: Float = 0f,
    ) : PdfElement

    @Serializable
    @SerialName("image")
    data class Image(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val data: String, // base64-encoded PNG/JPEG
        val format: String = "png",
    ) : PdfElement

    @Serializable
    @SerialName("sector")
    data class Sector(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val startAngle: Float,
        val sweepAngle: Float,
        val fillColor: PdfColor? = null,
        val ripple: Float = 0f,
        val seed: Int = 0,
        val mirror: Boolean = false,
    ) : PdfElement

    @Serializable
    @SerialName("polygon")
    data class Polygon(val points: List<PdfPoint>, val fillColor: PdfColor? = null) : PdfElement

    @Serializable
    @SerialName("polyline")
    data class Polyline(
        val points: List<PdfPoint>,
        val color: PdfColor = PdfColor.BLACK,
        val strokeWidth: Float = 1f,
        val thicknessRipple: Float = 0f,
    ) : PdfElement

    @Serializable
    @SerialName("svg")
    data class Svg(
        val content: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    ) : PdfElement

    @Serializable
    @SerialName("clipStart")
    data class ClipStart(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val cornerRadius: Float = 0f,
    ) : PdfElement

    @Serializable @SerialName("clipEnd") data object ClipEnd : PdfElement
}

@Serializable
data class PdfPage(
    val width: Float = A4_WIDTH,
    val height: Float = A4_HEIGHT,
    val elements: List<PdfElement> = emptyList(),
) {
    companion object {
        const val A4_WIDTH = 595.28f
        const val A4_HEIGHT = 841.89f
    }
}

@Serializable
data class PdfDocument(
    val pages: List<PdfPage> = emptyList(),
    val fonts: Map<String, PdfFontDef> = emptyMap(),
) {
    fun toJson(): String = PDF_JSON.encodeToString(this)

    companion object {
        private val PDF_JSON = Json { encodeDefaults = true }
    }
}
