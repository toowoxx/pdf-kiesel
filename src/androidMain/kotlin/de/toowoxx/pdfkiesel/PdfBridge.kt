package de.toowoxx.pdfkiesel

internal object PdfBridge {
    init {
        System.loadLibrary("pdfgen")
    }

    external fun generatePdfTree(json: String): ByteArray
}
