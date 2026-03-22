package de.toowoxx.pdfkiesel

import de.toowoxx.pdfkiesel.model.TreeDocument

/** Renders this [TreeDocument] to raw PDF bytes using the Krilla/Parley rendering engine. */
expect fun TreeDocument.renderToBytes(): ByteArray
