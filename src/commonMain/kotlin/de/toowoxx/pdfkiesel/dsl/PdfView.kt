package de.toowoxx.pdfkiesel.dsl

import de.toowoxx.pdfkiesel.model.DocumentNode
import de.toowoxx.pdfkiesel.model.PdfColor
import de.toowoxx.pdfkiesel.model.PdfElement
import de.toowoxx.pdfkiesel.model.PdfPoint
import de.toowoxx.pdfkiesel.model.TreeGridCell
import de.toowoxx.pdfkiesel.model.TreeGridColumnDef
import de.toowoxx.pdfkiesel.model.TreeGridRow
import de.toowoxx.pdfkiesel.model.TreeHorizontalAlignment
import de.toowoxx.pdfkiesel.model.TreePadding
import de.toowoxx.pdfkiesel.model.TreeRowCell
import de.toowoxx.pdfkiesel.model.TreeSplitStrategy
import de.toowoxx.pdfkiesel.model.TreeTextAlign
import de.toowoxx.pdfkiesel.model.TreeVerticalAlignment

data class ViewLayout(val elements: List<PdfElement>, val height: Float, val width: Float = 0f)

internal fun PdfElement.offset(dx: Float = 0f, dy: Float = 0f): PdfElement =
    when (this) {
        is PdfElement.Text -> copy(x = x + dx, y = y + dy)
        is PdfElement.Rect -> copy(x = x + dx, y = y + dy)
        is PdfElement.Line -> copy(x1 = x1 + dx, y1 = y1 + dy, x2 = x2 + dx, y2 = y2 + dy)
        is PdfElement.Image -> copy(x = x + dx, y = y + dy)
        is PdfElement.Sector -> copy(cx = cx + dx, cy = cy + dy)
        is PdfElement.Polygon -> copy(points = points.map { PdfPoint(it.x + dx, it.y + dy) })
        is PdfElement.Polyline -> copy(points = points.map { PdfPoint(it.x + dx, it.y + dy) })
        is PdfElement.Svg -> copy(x = x + dx, y = y + dy)
        is PdfElement.ClipStart -> copy(x = x + dx, y = y + dy)
        is PdfElement.ClipEnd -> this
    }

internal fun List<PdfElement>.offset(dx: Float = 0f, dy: Float = 0f): List<PdfElement> =
    if (dx == 0f && dy == 0f) this else map { it.offset(dx, dy) }

internal sealed interface PdfView {
    fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout
    fun toNode(): DocumentNode

    val pageSplitStrategy: PageSplitStrategy
        get() = PageSplitStrategy.NONE
}

/**
 * Approximate ascender ratio for standard fonts. Text nodes use this to translate from top-of-glyph
 * coordinates (y = top of "A") to PDF baseline coordinates internally, so callers can treat y as
 * the visual top.
 */
private const val ASCENDER_RATIO = 0.8f

internal data class TextNode(
    val content: String,
    val fontSize: Float,
    val font: String,
    val color: PdfColor,
    val align: TextAlign,
    val lineSpacing: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout {
        val w = TextMeasure.measureWidth(content, font, fontSize)
        val textX =
            when (align) {
                TextAlign.LEFT -> x
                TextAlign.CENTER -> x + (maxWidth - w) / 2f
                TextAlign.RIGHT -> x + maxWidth - w
            }
        return ViewLayout(
            listOf(
                PdfElement.Text(
                    content,
                    textX,
                    y - fontSize * ASCENDER_RATIO,
                    fontSize,
                    font,
                    color,
                )
            ),
            fontSize * lineSpacing,
            w,
        )
    }

    override fun toNode(): DocumentNode = DocumentNode.Text(
        content = content,
        fontSize = fontSize,
        font = font,
        color = color,
        align = align.toTreeTextAlign(),
        bold = bold,
        italic = italic,
        lineSpacing = lineSpacing,
    )
}

