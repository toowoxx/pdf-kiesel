#!/usr/bin/env bash
# Cross-compile pdfgen for iOS (device + simulator).
# Requires: rustup + Xcode. If rustup is not on PATH, re-execs via nix-shell.
#
# Only builds staticlib (not cdylib) — cdylib is for Android/JNI and its
# dynamic linking fails under Nix due to sysroot/SDK conflicts.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$MODULE_DIR/iosFrameworks/pdfgen-ios"

# Ensure rustup's cargo/rustc take precedence
export PATH="$HOME/.cargo/bin:$PATH"

# Toolchain + iOS targets:
#   - Inside the nix dev shell (default path): rust-overlay provides cargo/rustc
#     with iOS targets bundled — nothing to do.
#   - Outside nix (fallback path): the developer is expected to have stock
#     rustup installed; put rustup's cargo on PATH and ensure targets exist.
#   - If neither cargo nor rustup is present, re-exec through `nix develop`
#     against the flake (Xcode strips PATH, so we look up nix in well-known
#     locations).
if ! command -v cargo &>/dev/null; then
    if command -v rustup &>/dev/null; then
        export PATH="$HOME/.cargo/bin:$PATH"
    else
        for nixbin in /nix/var/nix/profiles/default/bin /run/current-system/sw/bin "$HOME/.nix-profile/bin"; do
            if [ -x "$nixbin/nix" ]; then
                echo "cargo not found, re-executing via $nixbin/nix develop..."
                exec "$nixbin/nix" develop --command bash "$0"
            fi
        done
        echo "ERROR: cargo not found and neither rustup nor nix is available." >&2
        echo "Either install rustup (https://rustup.rs) or run from inside the nix dev shell." >&2
        exit 1
    fi
fi

# If rustup is available (fallback path), make sure the targets are installed.
# Inside the nix shell rustup is absent and the targets are bundled by rust-overlay.
if command -v rustup >/dev/null 2>&1; then
    rustup show active-toolchain >/dev/null 2>&1 || rustup default stable
    rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios 2>/dev/null || true
fi

cd "$SCRIPT_DIR"

echo "Building pdfgen for iOS (aarch64-apple-ios)..."
cargo rustc --release --target aarch64-apple-ios --no-default-features --crate-type staticlib

echo "Building pdfgen for iOS Simulator (aarch64-apple-ios-sim)..."
cargo rustc --release --target aarch64-apple-ios-sim --no-default-features --crate-type staticlib

# Build for x86_64 simulator (Intel Macs)
echo "Building pdfgen for iOS Simulator (x86_64-apple-ios)..."
cargo rustc --release --target x86_64-apple-ios --no-default-features --crate-type staticlib

# Copy static libraries into target-specific directories (same filename for cinterop)
mkdir -p "$OUTPUT_DIR/device" "$OUTPUT_DIR/sim" "$OUTPUT_DIR/sim-x86_64"
cp "target/aarch64-apple-ios/release/libpdfgen.a" "$OUTPUT_DIR/device/libpdfgen.a"
cp "target/aarch64-apple-ios-sim/release/libpdfgen.a" "$OUTPUT_DIR/sim/libpdfgen.a"
cp "target/x86_64-apple-ios/release/libpdfgen.a" "$OUTPUT_DIR/sim-x86_64/libpdfgen.a"

echo "Built static libraries to $OUTPUT_DIR/"
echo "  Device:       device/libpdfgen.a"
echo "  Simulator:    sim/libpdfgen.a"
echo "  Simulator x86: sim-x86_64/libpdfgen.a"
