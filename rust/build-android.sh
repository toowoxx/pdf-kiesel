#!/usr/bin/env bash
# Cross-compile pdfgen for Android.
# Requires: rustup + cargo-ndk (provided via nix-shell or installed manually).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$MODULE_DIR/src/androidMain/jniLibs"

# Resolve the NDK: explicit ANDROID_NDK_HOME → the containing workspace's
# project-local SDK (../.android/sdk/ndk) → global ~/Android/Sdk/ndk.
# The local-SDK probe lets a sandbox without a global SDK build; on a
# standalone checkout the probe simply misses and the global fallback applies.
# Each candidate dir holds versioned subdirs; the newest wins.
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    NDK_DIR=""
    for candidate in "$MODULE_DIR/../.android/sdk/ndk" "$HOME/Android/Sdk/ndk"; do
        if [ -d "$candidate" ]; then
            NDK_DIR="$candidate"
            break
        fi
    done
    if [ -z "$NDK_DIR" ]; then
        echo "ERROR: Android NDK not found (set ANDROID_NDK_HOME, or provide" \
             "../.android/sdk/ndk or ~/Android/Sdk/ndk)"
        exit 1
    fi
    NDK_VERSION=$(ls "$NDK_DIR" | sort -V | tail -1)
    export ANDROID_NDK_HOME="$NDK_DIR/$NDK_VERSION"
fi
echo "Using NDK: $ANDROID_NDK_HOME"

# Toolchain + Android targets:
#   - Inside the nix shell (default path): rust-overlay provides rustc/cargo
#     with both Android targets bundled. Nothing to do here.
#   - Outside nix (fallback path): the developer is expected to have stock
#     rustup installed. Put rustup's cargo/rustc on PATH and ensure the
#     targets are available.
if command -v rustup >/dev/null 2>&1; then
    export PATH="$HOME/.cargo/bin:$PATH"
    rustup show active-toolchain >/dev/null 2>&1 || rustup default stable
    rustup target add aarch64-linux-android x86_64-linux-android 2>/dev/null || true
fi

# Install cargo-ndk if not available
command -v cargo-ndk >/dev/null 2>&1 || cargo install cargo-ndk

cd "$SCRIPT_DIR"
cargo ndk -t arm64-v8a -t x86_64 -P 33 -o "$OUTPUT_DIR" build --release

echo "Built .so files to $OUTPUT_DIR"
