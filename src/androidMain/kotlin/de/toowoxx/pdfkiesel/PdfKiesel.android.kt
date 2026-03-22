package de.toowoxx.pdfkiesel

import de.toowoxx.pdfkiesel.model.TreeDocument

actual fun TreeDocument.renderToBytes(): ByteArray {
    val json = toJson()
    return PdfBridge.generatePdfTree(json)
}
