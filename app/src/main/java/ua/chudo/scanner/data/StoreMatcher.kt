package ua.chudo.scanner.data

import kotlin.math.max
import kotlin.math.min

/** Магазин із сайту (той самий список, з якого сотрудник обирає магазин при реєстрації). */
data class SiteShop(val shopId: String, val region: String, val regionName: String, val address: String)

/** Результат для складу 1С: магазин, якщо впевнені, і кандидати, найкращі першими. */
data class StoreMatch(val code: String, val shopId: String?, val score: Double, val candidates: List<SiteShop>)

/**
 * Автоматична прив'язка складів 1С («ЧО 22 Одеса (ТРЦ Gagarin Plaza)») до магазинів сайту
 * («вул. Генуезька, 5/2, ТРЦ "Gagarinn Plaza", друга черга»): головне — назва торгового центру
 * (з опечатками, латиницею чи кирилицею), місто — додатково (у регіон «Київ» входять і передмістя).
 * Впевнено — прив'язуємо самі; сумнівно — питаємо адміна, показуючи найкращих кандидатів першими.
 */
object StoreMatcher {
    const val AUTO = 0.72
    const val MARGIN = 0.12

    private val STOP by lazy { stopWords.map { norm(it) }.toSet() }

    private val stopWords = listOf(
        "тц", "трц", "тд", "трк", "тк", "ту", "тсц", "вул", "вулиця", "просп", "проспект", "пр", "пл", "площа", "бул", "бульвар",
        "поверх", "черга", "друга", "перша", "третя", "м", "с", "смт", "буд", "корпус", "секція", "мол", "mall", "center", "центр",
        "ул", "the", "плаза", "plaza", "city", "сіті", "сити",
    )

    fun match(stores: List<NetStore>, shops: List<SiteShop>, alreadyLinked: Set<String> = emptySet()): List<StoreMatch> {
        val free = shops.filter { it.shopId !in alreadyLinked }
        // Усі пари склад × магазин з оцінкою.
        val scored = stores.associate { st -> st.code to free.map { sh -> sh to score(st, sh) }.sortedByDescending { it.second } }
        // Жадібно: найвпевненіші пари першими, кожен магазин — одному складу.
        val taken = mutableSetOf<String>()
        val chosen = mutableMapOf<String, Pair<SiteShop, Double>>()
        scored.flatMap { (code, list) -> list.map { Triple(code, it.first, it.second) } }
            .sortedByDescending { it.third }
            .forEach { (code, shop, s) ->
                if (code in chosen || shop.shopId in taken || s < AUTO) return@forEach
                val rivals = scored.getValue(code).filter { it.first.shopId != shop.shopId && it.first.shopId !in taken }
                val second = rivals.firstOrNull()?.second ?: 0.0
                if (s - second < MARGIN) return@forEach
                chosen[code] = shop to s
                taken += shop.shopId
            }
        return stores.map { st ->
            val c = chosen[st.code]
            StoreMatch(
                st.code, c?.first?.shopId, c?.second ?: (scored.getValue(st.code).firstOrNull()?.second ?: 0.0),
                scored.getValue(st.code).map { it.first }.filter { it.shopId !in taken || it.shopId == c?.first?.shopId }.take(8),
            )
        }
    }

    /** 0..1: назва ТЦ (вага 0,8) і місто (0,2); без назви ТЦ у складі — лише місто. */
    fun score(store: NetStore, shop: SiteShop): Double {
        val mall = mallTokens(store)
        val addr = tokens(shop.address)
        val cityKey = norm(store.city)
        val city = if (cityKey.isNotEmpty() && (norm(shop.regionName) == cityKey || addr.any { sim(it, cityKey) >= 0.85 })) 1.0 else 0.0
        if (mall.isEmpty()) return city * 0.75
        val name = mall.map { t -> addr.maxOfOrNull { sim(t, it) } ?: 0.0 }.average()
        return name * 0.8 + city * 0.2
    }

    /** Слова з назви ТЦ: те, що в дужках, а якщо дужок немає — після міста. */
    fun mallTokens(store: NetStore): List<String> {
        val inParens = Regex("\\((.*)\\)").find(store.name)?.groupValues?.get(1)
        val rest = inParens ?: store.name.replace(Regex("^\\s*Ч[ОO]\\s*\\d+\\s*", RegexOption.IGNORE_CASE), "").let { r ->
            if (store.city.isNotBlank()) r.replaceFirst(store.city, "") else r
        }
        val cityTokens = tokens(store.city).toSet()
        return tokens(rest).filter { it !in cityTokens }
    }

    fun tokens(s: String): List<String> =
        norm(s).split(' ').filter { it.length >= 3 && it !in STOP && !it.all(Char::isDigit) }

    /** Нижній регістр, латиниця → кирилиця не потрібна: усе в латиницю транслітом, без лапок і розділових знаків. */
    fun norm(s: String): String = translit(s.lowercase().replace(Regex("['`’ʼ]"), ""))
        .replace(Regex("[\"'`’ʼ«»“”.,;:()\\-/]"), " ").replace(Regex("\\s+"), " ").trim()

    private val TR = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "h", 'ґ' to "g", 'д' to "d", 'е' to "e", 'є' to "ie", 'ж' to "zh", 'з' to "z",
        'и' to "y", 'і' to "i", 'ї' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
        'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh",
        'щ' to "shch", 'ь' to "", 'ю' to "iu", 'я' to "ia", 'ы' to "y", 'э' to "e", 'ё' to "e", 'ъ' to "",
    )

    private fun translit(s: String) = buildString { s.forEach { c -> append(TR[c] ?: c.toString()) } }
        // Латиниця з сайту й транслітерація з 1С: «gagarin» / «hahahrin», «retroville» / «retrovil».
        .replace("g", "h").replace("w", "v").replace("ll", "l").replace("x", "ks").replace("c", "k").replace("q", "k")

    /** Схожість слів 0..1 (Левенштейн), префікс від 5 літер — теж збіг («Фр.Бульвар» / «Французький бульвар»). */
    fun sim(a: String, b: String): Double {
        if (a == b) return 1.0
        if (min(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a))) return 0.9
        val d = lev(a, b)
        return 1.0 - d.toDouble() / max(a.length, b.length)
    }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
