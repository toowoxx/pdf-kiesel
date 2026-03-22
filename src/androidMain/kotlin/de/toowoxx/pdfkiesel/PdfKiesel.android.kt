package de.toowoxx.pdfkiesel

import de.toowoxx.pdfkiesel.model.PdfDocument
import de.toowoxx.pdfkiesel.model.TreeDocument

actual fun PdfDocument.renderToBytes(): ByteArray {
    val json = toJson()
    return PdfBridge.generatePdf(json)
}

actual fun TreeDocument.renderToBytes(): ByteArray {
    val json = toJson()
    return PdfBridge.generatePdfTree(json)
}
