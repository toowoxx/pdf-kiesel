package de.toowoxx.pdfkiesel.dsl

import de.toowoxx.pdfkiesel.model.DocumentNode
import de.toowoxx.pdfkiesel.model.PdfColor
import de.toowoxx.pdfkiesel.model.PdfElement
import de.toowoxx.pdfkiesel.model.TreeGridCell
import de.toowoxx.pdfkiesel.model.TreeGridColumnDef
import de.toowoxx.pdfkiesel.model.TreeGridRow
import de.toowoxx.pdfkiesel.model.TreeHorizontalAlignment
import de.toowoxx.pdfkiesel.model.TreePadding
import de.toowoxx.pdfkiesel.model.TreeRowCell
import de.toowoxx.pdfkiesel.model.TreeSplitStrategy
import de.toowoxx.pdfkiesel.model.TreeTextAlign
import de.toowoxx.pdfkiesel.model.TreeVerticalAlignment

internal sealed interface PdfView {
    fun toNode(): DocumentNode
}

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
    override fun toNode(): DocumentNode = DocumentNode.Spacer(height)
}

internal data class DividerNode(val color: PdfColor, val strokeWidth: Float) : PdfView {
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
    private val defaultFont: String = "",
) : PdfView {
    override fun toNode(): DocumentNode {
        val builder = TableBuilder(availableWidth, defaultFont)
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
    val pageSplitStrategy: PageSplitStrategy = PageSplitStrategy.NONE,
    val markdown: Boolean = false,
) : PdfView {
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
    val pageSplitStrategy: PageSplitStrategy = PageSplitStrategy.SPLIT_NEAREST_VIEW,
) : PdfView {
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
    override fun toNode(): DocumentNode = DocumentNode.AccentBar(
        color = barColor,
        barWidth = barWidth,
        background = background,
        cornerRadius = cornerRadius,
        padding = padding,
        children = children.map { it.toNode() },
    )
}

internal data class PaddedNode(
    val padding: Padding,
    val background: PdfColor?,
    val cornerRadius: Float,
    val children: List<PdfView>,
) : PdfView {
    override fun toNode(): DocumentNode = DocumentNode.Padded(
        padding = TreePadding(padding.top, padding.right, padding.bottom, padding.left),
        background = background,
        cornerRadius = cornerRadius,
        children = children.map { it.toNode() },
    )
}

internal data class GridCellDef(val columnSpan: Int, val children: List<PdfView>)

internal data class GridRowDef(val cells: List<GridCellDef>, val background: PdfColor?, val skipTopBorder: Boolean = false)

internal class GridNode(
    private val rows: List<GridRowDef>,
    private val columnDefs: List<GridColumnDef>,
    private val cellPadding: Padding,
    private val borderColor: PdfColor?,
) : PdfView {
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
                skipTopBorder = row.skipTopBorder,
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
    override fun toNode(): DocumentNode = DocumentNode.Stack(
        alignment = verticalAlignment.toTreeVerticalAlignment(),
        children = children.map { it.toNode() },
    )
}

internal data class OverlayNode(val element: PdfElement) : PdfView {
    override fun toNode(): DocumentNode = DocumentNode.Overlay(elements = listOf(element))
}

internal class CanvasNode(
    private val canvasHeight: Float,
    private val block: CanvasScope.() -> Unit,
    private val availableWidth: Float,
    private val defaultFont: String = "",
) : PdfView {
    override fun toNode(): DocumentNode {
        val scope = CanvasScope(0f, 0f, availableWidth, canvasHeight, yDown = true, defaultFont = defaultFont)
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

@PublishedApi
internal fun PageSplitStrategy.toTreeSplitStrategy(): TreeSplitStrategy = when (this) {
    PageSplitStrategy.NONE -> TreeSplitStrategy.NONE
    PageSplitStrategy.SPLIT_NEAREST_VIEW -> TreeSplitStrategy.SPLIT_NEAREST_VIEW
    PageSplitStrategy.SPLIT_CENTER -> TreeSplitStrategy.SPLIT_CENTER
    PageSplitStrategy.SPLIT_ANYWHERE -> TreeSplitStrategy.SPLIT_ANYWHERE
}
