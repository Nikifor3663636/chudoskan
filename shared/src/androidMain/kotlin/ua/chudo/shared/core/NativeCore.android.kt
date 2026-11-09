package ua.chudo.shared.core

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/** libchudo_core.so (та же, что у UniFFI) — C-функции chudo_call/chudo_free через JNA. */
internal actual object NativeCore {
    private interface Lib : Library {
        fun chudo_call(method: String, args: String): Pointer?
        fun chudo_free(p: Pointer)
    }

    private val lib: Lib by lazy {
        Native.load("chudo_core", Lib::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    }

    actual fun call(method: String, argsJson: String): String {
        val p = lib.chudo_call(method, argsJson) ?: return """{"err":"null from core"}"""
        return try { p.getString(0, "UTF-8") } finally { lib.chudo_free(p) }
    }
}
