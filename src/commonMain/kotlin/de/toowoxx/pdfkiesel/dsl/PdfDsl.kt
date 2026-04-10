package de.toowoxx.pdfkiesel.dsl

import de.toowoxx.pdfkiesel.model.PdfColor
import de.toowoxx.pdfkiesel.model.PdfFontDef
import de.toowoxx.pdfkiesel.model.PdfElement
import de.toowoxx.pdfkiesel.model.PdfPoint
import de.toowoxx.pdfkiesel.model.PageSize
import de.toowoxx.pdfkiesel.model.TreeDocument
import de.toowoxx.pdfkiesel.model.TreeMargin
import de.toowoxx.pdfkiesel.model.TreePage
import de.toowoxx.pdfkiesel.model.TreeSplitStrategy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@DslMarker annotation class PdfDslMarker

inline fun pdfDocument(block: DocumentBuilder.() -> Unit): TreeDocument {
    val builder = DocumentBuilder()
    builder.block()
    return builder.build()
}

@PdfDslMarker
class DocumentBuilder {
    @PublishedApi internal val pages = mutableListOf<TreePage>()
    private val fonts = mutableMapOf<String, PdfFontDef>()
    @PublishedApi internal var defaultFont: String = ""

    fun defaultFont(name: String) {
        defaultFont = name
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun registerFont(name: String, data: ByteArray) {
        fonts[name] = PdfFontDef(data = Base64.encode(data))
    }

    inline fun page(
        width: Float = PageSize.A4_WIDTH,
        height: Float = PageSize.A4_HEIGHT,
        margin: Margin = Margin(),
        background: PdfColor? = null,
        block: PageBuilder.() -> Unit,
    ) {
        val contentWidth = width - margin.left - margin.right
        val scope = PageBuilder(contentWidth, defaultFont = defaultFont)
        scope.block()
        pages.add(
            TreePage(
                width = width,
                height = height,
                margin = TreeMargin(margin.top, margin.bottom, margin.left, margin.right),
                background = background,
                splitStrategy = TreeSplitStrategy.NONE,
                content = scope.buildContent(),
            )
        )
    }

    inline fun pagedContent(
        width: Float = PageSize.A4_WIDTH,
        height: Float = PageSize.A4_HEIGHT,
        margin: Margin = Margin(),
        background: PdfColor? = null,
        splitStrategy: PageSplitStrategy = PageSplitStrategy.SPLIT_NEAREST_VIEW,
        block: PageBuilder.() -> Unit,
    ) {
        val contentWidth = width - margin.left - margin.right
        val scope = PageBuilder(contentWidth, defaultFont = defaultFont)
        scope.block()
        pages.add(
            TreePage(
                width = width,
                height = height,
                margin = TreeMargin(margin.top, margin.bottom, margin.left, margin.right),
                background = background,
                splitStrategy = splitStrategy.toTreeSplitStrategy(),
                content = scope.buildContent(),
            )
        )
    }

    @PublishedApi
    internal fun build(): TreeDocument = TreeDocument(fonts.toMap(), pages.toList())
}

data class Margin(
    val top: Float = 50f,
    val bottom: Float = 50f,
    val left: Float = 50f,
    val right: Float = 50f,
)

data class Padding(
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val left: Float = 0f,
) {
    constructor(all: Float) : this(all, all, all, all)

    constructor(
        vertical: Float,
        horizontal: Float,
    ) : this(vertical, horizontal, vertical, horizontal)
}

sealed interface GridColumnDef {
    data class Fixed(val width: Float) : GridColumnDef

