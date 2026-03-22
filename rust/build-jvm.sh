#!/usr/bin/env bash
# Compile pdfgen for the host JVM (Linux x86_64).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$MODULE_DIR/src/jvmMain/resources/native/linux-x86_64"

mkdir -p "$OUTPUT_DIR"

cd "$SCRIPT_DIR"
cargo build --release --features jvm

cp target/release/libpdfgen.so "$OUTPUT_DIR/libpdfgen.so"

echo "Built libpdfgen.so to $OUTPUT_DIR"
