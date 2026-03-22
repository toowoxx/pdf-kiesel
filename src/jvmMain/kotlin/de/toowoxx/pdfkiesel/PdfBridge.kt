package de.toowoxx.pdfkiesel

internal object PdfBridge {
    init {
        val osName = System.getProperty("os.name").lowercase()
        val archName = System.getProperty("os.arch").lowercase()
        val libName = when {
            osName.contains("linux") && archName.contains("amd64") -> "/native/linux-x86_64/libpdfgen.so"
            osName.contains("linux") && archName.contains("aarch64") -> "/native/linux-aarch64/libpdfgen.so"
            else -> throw UnsupportedOperationException("Unsupported platform: $osName/$archName")
        }

        val tempFile = java.io.File.createTempFile("pdfgen", ".so")
        tempFile.deleteOnExit()
        PdfBridge::class.java.getResourceAsStream(libName)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw RuntimeException("Native library not found: $libName")
        System.load(tempFile.absolutePath)
    }

    external fun generatePdfTree(json: String): ByteArray
}
