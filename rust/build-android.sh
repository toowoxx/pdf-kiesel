#!/usr/bin/env bash
# Cross-compile pdfgen for Android.
# Requires: rustup + cargo-ndk (provided via nix-shell or installed manually).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$MODULE_DIR/src/androidMain/jniLibs"

# Find the latest NDK
NDK_DIR="$HOME/Android/Sdk/ndk"
if [ ! -d "$NDK_DIR" ]; then
    echo "ERROR: Android NDK not found at $NDK_DIR"
    exit 1
fi
NDK_VERSION=$(ls "$NDK_DIR" | sort -V | tail -1)
export ANDROID_NDK_HOME="$NDK_DIR/$NDK_VERSION"
echo "Using NDK: $ANDROID_NDK_HOME"

# Ensure rustup's cargo/rustc take precedence over Nix's
export PATH="$HOME/.cargo/bin:$PATH"

# Install toolchain + targets if needed
rustup show active-toolchain >/dev/null 2>&1 || rustup default stable
rustup target add aarch64-linux-android x86_64-linux-android 2>/dev/null || true

# Install cargo-ndk if not available
command -v cargo-ndk >/dev/null 2>&1 || cargo install cargo-ndk

cd "$SCRIPT_DIR"
cargo ndk -t arm64-v8a -t x86_64 -P 33 -o "$OUTPUT_DIR" build --release

echo "Built .so files to $OUTPUT_DIR"
