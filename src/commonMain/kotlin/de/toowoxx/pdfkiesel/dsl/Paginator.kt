package de.toowoxx.pdfkiesel.dsl

import de.toowoxx.pdfkiesel.model.PdfElement

internal object Paginator {

    fun paginate(
        children: List<PdfView>,
        x: Float,
        startY: Float,
        maxWidth: Float,
        gap: Float,
        pageContentHeight: Float,
        strategy: PageSplitStrategy,
    ): List<List<PdfElement>> {
        val measured = measureChildren(children, x, startY, maxWidth, gap)
        if (measured.isEmpty()) return listOf(emptyList())

        val totalHeight = measured.last().topOffset + measured.last().height
        if (totalHeight <= pageContentHeight + 0.1f) {
            return listOf(measured.flatMap { it.elements })
        }

        return distribute(measured, startY, pageContentHeight, strategy, x, maxWidth)
    }

    private data class MeasuredChild(
        val view: PdfView,
        val topOffset: Float,
        val height: Float,
        val elements: List<PdfElement>,
    )

    private fun measureChildren(
        children: List<PdfView>,
        x: Float,
        startY: Float,
        maxWidth: Float,
        gap: Float,
    ): List<MeasuredChild> {
        val result = mutableListOf<MeasuredChild>()
        var offset = 0f
        for ((i, child) in children.withIndex()) {
            if (i > 0 && gap > 0f) offset += gap
            val layout = child.layout(x, startY - offset, maxWidth)
            result.add(MeasuredChild(child, offset, layout.height, layout.elements))
            offset += layout.height
        }
        return result
    }

    private fun distribute(
        measured: List<MeasuredChild>,
        startY: Float,
        pageContentHeight: Float,
        strategy: PageSplitStrategy,
        x: Float,
        maxWidth: Float,
    ): List<List<PdfElement>> {
        val pages = mutableListOf<List<PdfElement>>()
        var currentPage = mutableListOf<PdfElement>()
        var pageTopOffset = 0f

        var i = 0
        while (i < measured.size) {
            val child = measured[i]
            val posOnPage = child.topOffset - pageTopOffset
            val endOnPage = posOnPage + child.height

            if (endOnPage <= pageContentHeight + 0.1f) {
                currentPage.addAll(child.elements)
                i++
                continue
            }

            val effectiveStrategy =
                if (child.view.pageSplitStrategy != PageSplitStrategy.NONE)
                    child.view.pageSplitStrategy
                else strategy

            when (effectiveStrategy) {
                PageSplitStrategy.NONE -> {
                    currentPage.addAll(child.elements)
                    i++
                }

                PageSplitStrategy.SPLIT_NEAREST_VIEW -> {
                    if (posOnPage > 0.1f) {
                        if (currentPage.isNotEmpty()) {
                            pages.add(currentPage.toList().offset(dy = pageTopOffset))
                        }
                        currentPage = mutableListOf()
                        pageTopOffset = child.topOffset
                    } else {
                        val subChildren = splitableChildren(child.view)
                        val childStrategy = child.view.pageSplitStrategy
                        if (subChildren != null && childStrategy != PageSplitStrategy.NONE) {
                            val subGap = childGap(child.view)
                            val subPages =
                                paginate(
                                    subChildren,
                                    x,
                                    startY,
                                    maxWidth,
                                    subGap,
                                    pageContentHeight,
                                    childStrategy,
                                )
                            pages.addAll(subPages)
                            i++
                            currentPage = mutableListOf()
                            pageTopOffset = if (i < measured.size) measured[i].topOffset else 0f
                        } else {
                            currentPage.addAll(child.elements)
                            i++
                        }
                    }
                }

                PageSplitStrategy.SPLIT_ANYWHERE -> {
                    val cutOffset = pageTopOffset + pageContentHeight
                    val cutY = startY - cutOffset
                    val (above, below) = splitElementsAt(child.elements, cutY)
                    currentPage.addAll(above)
                    pages.add(currentPage.toList().offset(dy = pageTopOffset))
                    currentPage = below.toMutableList()
                    pageTopOffset = cutOffset
                    i++
                }

                PageSplitStrategy.SPLIT_CENTER -> {
                    val childMidOffset = child.topOffset + child.height / 2f
                    val cutY = startY - childMidOffset
                    val (above, below) = splitElementsAt(child.elements, cutY)
                    currentPage.addAll(above)
                    pages.add(currentPage.toList().offset(dy = pageTopOffset))
                    currentPage = below.toMutableList()
                    pageTopOffset = childMidOffset
                    i++
                }
            }
        }

        if (currentPage.isNotEmpty()) {
            pages.add(currentPage.toList().offset(dy = pageTopOffset))
        }

        return pages
    }

    private fun splitableChildren(view: PdfView): List<PdfView>? =
        when (view) {
            is ColumnNode -> view.children
            is BulletListNode -> view.items.map { view.copy(items = listOf(it)) }
            else -> null
        }

    private fun childGap(view: PdfView): Float =
        when (view) {
            is ColumnNode -> view.gap
            else -> 0f
        }

    private fun splitElementsAt(
        elements: List<PdfElement>,
        cutY: Float,
    ): Pair<List<PdfElement>, List<PdfElement>> {
        val above = mutableListOf<PdfElement>()
        val below = mutableListOf<PdfElement>()
        for (el in elements) {
            if (elementTopY(el) > cutY + 0.1f) {
                above.add(el)
            } else {
                below.add(el)
            }
        }
        return above to below
    }

    private fun elementTopY(el: PdfElement): Float =
        when (el) {
            is PdfElement.Text -> el.y + el.fontSize
            is PdfElement.Rect -> el.y + el.height
            is PdfElement.Line -> maxOf(el.y1, el.y2)
            is PdfElement.Image -> el.y + el.height
            is PdfElement.Sector -> el.cy + el.radius
            is PdfElement.Polygon -> if (el.points.isEmpty()) 0f else el.points.maxOf { it.y }
            is PdfElement.Polyline -> if (el.points.isEmpty()) 0f else el.points.maxOf { it.y }
            is PdfElement.Svg -> el.y + el.height
            is PdfElement.ClipStart -> el.y + el.height
            is PdfElement.ClipEnd -> Float.MAX_VALUE
        }
}
