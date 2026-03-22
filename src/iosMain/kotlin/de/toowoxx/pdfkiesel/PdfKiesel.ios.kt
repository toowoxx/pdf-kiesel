package de.toowoxx.pdfkiesel

import de.toowoxx.pdfkiesel.model.TreeDocument
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import pdfgen.pdfgen_free
import pdfgen.pdfgen_free_error
import pdfgen.pdfgen_generate_tree

@OptIn(ExperimentalForeignApi::class)
private data class PdfGenOutput(
    val data: CPointer<UByteVar>?,
    val len: ULong,
    val errorPtr: CPointer<ByteVar>?,
)

@OptIn(ExperimentalForeignApi::class)
actual fun TreeDocument.renderToBytes(): ByteArray {
    val json = toJson()
    val result = pdfgen_generate_tree(json).useContents { PdfGenOutput(data, len, this.error) }
    return extractPdfBytes(result)
}

@OptIn(ExperimentalForeignApi::class)
private fun extractPdfBytes(result: PdfGenOutput): ByteArray {
    try {
        if (result.errorPtr != null) {
            throw RuntimeException("PDF generation failed: ${result.errorPtr.toKString()}")
        }

        val rawData = result.data ?: throw RuntimeException("PDF generation returned null data")
        return rawData.readBytes(result.len.toInt())
    } finally {
        if (result.data != null) {
            pdfgen_free(result.data, result.len)
        }
        if (result.errorPtr != null) {
            pdfgen_free_error(result.errorPtr)
        }
    }
}
