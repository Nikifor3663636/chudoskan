package ua.chudo.scanner.data

/** Рядок «Журналу дій» у «Мій магазин»: що зроблено, хто → з ким. */
data class ShopLogLine(val title: String, val detail: String, val action: String)

/** Назви дій у журналі магазину (минулий час, без роду — «Підтверджено анкету»). */
private val SHOP_LOG_TITLES = mapOf(
    StaffAction.APPROVE.log to "Підтверджено анкету",
    StaffAction.REJECT.log to "Відхилено анкету",
    StaffAction.MAKE_ADMIN.log to "Призначено адміном",
    StaffAction.REMOVE_ADMIN.log to "Знято адміна",
    StaffAction.TRANSFER.log to "Переведено в інший магазин",
    StaffAction.DISABLE.log to "Вимкнено доступ",
    "passwordReset" to "Скинуто пароль",
    "pairPc" to "Підключено комп’ютер",
    "devicePair" to "Підключено комп’ютер",
    "unpairPc" to "Відключено комп’ютер",
    "linkStore" to "Прив’язано склад 1С",
    "actAs" to "Адмін мережі працював у магазині",
)

/**
 * Запис журналу → рядок для адміна магазину: «Підтверджено анкету» / «Олена Коваль → Денис Т.».
 * Імена беремо зі списку співробітників магазину; невідомий uid показуємо як «адмін мережі» / «—».
 */
fun shopLogLine(entry: AdminLogEntry, names: Map<String, String>): ShopLogLine {
    val title = SHOP_LOG_TITLES[entry.action] ?: entry.action
    val who = names[entry.by]?.takeIf(String::isNotBlank) ?: "Адмін мережі"
    val whom = entry.target.takeIf(String::isNotBlank)?.let { names[it]?.takeIf(String::isNotBlank) ?: it }
    val detail = listOfNotNull(who, whom).joinToString(" → ") + entry.details.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
    return ShopLogLine(title, detail, entry.action)
}