internal data class ParagraphNode(
    val content: String,
    val fontSize: Float,
    val font: String,
    val color: PdfColor,
    val align: TextAlign,
    val lineSpacing: Float,
    val bold: Boolean = false,
    val markdown: Boolean = false,
) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout {
        val lines = TextMeasure.wrapText(content, font, fontSize, maxWidth)
        val lineHeight = fontSize * lineSpacing
        val elements = mutableListOf<PdfElement>()
        var currentY = y - fontSize * ASCENDER_RATIO
        for (line in lines) {
            val lineX =
                when (align) {
                    TextAlign.LEFT -> x
                    TextAlign.CENTER -> {
                        val w = TextMeasure.measureWidth(line, font, fontSize)
                        x + (maxWidth - w) / 2f
                    }
                    TextAlign.RIGHT -> {
                        val w = TextMeasure.measureWidth(line, font, fontSize)
                        x + maxWidth - w
                    }
                }
            elements.add(PdfElement.Text(line, lineX, currentY, fontSize, font, color))
            currentY -= lineHeight
        }
        return ViewLayout(elements, lines.size * lineHeight)
    }

    override fun toNode(): DocumentNode = DocumentNode.Paragraph(
        content = content,
        fontSize = fontSize,
        font = font,
        color = color,
        align = align.toTreeTextAlign(),
        lineSpacing = lineSpacing,
        bold = bold,
        markdown = markdown,
    )
}

internal data class SpacerNode(val height: Float) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout =
        ViewLayout(emptyList(), height)

    override fun toNode(): DocumentNode = DocumentNode.Spacer(height)
}

internal data class DividerNode(val color: PdfColor, val strokeWidth: Float) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout =
        ViewLayout(
            listOf(PdfElement.Line(x, y, x + maxWidth, y, color, strokeWidth)),
            strokeWidth + 4f,
        )

    override fun toNode(): DocumentNode = DocumentNode.Divider(color, strokeWidth)
}

internal data class RectNode(
    val width: Float?,
    val height: Float,
    val fillColor: PdfColor?,
    val strokeColor: PdfColor?,
    val strokeWidth: Float,
    val cornerRadius: Float,
) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout {
        val w = width ?: maxWidth
        return ViewLayout(
            listOf(
                PdfElement.Rect(
                    x,
                    y - height,
                    w,
                    height,
                    fillColor,
                    strokeColor,
                    strokeWidth,
                    cornerRadius,
                )
            ),
            height,
            w,
        )
    }

    override fun toNode(): DocumentNode = DocumentNode.Rect(
        width = width,
        height = height,
        fillColor = fillColor,
        strokeColor = strokeColor,
        strokeWidth = strokeWidth,
        cornerRadius = cornerRadius,
    )
}

internal class TableNode(
    private val buildBlock: TableBuilder.() -> Unit,
    private val availableWidth: Float,
) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout {
        val builder = TableBuilder(x, y, maxWidth)
        builder.buildBlock()
        return builder.build()
    }

    override fun toNode(): DocumentNode {
        val builder = TableBuilder(0f, 0f, availableWidth)
        builder.buildBlock()
        return builder.buildNode()
    }
}

internal data class ImageNode(
    val data: String,
    val width: Float,
    val height: Float,
    val align: TextAlign,
    val format: String,
) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout {
        val imgX =
            when (align) {
                TextAlign.LEFT -> x
                TextAlign.CENTER -> x + (maxWidth - width) / 2f
                TextAlign.RIGHT -> x + maxWidth - width
            }
        return ViewLayout(
            listOf(PdfElement.Image(imgX, y - height, width, height, data, format)),
            height,
            width,
        )
    }

    override fun toNode(): DocumentNode = DocumentNode.Image(
        data = data,
        width = width,
        height = height,
        align = align.toTreeTextAlign(),
        format = format,
    )
}

