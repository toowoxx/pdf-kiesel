package de.toowoxx.pdfkiesel.dsl

import de.toowoxx.pdfkiesel.model.DocumentNode
import de.toowoxx.pdfkiesel.model.PdfColor

@PdfDslMarker
class TableBuilder
internal constructor(
    private val tableWidth: Float,
) {
    private val rows = mutableListOf<TableRow>()
    private var columnWidths: List<Float>? = null

    var cellPadding: Float = 6f
    var borderColor: PdfColor = PdfColor(0.6f, 0.6f, 0.6f)
    var headerBackground: PdfColor? = PdfColor(0.94f, 0.94f, 0.94f)
    var alternateRowBackground: PdfColor? = null
    var font: String = ""
    var markdown: Boolean = false

    fun widths(vararg widths: Float) {
        columnWidths = widths.toList()
    }

    fun headerRow(block: TableRowBuilder.() -> Unit) = row(isHeader = true, block)

    fun row(isHeader: Boolean = false, block: TableRowBuilder.() -> Unit) {
        val builder = TableRowBuilder()
        builder.block()
        rows.add(TableRow(builder.cells, isHeader))
    }

    internal fun buildNode(): DocumentNode {
        val numCols = rows.maxOfOrNull { it.cells.size }
            ?: return DocumentNode.Column(children = emptyList())
        val resolvedWidths = columnWidths ?: List(numCols) { tableWidth / numCols }
        val columnDefs = resolvedWidths.map { GridColumnDef.Fixed(it) }

        val gridRows = rows.mapIndexed { rowIndex, row ->
            val bg = when {
                row.isHeader -> headerBackground
                alternateRowBackground != null && rowIndex % 2 == 1 -> alternateRowBackground
                else -> null
            }
            val gridCells = row.cells.map { cell ->
                val cellFont = cell.font.ifEmpty { font }
                val child = ParagraphNode(
                    content = cell.content,
                    fontSize = cell.fontSize,
                    font = cellFont,
                    color = cell.color,
                    align = cell.align,
                    lineSpacing = 1.3f,
                    bold = cell.bold,
                    markdown = markdown,
                )
                GridCellDef(columnSpan = 1, children = listOf(child))
            }
            GridRowDef(gridCells, bg)
        }

        val grid = GridNode(gridRows, columnDefs, Padding(cellPadding), borderColor)
        return grid.toNode()
    }
}

@PdfDslMarker
class TableRowBuilder internal constructor() {
    internal val cells = mutableListOf<TableCell>()

    fun cell(content: String, block: CellStyle.() -> Unit = {}) {
        val style = CellStyle().apply(block)
        cells.add(
            TableCell(
                content = content,
                fontSize = style.fontSize,
                font = style.font,
                color = style.color,
                align = style.align,
                bold = style.bold,
            )
        )
    }
}

class CellStyle {
    var fontSize: Float = 11f
    var font: String = ""
    var color: PdfColor = PdfColor.BLACK
    var align: TextAlign = TextAlign.LEFT
    var bold: Boolean = false
}

internal data class TableCell(
    val content: String,
    val fontSize: Float,
    val font: String,
    val color: PdfColor,
    val align: TextAlign,
    val bold: Boolean = false,
)

internal data class TableRow(val cells: List<TableCell>, val isHeader: Boolean)
