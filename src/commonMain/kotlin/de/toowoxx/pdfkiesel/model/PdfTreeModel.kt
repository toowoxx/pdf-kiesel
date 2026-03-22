package de.toowoxx.pdfkiesel.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RichSegment(
    val text: String,
    val bold: Boolean = false,
)

@Serializable
enum class TreeTextAlign {
    @SerialName("LEFT") LEFT,
    @SerialName("CENTER") CENTER,
    @SerialName("RIGHT") RIGHT,
}

@Serializable
enum class TreeHorizontalAlignment {
    @SerialName("Start") Start,
    @SerialName("CenterHorizontally") CenterHorizontally,
    @SerialName("End") End,
}

@Serializable
enum class TreeVerticalAlignment {
    @SerialName("Top") Top,
    @SerialName("CenterVertically") CenterVertically,
    @SerialName("Bottom") Bottom,
}

@Serializable
enum class TreeSplitStrategy {
    @SerialName("NONE") NONE,
    @SerialName("SPLIT_NEAREST_VIEW") SPLIT_NEAREST_VIEW,
    @SerialName("SPLIT_CENTER") SPLIT_CENTER,
    @SerialName("SPLIT_ANYWHERE") SPLIT_ANYWHERE,
}

@Serializable
sealed interface TreeGridColumnDef {
    @Serializable
    @SerialName("fixed")
    data class Fixed(val width: Float) : TreeGridColumnDef

    @Serializable
    @SerialName("weight")
    data class Weight(val weight: Float = 1f) : TreeGridColumnDef
}

@Serializable
data class TreeMargin(
    val top: Float = 50f,
    val bottom: Float = 50f,
    val left: Float = 50f,
    val right: Float = 50f,
)

@Serializable
data class TreePadding(
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val left: Float = 0f,
)

@Serializable
data class TreeRowCell(
    val weight: Float? = null,
    val fixedWidth: Float? = null,
    val children: List<DocumentNode> = emptyList(),
)

@Serializable
data class TreeGridRow(
    val background: PdfColor? = null,
    val cells: List<TreeGridCell> = emptyList(),
)

@Serializable
data class TreeGridCell(
    val span: Int = 1,
    val children: List<DocumentNode> = emptyList(),
)

@Serializable
sealed interface DocumentNode {

    @Serializable
    @SerialName("text")
    data class Text(
        val content: String,
        val fontSize: Float = 12f,
        val font: String = "",
        val color: PdfColor = PdfColor.BLACK,
        val align: TreeTextAlign = TreeTextAlign.LEFT,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val lineSpacing: Float = 1.4f,
    ) : DocumentNode

    @Serializable
    @SerialName("paragraph")
    data class Paragraph(
        val content: String,
        val fontSize: Float = 12f,
        val font: String = "",
        val color: PdfColor = PdfColor.BLACK,
        val align: TreeTextAlign = TreeTextAlign.LEFT,
        val lineSpacing: Float = 1.4f,
        val bold: Boolean = false,
        val markdown: Boolean = false,
    ) : DocumentNode

    @Serializable
    @SerialName("richParagraph")
    data class RichParagraph(
        val segments: List<RichSegment>,
        val fontSize: Float = 12f,
        val font: String = "",
        val color: PdfColor = PdfColor.BLACK,
        val lineSpacing: Float = 1.4f,
    ) : DocumentNode

    @Serializable
    @SerialName("bulletList")
    data class BulletList(
        val items: List<String>,
        val bulletColor: PdfColor = PdfColor.BLACK,
        val fontSize: Float = 12f,
        val font: String = "",
        val color: PdfColor = PdfColor.BLACK,
        val lineSpacing: Float = 1.4f,
        val splitStrategy: TreeSplitStrategy = TreeSplitStrategy.NONE,
        val bulletIndent: Float = 14f,
        val itemGap: Float = 2f,
        val markdown: Boolean = false,
    ) : DocumentNode

