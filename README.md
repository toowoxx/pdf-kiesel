# pdf-kiesel

A Kotlin Multiplatform library for generating PDF documents using a type-safe Kotlin DSL. The rendering is powered by [Krilla](https://github.com/LaurenzV/krilla) (Rust) with text layout via [Parley](https://github.com/linebender/parley).

**Platforms:** Android (JNI) · iOS (C FFI)

## Usage

Add pdf-kiesel as a git submodule:

```bash
git submodule add git@github.com:toowoxx/pdf-kiesel.git pdf-kiesel
```

Include it in your `settings.gradle.kts`:

```kotlin
include(":pdf-kiesel")
```

Add the dependency in your module's `build.gradle.kts`:

```kotlin
commonMain.dependencies {
    implementation(projects.pdfKiesel)
}
```

## Quick Start

```kotlin
import de.toowoxx.pdfkiesel.dsl.*
import de.toowoxx.pdfkiesel.model.PdfColor
import de.toowoxx.pdfkiesel.renderToBytes

val doc = pdfDocument {
    pagedContent {
        text("Hello, PDF!") {
            fontSize = 24f
            bold = true
        }

        spacer(12f)

        paragraph("This document was generated with pdf-kiesel.") {
            fontSize = 12f
            color = PdfColor(0.3f, 0.3f, 0.3f)
        }

        spacer(8f)

        divider()

        spacer(8f)

        bulletList(listOf("Fast rendering", "Type-safe DSL", "Cross-platform")) {
            fontSize = 11f
        }
    }
}

val pdfBytes: ByteArray = doc.renderToBytes()
```

## DSL Reference

### Document Builder

`pdfDocument { }` is the entry point. It supports `page()` for single pages and `pagedContent()` for automatic page splitting.

### Page Content

**Text elements:**

```kotlin
text("Single line") { fontSize = 14f; bold = true }
paragraph("Long text that wraps automatically...") { fontSize = 11f }
richParagraph("Text with **bold** and *italic* markdown") { fontSize = 11f }
```

**Structural elements:**

```kotlin
spacer(height = 12f)
divider(color = PdfColor.GRAY, strokeWidth = 0.5f)
rect(height = 40f) { fillColor = PdfColor(0.95f, 0.95f, 0.95f); cornerRadius = 4f }
```

**Lists:**

```kotlin
bulletList(listOf("Item 1", "Item 2", "Item 3")) { fontSize = 11f }
richBulletList(listOf("**Bold** item", "Normal item")) { fontSize = 11f }
```

**Images and SVG:**

```kotlin
image(pngBytes, width = 200f, height = 150f, align = TextAlign.CENTER)
svg(svgString, width = 100f, height = 100f)
```

### Layout Containers

```kotlin
// Vertical layout
column(gap = 8f) {
    text("First") { fontSize = 12f }
    text("Second") { fontSize = 12f }
}

// Horizontal layout with weighted cells
row(gap = 12f) {
    cell(weight = 1f) { text("Left") {} }
    cell(weight = 2f) { text("Right (wider)") {} }
}

// Fixed + weighted columns
row {
    cell(width = 100f) { text("Fixed 100pt") {} }
    cell(weight = 1f) { text("Fills remaining") {} }
}

// Accent bar (colored sidebar)
accentBar(color = PdfColor(0.2f, 0.5f, 0.8f), barWidth = 3f) {
    text("Highlighted content") { fontSize = 11f }
}

// Box with background
box(background = PdfColor(0.95f, 0.95f, 0.95f), padding = 12f, cornerRadius = 4f) {
    text("Boxed content") { fontSize = 11f }
}

// Padding with fine control
padded(Padding(top = 8f, right = 16f, bottom = 8f, left = 16f)) {
    text("Padded content") { fontSize = 11f }
}

// Grid layout
grid(columns = listOf(GridColumnDef.Weight(1f), GridColumnDef.Weight(1f))) {
    row { cell { text("A") {} }; cell { text("B") {} } }
    row { cell { text("C") {} }; cell { text("D") {} } }
}

// Stack (overlapping children)
stack(verticalAlignment = VerticalAlignment.CenterVertically) {
    rect(height = 50f) { fillColor = PdfColor.LIGHT_GRAY }
    text("Centered on rect") { align = TextAlign.CENTER }
}
```

### Tables

```kotlin
table {
    widths(150f, 200f, 100f)
    headerRow {
        cell("Name") { bold = true }
        cell("Description") { bold = true }
        cell("Price") { bold = true; align = TextAlign.RIGHT }
    }
    row {
        cell("Widget")
        cell("A useful widget")
        cell("€9.99") { align = TextAlign.RIGHT }
    }
}
```

### Canvas (Custom Drawing)

```kotlin
canvas(height = 200f) {
    circle(cx, cy, radius = 40f, fill = PdfColor(0.2f, 0.6f, 0.9f))
    sector(cx, cy, radius = 60f, startAngle = 0f, sweepAngle = 90f, fill = PdfColor(0.9f, 0.3f, 0.3f))
    line(left, cy, right, cy) { color = PdfColor.GRAY; strokeWidth = 1f }
    rect(x = 10f, y = 10f, width = 50f, height = 30f) { fillColor = PdfColor.LIGHT_GRAY }
    polygon(listOf(50f to 0f, 100f to 50f, 0f to 50f), fill = PdfColor(0.3f, 0.8f, 0.3f))
    text("Label", x = cx, y = cy) { fontSize = 10f }
}
```

### Custom Fonts

```kotlin
pdfDocument {
    registerFont("MyFont", fontBytes)  // TTF or OTF ByteArray

    pagedContent {
        text("Custom font text") { font = "MyFont"; fontSize = 14f }
    }
}
```

### Page Configuration

```kotlin
pdfDocument {
    pagedContent(
        width = PdfPage.A4_WIDTH,
        height = PdfPage.A4_HEIGHT,
        margin = Margin(top = 60f, bottom = 60f, left = 50f, right = 50f),
        background = PdfColor(0.98f, 0.98f, 0.98f),
        splitStrategy = PageSplitStrategy.SPLIT_NEAREST_VIEW,
    ) {
        // content...
    }
}
```

## Architecture

```
Kotlin DSL (commonMain)
    │
    ├─ pdfDocument { }       → PdfDocument (JSON) → Rust render()
    └─ pdfDocument { }   → TreeDocument (JSON) → Rust render_tree()
                                                          │
                                              ┌───────────┴───────────┐
                                              │   Parley (text layout) │
                                              │   Krilla (PDF output)  │
                                              └────────────────────────┘
```

The Kotlin DSL builds a document model that is serialized to JSON and passed to the Rust backend. The Rust side handles text layout (Parley + skrifa) and PDF rendering (Krilla).

## Building the Rust Backend

The Rust native library must be built before the Kotlin code can run:

```bash
# Android (requires cargo-ndk and Android NDK)
cd rust && bash build-android.sh

# iOS (requires macOS with Xcode)
cd rust && bash build-ios.sh
```

Or via Gradle tasks:

```bash
./gradlew :pdf-kiesel:buildRust       # Android
./gradlew :pdf-kiesel:buildRustIos    # iOS (macOS only)
```

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
