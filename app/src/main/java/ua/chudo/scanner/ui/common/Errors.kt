package ua.chudo.scanner.ui.common

import ua.chudo.scanner.data.BlockedException
import ua.chudo.scanner.data.HttpException
import ua.chudo.scanner.data.NoAiKeyException
import uniffi.chudo_core.BlockKind
import uniffi.chudo_core.CoreException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class UiError(val title: String, val hint: String, val openSite: Boolean = false, val needsKey: Boolean = false)

fun Throwable.toUiError(): UiError = when (this) {
    is UnknownHostException -> UiError("Немає інтернету", "Перевір підключення і спробуй ще раз.")
    is SocketTimeoutException -> UiError("Сайт довго відповідає", "Спробуй ще раз за хвилину.")
    is BlockedException -> if (kind == BlockKind.RATE_LIMITED) {
        UiError("Забагато запитів", "Зачекай трохи. Відкрита база штрихкодів дає близько 100 запитів на добу.")
    } else {
        UiError("Сайт не пускає застосунок", "Спрацював захист сайту. Відкрий товар у браузері.", openSite = true)
    }
    is HttpException -> UiError("Помилка сайту ($code)", "Спробуй ще раз пізніше.", openSite = true)
    is NoAiKeyException -> UiError("Потрібен ключ OpenAI", "Для розпізнавання фото введи ключ з platform.openai.com.", needsKey = true)
    is CoreException.Ai -> aiError(msg)
    is CoreException.NotFound -> UiError("Товар не знайдено", "Можливо, його прибрали з сайту або на фото не товар.")
    is CoreException -> UiError("Сайт змінив формат даних", "Потрібно оновити парсер у застосунку. ($message)")
    else -> UiError("Щось пішло не так", message ?: javaClass.simpleName)
}

private fun aiError(msg: String): UiError {
    val m = msg.lowercase()
    return when {
        "api key" in m -> UiError("Ключ OpenAI не підходить", "Перевір ключ у налаштуваннях. ($msg)", needsKey = true)
        "quota" in m || "billing" in m -> UiError("Закінчився баланс OpenAI", "Поповни рахунок на platform.openai.com. ($msg)")
        "model" in m -> UiError("Модель недоступна", "Зміни модель у налаштуваннях фото. ($msg)", needsKey = true)
        else -> UiError("ChatGPT не зміг визначити товар", "Спробуй чіткіше фото або введи назву вручну. ($msg)")
    }
}
