import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import ua.chudo.shared.ui.App
import kotlin.js.Promise

@JsFun("() => globalThis.chudoCoreReady")
private external fun coreReady(): Promise<JsAny?>

/** Веб-версия: ждём Rust-ядро (core.wasm), затем тот же интерфейс, что на iPhone. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    coreReady().then {
        document.getElementById("loading")?.remove()
        ComposeViewport(document.body!!) { App() }
        null
    }
}
