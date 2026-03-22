package de.toowoxx.pdfkiesel

import de.toowoxx.pdfkiesel.model.PdfDocument
import de.toowoxx.pdfkiesel.model.TreeDocument

/** Renders this [PdfDocument] to raw PDF bytes using the platform-native renderer. */
expect fun PdfDocument.renderToBytes(): ByteArray

/** Renders this [TreeDocument] to raw PDF bytes using the parley-based layout engine. */
expect fun TreeDocument.renderToBytes(): ByteArray
