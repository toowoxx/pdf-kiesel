package de.toowoxx.pdfkiesel.dsl

import de.toowoxx.pdfkiesel.model.PdfColor

@PdfDslMarker
class PageBuilder
@PublishedApi
internal constructor(
    @PublishedApi internal val availableWidth: Float,
    private val nonSplittable: Boolean = false,
    @PublishedApi internal val defaultFont: String = "",
) {
    internal val children = mutableListOf<PdfView>()

    @PublishedApi
    internal fun buildContent() = children.map { it.toNode() }

    val contentWidth: Float
        get() = availableWidth

    fun text(content: String, block: TextStyle.() -> Unit = {}) {
        val s = TextStyle().apply(block)
        children.add(TextNode(content, s.fontSize, s.font, s.color, s.align, s.lineSpacing, s.bold, s.italic))
    }

    fun paragraph(content: String, block: TextStyle.() -> Unit = {}) {
        val s = TextStyle().apply(block)
        children.add(ParagraphNode(content, s.fontSize, s.font, s.color, s.align, s.lineSpacing, s.bold))
    }

    fun spacer(height: Float) {
        children.add(SpacerNode(height))
    }

    fun divider(color: PdfColor = PdfColor(0.8f, 0.8f, 0.8f), strokeWidth: Float = 0.5f) {
        children.add(DividerNode(color, strokeWidth))
    }

    fun rect(width: Float? = null, height: Float, block: RectStyle.() -> Unit = {}) {
        val s = RectStyle().apply(block)
        children.add(
            RectNode(width, height, s.fillColor, s.strokeColor, s.strokeWidth, s.cornerRadius)
        )
    }

    fun table(block: TableBuilder.() -> Unit) {
        children.add(TableNode(block, availableWidth, defaultFont))
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun image(
        imageData: ByteArray,
        width: Float,
        height: Float,
        align: TextAlign = TextAlign.LEFT,
        format: String = "png",
    ) {
        children.add(
            ImageNode(kotlin.io.encoding.Base64.encode(imageData), width, height, align, format)
        )
    }

    fun svg(
        content: String,
        width: Float,
        height: Float,
        align: TextAlign = TextAlign.LEFT,
    ) {
        children.add(SvgNode(content, width, height, align))
    }

    fun bulletList(
        items: List<String>,
        bulletColor: PdfColor = PdfColor.BLACK,
        splitStrategy: PageSplitStrategy = PageSplitStrategy.NONE,
        block: TextStyle.() -> Unit = {},
    ) {
        val effective = if (nonSplittable) PageSplitStrategy.NONE else splitStrategy
        val s = TextStyle().apply(block)
        children.add(
            BulletListNode(
                items,
                bulletColor,
                s.fontSize,
                s.font,
                s.color,
                s.lineSpacing,
                effective,
            )
        )
    }

    fun richParagraph(content: String, block: TextStyle.() -> Unit = {}) {
        val s = TextStyle().apply(block)
        children.add(
            ParagraphNode(
                content = content,
                fontSize = s.fontSize,
                font = s.font,
                color = s.color,
                align = TextAlign.LEFT,
                lineSpacing = s.lineSpacing,
                markdown = true,
            )
        )
    }

    fun richBulletList(
        items: List<String>,
        bulletColor: PdfColor = PdfColor.BLACK,
        splitStrategy: PageSplitStrategy = PageSplitStrategy.NONE,
        block: TextStyle.() -> Unit = {},
    ) {
        val effective = if (nonSplittable) PageSplitStrategy.NONE else splitStrategy
        val s = TextStyle().apply(block)
        children.add(
            BulletListNode(
                items,
                bulletColor,
                s.fontSize,
                s.font,
                s.color,
                s.lineSpacing,
                effective,
                markdown = true,
            )
        )
    }

    fun column(
        gap: Float = 0f,
        horizontalAlignment: HorizontalAlignment = HorizontalAlignment.Start,
        splitStrategy: PageSplitStrategy = PageSplitStrategy.SPLIT_NEAREST_VIEW,
        block: PageBuilder.() -> Unit,
    ) {
        val effective = if (nonSplittable) PageSplitStrategy.NONE else splitStrategy
        val scope = PageBuilder(availableWidth, nonSplittable = effective == PageSplitStrategy.NONE, defaultFont = defaultFont)
        scope.block()
        children.add(ColumnNode(gap, scope.children, horizontalAlignment, effective))
    }

    fun row(
        gap: Float = 0f,
        verticalAlignment: VerticalAlignment = VerticalAlignment.Top,
        block: RowScope.() -> Unit,
    ) {
        val scope = RowScope(availableWidth, gap, defaultFont)
        scope.block()
        children.add(RowNode(gap, scope.cells, verticalAlignment))
    }

    fun accentBar(
        color: PdfColor,
        barWidth: Float = 3f,
        background: PdfColor? = null,
        padding: Float = 4f,
        cornerRadius: Float = 0f,
        block: PageBuilder.() -> Unit,
    ) {
        val innerWidth = availableWidth - barWidth - padding * 2
        val scope = PageBuilder(innerWidth, defaultFont = defaultFont)
        scope.block()
        children.add(
            AccentBarNode(color, barWidth, background, padding, cornerRadius, scope.children)
        )
    }

    fun padded(
        padding: Padding,
        background: PdfColor? = null,
        cornerRadius: Float = 0f,
        block: PageBuilder.() -> Unit,
    ) {
        val innerWidth = availableWidth - padding.left - padding.right
        val scope = PageBuilder(innerWidth, defaultFont = defaultFont)
        scope.block()
        children.add(PaddedNode(padding, background, cornerRadius, scope.children))
    }

    fun box(
        background: PdfColor,
        padding: Float = 4f,
        cornerRadius: Float = 0f,
        block: PageBuilder.() -> Unit,
    ) {
        padded(Padding(padding), background, cornerRadius, block)
    }

    fun grid(
        columns: List<GridColumnDef>,
        cellPadding: Padding = Padding(6f, 6f),
        borderColor: PdfColor? = null,
        block: GridScope.() -> Unit,
    ) {
        val resolvedWidths = resolveColumnWidths(columns, availableWidth)
        val scope = GridScope(resolvedWidths, defaultFont)
        scope.block()
        children.add(GridNode(scope.rows, columns, cellPadding, borderColor))
    }

    fun stack(
        verticalAlignment: VerticalAlignment = VerticalAlignment.Top,
        block: PageBuilder.() -> Unit,
    ) {
        val scope = PageBuilder(availableWidth, defaultFont = defaultFont)
        scope.block()
        children.add(StackNode(scope.children, verticalAlignment))
    }

    fun canvas(height: Float, block: CanvasScope.() -> Unit) {
        children.add(CanvasNode(height, block, availableWidth, defaultFont))
    }

    fun overlay(block: CanvasScope.() -> Unit) {
        val scope = CanvasScope(0f, 0f, availableWidth, 0f, yDown = true, defaultFont = defaultFont)
        scope.block()
        for (element in scope.elements) {
            children.add(OverlayNode(element))
        }
    }
}

@PdfDslMarker
class RowScope internal constructor(private val availableWidth: Float, private val gap: Float, private val defaultFont: String = "") {
    private class CellDef(
        val weight: Float,
        val fixedWidth: Float?,
        val block: PageBuilder.() -> Unit,
    )

    private val cellDefs = mutableListOf<CellDef>()

    fun cell(weight: Float = 1f, width: Float? = null, block: PageBuilder.() -> Unit) {
        cellDefs.add(CellDef(weight, width, block))
    }

    internal val cells: List<RowCell> by lazy {
        val totalGap = gap * (cellDefs.size - 1).coerceAtLeast(0)
        val fixedTotal = cellDefs.mapNotNull { it.fixedWidth }.sum()
        val flexSpace = availableWidth - fixedTotal - totalGap
        val totalWeight =
            cellDefs.filter { it.fixedWidth == null }.sumOf { it.weight.toDouble() }.toFloat()

        cellDefs.map { def ->
            val cellWidth =
                def.fixedWidth
                    ?: if (totalWeight > 0f) (flexSpace * def.weight / totalWeight) else 0f
            val scope = PageBuilder(cellWidth, defaultFont = defaultFont)
            def.block(scope)
            RowCell(def.weight, def.fixedWidth, scope.children)
        }
    }
}

@PdfDslMarker
class GridScope internal constructor(private val resolvedWidths: List<Float>, private val defaultFont: String = "") {
    internal val rows = mutableListOf<GridRowDef>()

    fun row(background: PdfColor? = null, block: GridRowScope.() -> Unit) {
        val scope = GridRowScope(resolvedWidths, defaultFont)
        scope.block()
        rows.add(GridRowDef(scope.cells, background))
    }
}

@PdfDslMarker
class GridRowScope internal constructor(private val widths: List<Float>, private val defaultFont: String = "") {
    internal val cells = mutableListOf<GridCellDef>()
    private var colIdx = 0

    fun cell(span: Int = 1, block: PageBuilder.() -> Unit = {}) {
        val actualSpan = span.coerceIn(1, widths.size - colIdx)
        val cellWidth = (colIdx until colIdx + actualSpan).sumOf { widths[it].toDouble() }.toFloat()
        val scope = PageBuilder(cellWidth, defaultFont = defaultFont)
        scope.block()
        cells.add(GridCellDef(actualSpan, scope.children))
        colIdx += actualSpan
    }
}

internal fun resolveColumnWidths(columns: List<GridColumnDef>, totalWidth: Float): List<Float> {
    val fixedTotal =
        columns.filterIsInstance<GridColumnDef.Fixed>().sumOf { it.width.toDouble() }.toFloat()
    val flexSpace = (totalWidth - fixedTotal).coerceAtLeast(0f)
    val totalWeight =
        columns.filterIsInstance<GridColumnDef.Weight>().sumOf { it.weight.toDouble() }.toFloat()
    return columns.map { col ->
        when (col) {
            is GridColumnDef.Fixed -> col.width
            is GridColumnDef.Weight ->
                if (totalWeight > 0) flexSpace * col.weight / totalWeight else 0f
        }
    }
}

