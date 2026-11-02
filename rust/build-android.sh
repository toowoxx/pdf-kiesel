#!/usr/bin/env bash
# Cross-compile pdfgen for Android.
# Requires: rustup, the Android NDK (ANDROID_NDK_HOME) and PDFGEN_OUTPUT_DIR, the folder that receives the
# libraries. The Gradle task buildRust sets both. For a manual run, export them first.
# The Rust toolchain is the one in rust-toolchain.toml and cargo-ndk is the version below, so a build
# gives the same libraries on every machine.
set -euo pipefail

CARGO_NDK_VERSION="4.1.2"

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the Android NDK folder}"
: "${PDFGEN_OUTPUT_DIR:?set PDFGEN_OUTPUT_DIR to the folder that receives the libraries}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "Using NDK: $ANDROID_NDK_HOME"

# Without arguments, rustup installs the toolchain and the Android targets listed in rust-toolchain.toml.
export PATH="$HOME/.cargo/bin:$PATH"
rustup toolchain install

[ "$(cargo ndk --version 2>/dev/null)" = "cargo-ndk $CARGO_NDK_VERSION" ] ||
    cargo install cargo-ndk --locked --version "$CARGO_NDK_VERSION"

cargo ndk -t arm64-v8a -t x86_64 -P 33 -o "$PDFGEN_OUTPUT_DIR" build --release

echo "Built .so files to $PDFGEN_OUTPUT_DIR"