internal data class SvgNode(
    val content: String,
    val width: Float,
    val height: Float,
    val align: TextAlign,
) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout {
        val svgX =
            when (align) {
                TextAlign.LEFT -> x
                TextAlign.CENTER -> x + (maxWidth - width) / 2f
                TextAlign.RIGHT -> x + maxWidth - width
            }
        return ViewLayout(
            listOf(PdfElement.Svg(content, svgX, y - height, width, height)),
            height,
            width,
        )
    }

    override fun toNode(): DocumentNode = DocumentNode.Svg(
        content = content,
        width = width,
        height = height,
        align = align.toTreeTextAlign(),
    )
}

internal data class BulletListNode(
    val items: List<String>,
    val bulletColor: PdfColor,
    val fontSize: Float,
    val font: String,
    val color: PdfColor,
    val lineSpacing: Float,
    override val pageSplitStrategy: PageSplitStrategy = PageSplitStrategy.NONE,
    val markdown: Boolean = false,
) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout {
        val bulletIndent = 14f
        val textX = x + bulletIndent
        val textWidth = maxWidth - bulletIndent
        val lineHeight = fontSize * lineSpacing
        val ascender = fontSize * ASCENDER_RATIO
        val elements = mutableListOf<PdfElement>()
        var currentY = y
        for (item in items) {
            val lines = TextMeasure.wrapText(item, font, fontSize, textWidth)
            elements.add(
                PdfElement.Text("\u2022", x + 2f, currentY - ascender, fontSize, font, bulletColor)
            )
            for (line in lines) {
                elements.add(
                    PdfElement.Text(line, textX, currentY - ascender, fontSize, font, color)
                )
                currentY -= lineHeight
            }
            currentY -= 2f
        }
        return ViewLayout(elements, y - currentY)
    }

    override fun toNode(): DocumentNode = DocumentNode.BulletList(
        items = items,
        bulletColor = bulletColor,
        fontSize = fontSize,
        font = font,
        color = color,
        lineSpacing = lineSpacing,
        splitStrategy = pageSplitStrategy.toTreeSplitStrategy(),
        markdown = markdown,
    )
}

internal data class ColumnNode(
    val gap: Float,
    val children: List<PdfView>,
    val horizontalAlignment: HorizontalAlignment = HorizontalAlignment.Start,
    override val pageSplitStrategy: PageSplitStrategy = PageSplitStrategy.SPLIT_NEAREST_VIEW,
) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout {
        val elements = mutableListOf<PdfElement>()
        var currentY = y
        var maxChildWidth = 0f
        for ((i, child) in children.withIndex()) {
            if (i > 0 && gap > 0f) currentY -= gap
            val result = child.layout(x, currentY, maxWidth)
            val childWidth = if (result.width > 0f) result.width else maxWidth
            val dx =
                when (horizontalAlignment) {
                    HorizontalAlignment.Start -> 0f
                    HorizontalAlignment.CenterHorizontally -> (maxWidth - childWidth) / 2f
                    HorizontalAlignment.End -> maxWidth - childWidth
                }
            elements.addAll(result.elements.offset(dx = dx))
            maxChildWidth = maxOf(maxChildWidth, childWidth)
            currentY -= result.height
        }
        return ViewLayout(elements, y - currentY, maxChildWidth)
    }

    override fun toNode(): DocumentNode = DocumentNode.Column(
        gap = gap,
        alignment = horizontalAlignment.toTreeHorizontalAlignment(),
        splitStrategy = pageSplitStrategy.toTreeSplitStrategy(),
        children = children.map { it.toNode() },
    )
}

internal data class RowCell(val weight: Float, val fixedWidth: Float?, val children: List<PdfView>)

