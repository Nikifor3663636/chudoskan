#!/usr/bin/env bash
# Перегенерация Kotlin-обёртки UniFFI из Rust-ядра.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/core"
cargo build --lib
lib=""
for f in target/debug/libchudo_core.so target/debug/libchudo_core.dylib; do
  [[ -f "$f" ]] && lib="$f" && break
done
[[ -n "$lib" ]] || { echo "libchudo_core не найдена" >&2; exit 1; }
cargo run --features bindgen --bin uniffi-bindgen -- generate \
  --library "$lib" --language kotlin --out-dir "$root/app/src/main/java" --no-format
