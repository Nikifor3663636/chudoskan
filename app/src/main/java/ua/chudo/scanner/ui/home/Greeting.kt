package ua.chudo.scanner.ui.home

/** «Доброго ранку» / «Доброго дня» / «Доброго вечора» / «Доброї ночі» за годиною (0–23). */
fun greetingFor(hour: Int): String = when (hour) {
    in 5..10 -> "Доброго ранку"
    in 11..16 -> "Доброго дня"
    in 17..22 -> "Доброго вечора"
    else -> "Доброї ночі"
}

/**
 * Ім’я для звертання — перше слово з анкети в кличному відмінку за простими правилами
 * (Олена → Олено, Микита → Микито, Марія → Маріє, Таня → Таню, Андрій → Андрію).
 * Решту імен лишаємо як є — краще називний, ніж неправильна форма.
 */
fun vocativeName(fullName: String): String {
    val name = fullName.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
    if (name.length < 2) return name
    val lower = name.lowercase()
    return when {
        lower.endsWith("ія") -> name.dropLast(1) + "є"
        lower.endsWith("а") -> name.dropLast(1) + "о"
        lower.endsWith("я") -> name.dropLast(1) + "ю"
        lower.endsWith("й") -> name.dropLast(1) + "ю"
        else -> name
    }
}

/** «Доброго ранку, Олено» або без імені, якщо його ще немає. */
fun greeting(fullName: String, hour: Int): String {
    val name = vocativeName(fullName)
    return if (name.isBlank()) greetingFor(hour) else "${greetingFor(hour)}, $name"
}
