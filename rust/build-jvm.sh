#!/usr/bin/env bash
# Compile pdfgen for the host JVM (Linux x86_64).
# Requires PDFGEN_OUTPUT_DIR, the folder that receives libpdfgen.so. The Gradle task buildRustJvm sets it. For a
# manual run, export it first.
set -euo pipefail

: "${PDFGEN_OUTPUT_DIR:?set PDFGEN_OUTPUT_DIR to the folder that receives libpdfgen.so}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$PDFGEN_OUTPUT_DIR"

cd "$SCRIPT_DIR"
cargo build --release --features jvm

cp target/release/libpdfgen.so "$PDFGEN_OUTPUT_DIR/libpdfgen.so"

echo "Built libpdfgen.so to $PDFGEN_OUTPUT_DIR"
