#!/usr/bin/env bash
# Rust-ядро для iOS: статические библиотеки для iPhone и симулятора (Apple Silicon). Только на macOS.
set -euo pipefail
cd "$(dirname "$0")/../core"
export IPHONEOS_DEPLOYMENT_TARGET=15.0
for t in aarch64-apple-ios aarch64-apple-ios-sim; do
  rustup target add "$t"
  cargo build --release --lib --target "$t"
done
ls -la target/aarch64-apple-ios/release/libchudo_core.a target/aarch64-apple-ios-sim/release/libchudo_core.a