internal data class RowNode(
    val gap: Float,
    val cells: List<RowCell>,
    val verticalAlignment: VerticalAlignment = VerticalAlignment.Top,
) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout {
        if (cells.isEmpty()) return ViewLayout(emptyList(), 0f)
        val totalGap = gap * (cells.size - 1)
        val fixedTotal = cells.mapNotNull { it.fixedWidth }.sum()
        val flexSpace = maxWidth - fixedTotal - totalGap
        val totalWeight =
            cells.filter { it.fixedWidth == null }.sumOf { it.weight.toDouble() }.toFloat()

        data class CellResult(val layout: ViewLayout, val width: Float)

        val cellResults = mutableListOf<CellResult>()
        var cellX = x
        var maxHeight = 0f
        for (cell in cells) {
            val cellWidth =
                cell.fixedWidth
                    ?: if (totalWeight > 0f) (flexSpace * cell.weight / totalWeight) else 0f
            val result = ColumnNode(0f, cell.children).layout(cellX, y, cellWidth)
            cellResults.add(CellResult(result, cellWidth))
            maxHeight = maxOf(maxHeight, result.height)
            cellX += cellWidth + gap
        }

        val elements = mutableListOf<PdfElement>()
        for (cellResult in cellResults) {
            val dy =
                when (verticalAlignment) {
                    VerticalAlignment.Top -> 0f
                    VerticalAlignment.CenterVertically ->
                        -(maxHeight - cellResult.layout.height) / 2f
                    VerticalAlignment.Bottom -> -(maxHeight - cellResult.layout.height)
                }
            elements.addAll(cellResult.layout.elements.offset(dy = dy))
        }
        return ViewLayout(elements, maxHeight)
    }

    override fun toNode(): DocumentNode = DocumentNode.Row(
        gap = gap,
        alignment = verticalAlignment.toTreeVerticalAlignment(),
        cells = cells.map { cell ->
            TreeRowCell(
                weight = if (cell.fixedWidth != null) null else cell.weight,
                fixedWidth = cell.fixedWidth,
                children = cell.children.map { it.toNode() },
            )
        },
    )
}

internal data class AccentBarNode(
    val barColor: PdfColor,
    val barWidth: Float,
    val background: PdfColor?,
    val padding: Float,
    val cornerRadius: Float,
    val children: List<PdfView>,
) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout {
        val contentX = x + barWidth + padding
        val contentMaxWidth = maxWidth - barWidth - padding * 2
        val contentResult = ColumnNode(0f, children).layout(contentX, 0f, contentMaxWidth)
        val totalHeight = contentResult.height + padding * 2

        val boxCenterY = y - totalHeight / 2f
        val contentCenterY = visualCenterY(contentResult.elements) ?: (-contentResult.height / 2f)
        val dy = boxCenterY - contentCenterY

        val elements = mutableListOf<PdfElement>()
        val useClip = cornerRadius > 0f
        if (useClip) {
            elements.add(
                PdfElement.ClipStart(x, y - totalHeight, maxWidth, totalHeight, cornerRadius)
            )
        }
        if (background != null) {
            elements.add(PdfElement.Rect(x, y - totalHeight, maxWidth, totalHeight, background))
        }
        elements.add(PdfElement.Rect(x, y - totalHeight, barWidth, totalHeight, barColor))
        elements.addAll(contentResult.elements.offset(dy = dy))
        if (useClip) {
            elements.add(PdfElement.ClipEnd)
        }
        return ViewLayout(elements, totalHeight)
    }

    override fun toNode(): DocumentNode = DocumentNode.AccentBar(
        color = barColor,
        barWidth = barWidth,
        background = background,
        cornerRadius = cornerRadius,
        padding = padding,
        children = children.map { it.toNode() },
    )
}

private fun visualCenterY(elements: List<PdfElement>): Float? {
    val centerYs =
        elements.mapNotNull { el ->
            when (el) {
                is PdfElement.Text -> el.y + el.fontSize * 0.2f
                is PdfElement.Rect -> el.y + el.height / 2f
                is PdfElement.Image -> el.y + el.height / 2f
                is PdfElement.Line -> (el.y1 + el.y2) / 2f
                is PdfElement.Sector -> el.cy
                is PdfElement.Polygon -> el.points.map { it.y }.average().toFloat()
                is PdfElement.Polyline -> el.points.map { it.y }.average().toFloat()
                is PdfElement.Svg -> el.y + el.height / 2f
                is PdfElement.ClipStart,
                is PdfElement.ClipEnd -> null
            }
        }
    if (centerYs.isEmpty()) return null
    return (centerYs.min() + centerYs.max()) / 2f
}

