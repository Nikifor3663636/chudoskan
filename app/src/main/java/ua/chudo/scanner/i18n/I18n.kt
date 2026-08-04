package ua.chudo.scanner.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import uniffi.chudo_core.Lang
import uniffi.chudo_core.Region

/**
 * Язык интерфейса = язык данных сайта (переключатель UA / RU на главном экране).
 * Строки живут рядом с кодом парами: `tr("українською", "по-русски")`.
 */
val LocalLang = compositionLocalOf { Lang.UA }

@Composable
@ReadOnlyComposable
fun tr(ua: String, ru: String): String = if (LocalLang.current == Lang.RU) ru else ua

/** Для кода вне Compose: ViewModel, репозиторий, тосты. */
fun Lang.tr(ua: String, ru: String): String = if (this == Lang.RU) ru else ua

@Composable
@ReadOnlyComposable
fun Region.title(): String = title(LocalLang.current)

fun Region.title(lang: Lang): String = if (lang == Lang.RU) RU_CITIES[code] ?: name else name

/** Ядро отдаёт города по-украински (как на сайте); русские названия — здесь. */
private val RU_CITIES = mapOf(
    "brovari" to "Бровары",
    "bucha" to "Буча",
    "vinnica" to "Винница",
    "dnipro" to "Днепр",
    "zhitomir" to "Житомир",
    "ivano-frankivsk" to "Ивано-Франковск",
    "izmayil" to "Измаил",
    "kam-janec-podilskij" to "Каменец-Подольский",
    "kam-janske" to "Каменское",
    "kyiv" to "Киев",
    "kovel" to "Ковель",
    "kolomija" to "Коломыя",
    "kropivnickij" to "Кропивницкий",
    "luck" to "Луцк",
    "lviv" to "Львов",
    "odesa" to "Одесса",
    "oleksandriya" to "Александрия",
    "poltava" to "Полтава",
    "rivne" to "Ровно",
    "stryi" to "Стрый",
    "ternopil" to "Тернополь",
    "uzhgorod" to "Ужгород",
    "uman" to "Умань",
    "harkiv" to "Харьков",
    "hmelniickij" to "Хмельницкий",
    "cherkasi" to "Черкассы",
    "chernivci" to "Черновцы",
    "chernigiv" to "Чернигов",
    "chervonohrad" to "Шептицкий",
)