    data class Weight(val weight: Float = 1f) : GridColumnDef
}

enum class TextAlign {
    LEFT,
    CENTER,
    RIGHT,
}

enum class HorizontalAlignment {
    Start,
    CenterHorizontally,
    End,
}

enum class VerticalAlignment {
    Top,
    CenterVertically,
    Bottom,
}

enum class PageSplitStrategy {
    NONE,
    SPLIT_NEAREST_VIEW,
    SPLIT_CENTER,
    SPLIT_ANYWHERE,
}

class TextStyle {
    var fontSize: Float = 12f
    var font: String = ""
    var color: PdfColor = PdfColor.BLACK
    var align: TextAlign = TextAlign.LEFT
    var lineSpacing: Float = 1.4f
    var bold: Boolean = false
    var italic: Boolean = false
}

class RectStyle {
    var fillColor: PdfColor? = null
    var strokeColor: PdfColor? = null
    var strokeWidth: Float = 1f
    var cornerRadius: Float = 0f
}

class LineStyle {
    var color: PdfColor = PdfColor.BLACK
    var strokeWidth: Float = 1f
    var ripple: Float = 0f
    var thicknessRipple: Float = 0f
}

@PdfDslMarker
class CanvasScope
internal constructor(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    internal val yDown: Boolean = false,
    private val defaultFont: String = "",
) {
    val cx: Float
        get() = x + width / 2f

    val cy: Float
        get() = if (yDown) y + height / 2f else y - height / 2f

    val top: Float get() = y
    val bottom: Float get() = if (yDown) y + height else y - height
    val left: Float get() = x
    val right: Float get() = x + width

    internal val elements = mutableListOf<PdfElement>()

    fun sector(
        cx: Float,
        cy: Float,
        radius: Float,
        startAngle: Float,
        sweepAngle: Float,
        fill: PdfColor,
        ripple: Float = 0f,
        seed: Int = 0,
        mirror: Boolean = false,
    ) {
        elements.add(
            PdfElement.Sector(cx, cy, radius, startAngle, sweepAngle, fill, ripple, seed, mirror)
        )
    }

    fun circle(cx: Float, cy: Float, radius: Float, fill: PdfColor) {
        elements.add(PdfElement.Sector(cx, cy, radius, 0f, 360f, fill))
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, block: LineStyle.() -> Unit = {}) {
        val s = LineStyle().apply(block)
        elements.add(
            PdfElement.Line(x1, y1, x2, y2, s.color, s.strokeWidth, s.ripple, s.thicknessRipple)
        )
    }

    fun rect(x: Float, y: Float, width: Float, height: Float, block: RectStyle.() -> Unit = {}) {
        val s = RectStyle().apply(block)
        val ry = if (yDown) y else y - height
        elements.add(
            PdfElement.Rect(
                x,
                ry,
                width,
                height,
                s.fillColor,
                s.strokeColor,
                s.strokeWidth,
                s.cornerRadius,
            )
        )
    }

    fun text(content: String, x: Float, y: Float, block: TextStyle.() -> Unit = {}) {
        val s = TextStyle().apply { align = TextAlign.CENTER }.apply(block)
        val resolvedFont = s.font.ifEmpty { defaultFont }
        val w = TextMeasure.measureWidth(content, resolvedFont, s.fontSize)
        val tx =
            when (s.align) {
                TextAlign.LEFT -> x
                TextAlign.CENTER -> x - w / 2f
                TextAlign.RIGHT -> x - w
            }
        elements.add(
            PdfElement.Text(content, tx, y + s.fontSize * 0.3f, s.fontSize, resolvedFont, s.color)
        )
    }

    fun polygon(points: List<Pair<Float, Float>>, fill: PdfColor) {
        elements.add(PdfElement.Polygon(points.map { (px, py) -> PdfPoint(px, py) }, fill))
    }

    fun polyline(points: List<Pair<Float, Float>>, block: LineStyle.() -> Unit = {}) {
        val s = LineStyle().apply(block)
        elements.add(
            PdfElement.Polyline(
                points.map { (px, py) -> PdfPoint(px, py) },
                s.color,
                s.strokeWidth,
                s.thicknessRipple,
            )
        )
    }

    fun chevron(
        tipX: Float,
        tipY: Float,
        angleDeg: Float,
        size: Float = 8f,
        spread: Float = 35f,
        cornerRadius: Float = 0f,
        block: LineStyle.() -> Unit = {},
    ) {
        val (lx, ly) = polar(tipX, tipY, size, angleDeg + 180f + spread)
        val (rx, ry) = polar(tipX, tipY, size, angleDeg + 180f - spread)
        if (cornerRadius <= 0f) {
            polyline(listOf(lx to ly, tipX to tipY, rx to ry), block)
            return
        }
        // Replace the sharp tip with a circular fillet arc
        val e1x = lx - tipX
        val e1y = ly - tipY
        val e1len = kotlin.math.sqrt(e1x * e1x + e1y * e1y)
        val e2x = rx - tipX
        val e2y = ry - tipY
        val e2len = kotlin.math.sqrt(e2x * e2x + e2y * e2y)
        val dot = (e1x * e2x + e1y * e2y) / (e1len * e2len)
        val halfAngle = kotlin.math.acos(dot.coerceIn(-1f, 1f)) / 2f
        val tanHalf = kotlin.math.tan(halfAngle)
        if (tanHalf < 1e-4f) {
            polyline(listOf(lx to ly, tipX to tipY, rx to ry), block)
            return
        }
        val td = (cornerRadius / tanHalf).coerceAtMost(minOf(e1len, e2len) * 0.45f)
        val r = td * tanHalf
        // Tangent points on each arm
        val t1x = tipX + td * e1x / e1len
        val t1y = tipY + td * e1y / e1len
        val t2x = tipX + td * e2x / e2len
        val t2y = tipY + td * e2y / e2len
        // Fillet center along bisector
        val bx = e1x / e1len + e2x / e2len
        val by = e1y / e1len + e2y / e2len
        val blen = kotlin.math.sqrt(bx * bx + by * by).coerceAtLeast(1e-6f)
        val cDist = r / kotlin.math.sin(halfAngle)
        val fcx = tipX + cDist * bx / blen
        val fcy = tipY + cDist * by / blen
        // Arc from t1 to t2
        val a0 = kotlin.math.atan2(t1y - fcy, t1x - fcx)
        val a1 = kotlin.math.atan2(t2y - fcy, t2x - fcx)
        var sweep = a1 - a0
        if (sweep > kotlin.math.PI.toFloat()) sweep -= 2f * kotlin.math.PI.toFloat()
        if (sweep < -kotlin.math.PI.toFloat()) sweep += 2f * kotlin.math.PI.toFloat()
        val arcSteps = 8
        val pts = mutableListOf(lx to ly, t1x to t1y)
        for (i in 1 until arcSteps) {
            val a = a0 + sweep * i.toFloat() / arcSteps
            pts.add((fcx + r * kotlin.math.cos(a)) to (fcy + r * kotlin.math.sin(a)))
        }
        pts.add(t2x to t2y)
        pts.add(rx to ry)
        polyline(pts, block)
    }

    fun svg(content: String, x: Float, y: Float, width: Float, height: Float) {
        elements.add(PdfElement.Svg(content, x, y, width, height))
    }

    fun arrowhead(
        tipX: Float,
        tipY: Float,
        angleDeg: Float,
        size: Float = 4f,
        block: LineStyle.() -> Unit = {},
    ) {
        val s = LineStyle().apply(block)
        val spread = 35f
        val (lx, ly) = polar(tipX, tipY, size, angleDeg + 180f + spread)
        val (rx, ry) = polar(tipX, tipY, size, angleDeg + 180f - spread)
        elements.add(
            PdfElement.Line(lx, ly, tipX, tipY, s.color, s.strokeWidth, s.ripple, s.thicknessRipple)
        )
        elements.add(
            PdfElement.Line(rx, ry, tipX, tipY, s.color, s.strokeWidth, s.ripple, s.thicknessRipple)
        )
    }

    /**
     * A boomerang-shaped filled arrowhead with organic variable-width arms. Each arm tapers from
     * [baseWidth] at the base to a point at the tip, with independent triple-harmonic ripple on
     * each edge for a hand-painted feel.
     */
    fun brushArrowhead(
        tipX: Float,
        tipY: Float,
        angleDeg: Float,
        armLength: Float = 12f,
        spread: Float = 45f,
        baseWidth: Float = 5f,
        rippleAmp: Float = 0.6f,
        fill: PdfColor = PdfColor.BLACK,
    ) {
        val (lx, ly) = polar(tipX, tipY, armLength, angleDeg + 180f + spread)
        val (rx, ry) = polar(tipX, tipY, armLength, angleDeg + 180f - spread)

        val steps = 20

        // Left arm: base (lx,ly) → tip
        val ldx = tipX - lx
        val ldy = tipY - ly
        val lLen = kotlin.math.sqrt((ldx * ldx + ldy * ldy).toDouble()).toFloat()
        // Outer = left of direction (CCW 90°)
        val lonx = -ldy / lLen
        val lony = ldx / lLen

        // Right arm: base (rx,ry) → tip
        val rdx = tipX - rx
        val rdy = tipY - ry
        val rLen = kotlin.math.sqrt((rdx * rdx + rdy * rdy).toDouble()).toFloat()
        // Outer = right of direction (CW 90°)
        val ronx = rdy / rLen
        val rony = -rdx / rLen

        val outline = mutableListOf<Pair<Float, Float>>()
        val halfBase = baseWidth / 2f

        // 1) Left arm outer edge: base → tip (tapers last 50%)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val taper = if (t > 0.5f) (1f - t) / 0.5f else 1f
            val x = lx + t * ldx
            val y = ly + t * ldy
            val d = (t * lLen).toDouble()
            val wave =
                (0.40 * kotlin.math.sin(0.5 * d + 0.7) +
                        0.35 * kotlin.math.sin(1.1 * d + 1.4) +
                        0.25 * kotlin.math.sin(1.8 * d + 4.8))
                    .toFloat()
            val hw = (halfBase + rippleAmp * wave) * taper
            outline.add((x + hw * lonx) to (y + hw * lony))
        }

        // 2) Sharp tip
        outline.add(tipX to tipY)

        // 3) Right arm outer edge: tip → base
        for (i in steps downTo 0) {
            val t = i.toFloat() / steps
            val taper = if (t > 0.5f) (1f - t) / 0.5f else 1f
            val x = rx + t * rdx
            val y = ry + t * rdy
            val d = (t * rLen).toDouble()
            val wave =
                (0.45 * kotlin.math.sin(0.6 * d + 3.2) +
                        0.30 * kotlin.math.sin(1.2 * d + 0.6) +
                        0.25 * kotlin.math.sin(1.9 * d + 5.5))
                    .toFloat()
            val hw = (halfBase + rippleAmp * wave) * taper
            outline.add((x + hw * ronx) to (y + hw * rony))
        }

        // 4) Right arm inner edge: base → tip
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val taper = if (t > 0.5f) (1f - t) / 0.5f else 1f
            val x = rx + t * rdx
            val y = ry + t * rdy
            val d = (t * rLen).toDouble()
            val wave =
                (0.40 * kotlin.math.sin(0.7 * d + 5.1) +
                        0.35 * kotlin.math.sin(1.3 * d + 2.8) +
                        0.25 * kotlin.math.sin(2.0 * d + 0.3))
                    .toFloat()
            val hw = (halfBase + rippleAmp * wave) * taper
            outline.add((x - hw * ronx) to (y - hw * rony))
        }