internal data class PaddedNode(
    val padding: Padding,
    val background: PdfColor?,
    val cornerRadius: Float,
    val children: List<PdfView>,
) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout {
        val innerWidth = maxWidth - padding.left - padding.right
        val result = ColumnNode(0f, children).layout(x + padding.left, y - padding.top, innerWidth)
        val totalHeight = result.height + padding.top + padding.bottom
        val elements = buildList {
            if (background != null) {
                add(
                    PdfElement.Rect(
                        x,
                        y - totalHeight,
                        maxWidth,
                        totalHeight,
                        background,
                        cornerRadius = cornerRadius,
                    )
                )
            }
            addAll(result.elements)
        }
        return ViewLayout(elements, totalHeight)
    }

    override fun toNode(): DocumentNode = DocumentNode.Padded(
        padding = TreePadding(padding.top, padding.right, padding.bottom, padding.left),
        background = background,
        cornerRadius = cornerRadius,
        children = children.map { it.toNode() },
    )
}

internal data class GridCellDef(val columnSpan: Int, val children: List<PdfView>)

internal data class GridRowDef(val cells: List<GridCellDef>, val background: PdfColor?)

internal class GridNode(
    private val resolvedWidths: List<Float>,
    private val rows: List<GridRowDef>,
    private val cellPadding: Padding,
    private val borderColor: PdfColor?,
    private val columnDefs: List<GridColumnDef> = resolvedWidths.map { GridColumnDef.Fixed(it) },
) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout {
        val elements = mutableListOf<PdfElement>()
        var currentY = y

        for (row in rows) {
            var colIdx = 0
            val cellLayouts = mutableListOf<Triple<ViewLayout, Float, Float>>()

            // First pass: layout each cell and determine row height
            var cellX = x
            for (cell in row.cells) {
                val span = cell.columnSpan.coerceIn(1, resolvedWidths.size - colIdx)
                val cellWidth =
                    (colIdx until colIdx + span).sumOf { resolvedWidths[it].toDouble() }.toFloat()
                val innerWidth =
                    (cellWidth - cellPadding.left - cellPadding.right).coerceAtLeast(0f)

                val result =
                    ColumnNode(0f, cell.children)
                        .layout(cellX + cellPadding.left, currentY - cellPadding.top, innerWidth)
                cellLayouts.add(Triple(result, cellX, cellWidth))
                cellX += cellWidth
                colIdx += span
            }

            val maxCellHeight =
                cellLayouts.maxOfOrNull { it.first.height + cellPadding.top + cellPadding.bottom }
                    ?: 0f

            // Row background
            if (row.background != null) {
                elements.add(
                    PdfElement.Rect(
                        x,
                        currentY - maxCellHeight,
                        maxWidth,
                        maxCellHeight,
                        row.background,
                    )
                )
            }

            // Cell contents
            for ((layout, _, _) in cellLayouts) {
                elements.addAll(layout.elements)
            }

            // Row bottom border
            if (borderColor != null) {
                elements.add(
                    PdfElement.Line(
                        x,
                        currentY - maxCellHeight,
                        x + maxWidth,
                        currentY - maxCellHeight,
                        borderColor,
                        0.5f,
                    )
                )
            }

            currentY -= maxCellHeight
        }

        return ViewLayout(elements, y - currentY)
    }

    override fun toNode(): DocumentNode = DocumentNode.Grid(
        columns = columnDefs.map { col ->
            when (col) {
                is GridColumnDef.Fixed -> TreeGridColumnDef.Fixed(col.width)
                is GridColumnDef.Weight -> TreeGridColumnDef.Weight(col.weight)
            }
        },
        cellPadding = TreePadding(
            cellPadding.top, cellPadding.right, cellPadding.bottom, cellPadding.left,
        ),
        borderColor = borderColor,
        rows = rows.map { row ->
            TreeGridRow(
                background = row.background,
                cells = row.cells.map { cell ->
                    TreeGridCell(
                        span = cell.columnSpan,
                        children = cell.children.map { it.toNode() },
                    )
                },
            )
        },
    )
}