    @Serializable
    @SerialName("richBulletList")
    data class RichBulletList(
        val items: List<List<RichSegment>>,
        val bulletColor: PdfColor = PdfColor.BLACK,
        val fontSize: Float = 12f,
        val font: String = "",
        val color: PdfColor = PdfColor.BLACK,
        val lineSpacing: Float = 1.4f,
        val splitStrategy: TreeSplitStrategy = TreeSplitStrategy.NONE,
        val bulletIndent: Float = 14f,
        val itemGap: Float = 2f,
    ) : DocumentNode

    @Serializable
    @SerialName("spacer")
    data class Spacer(val height: Float) : DocumentNode

    @Serializable
    @SerialName("divider")
    data class Divider(
        val color: PdfColor = PdfColor.BLACK,
        val strokeWidth: Float = 0.5f,
    ) : DocumentNode

    @Serializable
    @SerialName("rect")
    data class Rect(
        val width: Float? = null,
        val height: Float,
        val fillColor: PdfColor? = null,
        val strokeColor: PdfColor? = null,
        val strokeWidth: Float = 1f,
        val cornerRadius: Float = 0f,
    ) : DocumentNode

    @Serializable
    @SerialName("image")
    data class Image(
        val data: String,
        val width: Float,
        val height: Float,
        val align: TreeTextAlign = TreeTextAlign.LEFT,
        val format: String = "png",
    ) : DocumentNode

    @Serializable
    @SerialName("svg")
    data class Svg(
        val content: String,
        val width: Float,
        val height: Float,
        val align: TreeTextAlign = TreeTextAlign.LEFT,
    ) : DocumentNode

    @Serializable
    @SerialName("column")
    data class Column(
        val gap: Float = 0f,
        val alignment: TreeHorizontalAlignment = TreeHorizontalAlignment.Start,
        val splitStrategy: TreeSplitStrategy = TreeSplitStrategy.NONE,
        val children: List<DocumentNode> = emptyList(),
    ) : DocumentNode

    @Serializable
    @SerialName("row")
    data class Row(
        val gap: Float = 0f,
        val alignment: TreeVerticalAlignment = TreeVerticalAlignment.Top,
        val cells: List<TreeRowCell>,
    ) : DocumentNode

    @Serializable
    @SerialName("padded")
    data class Padded(
        val padding: TreePadding = TreePadding(),
        val background: PdfColor? = null,
        val cornerRadius: Float = 0f,
        val children: List<DocumentNode> = emptyList(),
    ) : DocumentNode

    @Serializable
    @SerialName("accentBar")
    data class AccentBar(
        val color: PdfColor,
        val barWidth: Float = 3f,
        val background: PdfColor? = null,
        val cornerRadius: Float = 0f,
        val padding: Float = 0f,
        val children: List<DocumentNode> = emptyList(),
    ) : DocumentNode

    @Serializable
    @SerialName("grid")
    data class Grid(
        val columns: List<TreeGridColumnDef>,
        val cellPadding: TreePadding = TreePadding(),
        val borderColor: PdfColor? = null,
        val rows: List<TreeGridRow> = emptyList(),
    ) : DocumentNode

    @Serializable
    @SerialName("stack")
    data class Stack(
        val alignment: TreeVerticalAlignment = TreeVerticalAlignment.Top,
        val children: List<DocumentNode> = emptyList(),
    ) : DocumentNode

    @Serializable
    @SerialName("canvas")
    data class Canvas(
        val height: Float,
        val elements: List<PdfElement> = emptyList(),
    ) : DocumentNode

    @Serializable
    @SerialName("overlay")
    data class Overlay(
        val elements: List<PdfElement> = emptyList(),
    ) : DocumentNode
}

@Serializable
data class TreePage(
    val width: Float = PageSize.A4_WIDTH,
    val height: Float = PageSize.A4_HEIGHT,
    val margin: TreeMargin = TreeMargin(),
    val background: PdfColor? = null,
    val splitStrategy: TreeSplitStrategy = TreeSplitStrategy.NONE,
    val content: List<DocumentNode> = emptyList(),
)

@Serializable
data class TreeDocument(
    val fonts: Map<String, PdfFontDef> = emptyMap(),
    val pages: List<TreePage> = emptyList(),
) {
    fun toJson(): String = TREE_JSON.encodeToString(this)

    companion object {
        private val TREE_JSON = Json { encodeDefaults = true }
    }
}