        // 5) Left arm inner edge: tip → base
        for (i in steps downTo 0) {
            val t = i.toFloat() / steps
            val taper = if (t > 0.5f) (1f - t) / 0.5f else 1f
            val x = lx + t * ldx
            val y = ly + t * ldy
            val d = (t * lLen).toDouble()
            val wave =
                (0.45 * kotlin.math.sin(0.55 * d + 4.0) +
                        0.30 * kotlin.math.sin(1.15 * d + 1.2) +
                        0.25 * kotlin.math.sin(1.85 * d + 6.7))
                    .toFloat()
            val hw = (halfBase + rippleAmp * wave) * taper
            outline.add((x - hw * lonx) to (y - hw * lony))
        }

        polygon(outline, fill)
    }

    fun polar(
        originX: Float,
        originY: Float,
        distance: Float,
        angleDeg: Float,
    ): Pair<Float, Float> {
        val rad = angleDeg * kotlin.math.PI.toFloat() / 180f
        val dx = distance * kotlin.math.cos(rad.toDouble()).toFloat()
        val dy = distance * kotlin.math.sin(rad.toDouble()).toFloat()
        return if (yDown) {
            Pair(originX + dx, originY - dy)
        } else {
            Pair(originX + dx, originY + dy)
        }
    }
}
