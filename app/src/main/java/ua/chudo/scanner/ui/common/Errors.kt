package ua.chudo.scanner.ui.common

import ua.chudo.scanner.data.BlockedException
import ua.chudo.scanner.data.HttpException
import ua.chudo.scanner.data.NoAiKeyException
import ua.chudo.scanner.i18n.tr
import uniffi.chudo_core.BlockKind
import uniffi.chudo_core.CoreException
import uniffi.chudo_core.Lang
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class UiError(val title: String, val hint: String, val openSite: Boolean = false, val needsKey: Boolean = false)

fun Throwable.toUiError(lang: Lang = Lang.UA): UiError = with(lang) {
    when (this@toUiError) {
        is UnknownHostException -> UiError(tr("Немає інтернету", "Нет интернета"), tr("Перевір підключення і спробуй ще раз.", "Проверь подключение и попробуй ещё раз."))
        is SocketTimeoutException -> UiError(tr("Сайт довго відповідає", "Сайт долго отвечает"), tr("Спробуй ще раз за хвилину.", "Попробуй ещё раз через минуту."))
        is BlockedException -> if (kind == BlockKind.RATE_LIMITED) {
            UiError(tr("Забагато запитів", "Слишком много запросов"), tr("Зачекай трохи. Відкрита база штрихкодів дає близько 100 запитів на добу.", "Подожди немного. Открытая база штрихкодов даёт около 100 запросов в сутки."))
        } else {
            UiError(tr("Сайт не пускає застосунок", "Сайт не пускает приложение"), tr("Спрацював захист сайту. Відкрий товар у браузері.", "Сработала защита сайта. Открой товар в браузере."), openSite = true)
        }
        is HttpException -> UiError(tr("Помилка сайту ($code)", "Ошибка сайта ($code)"), tr("Спробуй ще раз пізніше.", "Попробуй ещё раз позже."), openSite = true)
        is NoAiKeyException -> UiError(tr("Потрібен ключ OpenAI", "Нужен ключ OpenAI"), tr("Для розпізнавання фото введи ключ з platform.openai.com.", "Для распознавания фото введи ключ с platform.openai.com."), needsKey = true)
        is CoreException.Ai -> aiError(msg, lang)
        is CoreException.NotFound -> UiError(tr("Товар не знайдено", "Товар не найден"), tr("Можливо, його прибрали з сайту або на фото не товар.", "Возможно, его убрали с сайта или на фото не товар."))
        is CoreException -> UiError(tr("Сайт змінив формат даних", "Сайт изменил формат данных"), tr("Потрібно оновити парсер у застосунку. ($message)", "Нужно обновить парсер в приложении. ($message)"))
        else -> UiError(tr("Щось пішло не так", "Что-то пошло не так"), message ?: javaClass.simpleName)
    }
}

private fun aiError(msg: String, lang: Lang): UiError = with(lang) {
    val m = msg.lowercase()
    when {
        "api key" in m -> UiError(tr("Ключ OpenAI не підходить", "Ключ OpenAI не подходит"), tr("Перевір ключ у налаштуваннях. ($msg)", "Проверь ключ в настройках. ($msg)"), needsKey = true)
        "quota" in m || "billing" in m -> UiError(tr("Закінчився баланс OpenAI", "Закончился баланс OpenAI"), tr("Поповни рахунок на platform.openai.com. ($msg)", "Пополни счёт на platform.openai.com. ($msg)"))
        "model" in m -> UiError(tr("Модель недоступна", "Модель недоступна"), tr("Зміни модель у налаштуваннях фото. ($msg)", "Смени модель в настройках фото. ($msg)"), needsKey = true)
        else -> UiError(tr("ChatGPT не зміг визначити товар", "ChatGPT не смог определить товар"), tr("Спробуй чіткіше фото або введи назву вручну. ($msg)", "Попробуй более чёткое фото или введи название вручную. ($msg)"))
    }
}