internal data class StackNode(
    val children: List<PdfView>,
    val verticalAlignment: VerticalAlignment,
) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout {
        if (children.isEmpty()) return ViewLayout(emptyList(), 0f)
        val childLayouts = children.map { it.layout(x, y, maxWidth) }
        val maxHeight = childLayouts.maxOf { it.height }
        val containerCenter = y - maxHeight / 2f
        val elements = mutableListOf<PdfElement>()
        for (result in childLayouts) {
            val dy =
                when (verticalAlignment) {
                    VerticalAlignment.Top -> 0f
                    VerticalAlignment.CenterVertically -> {
                        if (result.height >= maxHeight - 0.01f) {
                            0f
                        } else {
                            val childCenter =
                                visualCenterY(result.elements) ?: (y - result.height / 2f)
                            containerCenter - childCenter
                        }
                    }
                    VerticalAlignment.Bottom -> -(maxHeight - result.height)
                }
            elements.addAll(result.elements.offset(dy = dy))
        }
        return ViewLayout(elements, maxHeight)
    }

    override fun toNode(): DocumentNode = DocumentNode.Stack(
        alignment = verticalAlignment.toTreeVerticalAlignment(),
        children = children.map { it.toNode() },
    )
}

internal data class OverlayNode(val element: PdfElement) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout =
        ViewLayout(listOf(element), 0f)

    override fun toNode(): DocumentNode = DocumentNode.Overlay(elements = listOf(element))
}

internal class CanvasNode(
    private val canvasHeight: Float,
    private val block: CanvasScope.() -> Unit,
    private val availableWidth: Float,
) : PdfView {
    override fun layout(x: Float, y: Float, maxWidth: Float): ViewLayout {
        val scope = CanvasScope(x, y, maxWidth, canvasHeight)
        scope.block()
        return ViewLayout(scope.elements, canvasHeight)
    }

    override fun toNode(): DocumentNode {
        val scope = CanvasScope(0f, 0f, availableWidth, canvasHeight, yDown = true)
        scope.block()
        return DocumentNode.Canvas(height = canvasHeight, elements = scope.elements)
    }
}


internal fun TextAlign.toTreeTextAlign(): TreeTextAlign = when (this) {
    TextAlign.LEFT -> TreeTextAlign.LEFT
    TextAlign.CENTER -> TreeTextAlign.CENTER
    TextAlign.RIGHT -> TreeTextAlign.RIGHT
}

internal fun HorizontalAlignment.toTreeHorizontalAlignment(): TreeHorizontalAlignment = when (this) {
    HorizontalAlignment.Start -> TreeHorizontalAlignment.Start
    HorizontalAlignment.CenterHorizontally -> TreeHorizontalAlignment.CenterHorizontally
    HorizontalAlignment.End -> TreeHorizontalAlignment.End
}

internal fun VerticalAlignment.toTreeVerticalAlignment(): TreeVerticalAlignment = when (this) {
    VerticalAlignment.Top -> TreeVerticalAlignment.Top
    VerticalAlignment.CenterVertically -> TreeVerticalAlignment.CenterVertically
    VerticalAlignment.Bottom -> TreeVerticalAlignment.Bottom
}

internal fun PageSplitStrategy.toTreeSplitStrategy(): TreeSplitStrategy = when (this) {
    PageSplitStrategy.NONE -> TreeSplitStrategy.NONE
    PageSplitStrategy.SPLIT_NEAREST_VIEW -> TreeSplitStrategy.SPLIT_NEAREST_VIEW
    PageSplitStrategy.SPLIT_CENTER -> TreeSplitStrategy.SPLIT_CENTER
    PageSplitStrategy.SPLIT_ANYWHERE -> TreeSplitStrategy.SPLIT_ANYWHERE
}
