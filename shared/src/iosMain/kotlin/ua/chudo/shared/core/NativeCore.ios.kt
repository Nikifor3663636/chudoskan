package ua.chudo.shared.core

import chudo.core.chudo_call
import chudo.core.chudo_free
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

/** libchudo_core.a (Rust, staticlib) — C-функции из core/include/chudo_core.h через cinterop. */
@OptIn(ExperimentalForeignApi::class)
internal actual object NativeCore {
    actual fun call(method: String, argsJson: String): String {
        val p = chudo_call(method, argsJson) ?: return """{"err":"null from core"}"""
        return try { p.toKString() } finally { chudo_free(p) }
    }
}
