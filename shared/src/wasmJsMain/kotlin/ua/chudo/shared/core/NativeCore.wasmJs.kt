package ua.chudo.shared.core

/** Rust-ядро, собранное в WebAssembly (core.wasm); загрузчик — core-loader.js на странице. */
@JsFun("(m, a) => globalThis.chudoCore.call(m, a)")
private external fun coreCall(method: String, argsJson: String): String

internal actual object NativeCore {
    actual fun call(method: String, argsJson: String): String = coreCall(method, argsJson)
}
