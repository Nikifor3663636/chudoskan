# Перегенерация Kotlin-обёртки UniFFI из Rust-ядра.
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Push-Location "$root\core"
try {
    cargo build --lib
    $lib = if (Test-Path "target\debug\chudo_core.dll") { "target\debug\chudo_core.dll" }
           elseif (Test-Path "target\debug\libchudo_core.dylib") { "target\debug\libchudo_core.dylib" }
           else { "target\debug\libchudo_core.so" }
    cargo run --features bindgen --bin uniffi-bindgen -- generate --library $lib --language kotlin --out-dir "$root\app\src\main\java" --no-format
} finally {
    Pop-Location
}
