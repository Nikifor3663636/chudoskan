package ua.chudo.scanner.ui.common

import ua.chudo.scanner.data.BlockedException
import ua.chudo.scanner.data.HttpException
import uniffi.chudo_core.BlockKind
import uniffi.chudo_core.CoreException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class UiError(val title: String, val hint: String, val openSite: Boolean = false)

fun Throwable.toUiError(): UiError = run {
    when (this@toUiError) {
        is UnknownHostException -> UiError("Немає інтернету", "Перевір підключення і спробуй ще раз.")
        is SocketTimeoutException -> UiError("Сайт довго відповідає", "Спробуй ще раз за хвилину.")
        is BlockedException -> if (kind == BlockKind.RATE_LIMITED) {
            UiError("Забагато запитів", "Зачекай трохи. Відкрита база штрихкодів дає близько 100 запитів на добу.")
        } else {
            UiError("Сайт не пускає застосунок", "Спрацював захист сайту. Відкрий товар у браузері.", openSite = true)
        }
        is HttpException -> UiError("Помилка сайту ($code)", "Спробуй ще раз пізніше.", openSite = true)
        is CoreException.NotFound -> UiError("Товар не знайдено", "Можливо, його прибрали з сайту.")
        is CoreException -> UiError("Сайт змінив формат даних", "Потрібно оновити парсер у застосунку. ($message)")
        else -> UiError("Щось пішло не так", message ?: javaClass.simpleName)
    }
}

