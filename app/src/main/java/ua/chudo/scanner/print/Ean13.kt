package ua.chudo.scanner.print

/**
 * EAN-13 без Android и без Rust-ядра: контрольная цифра, 95 модулей символа и геометрия штрихов
 * в целых точках принтера. Один и тот же расчёт рисует превью, печать и проверяется ZXing в тестах.
 */
object Ean13 {
    private val L = arrayOf("0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011")
    private val G = arrayOf("0100111", "0110011", "0011011", "0100001", "0011101", "0111001", "0000101", "0010001", "0001001", "0010111")
    private val PARITY = arrayOf("LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL")

    /** Модулей в символе (без тихих зон). */
    const val MODULES = 95

    /** Тихая зона по макету: 5 модулей с каждой стороны. */
    const val QUIET = 5

    /** Контрольная цифра для 12 цифр: веса 1, 3, 1, 3… слева направо. */
    fun checkDigit(first12: String): Int {
        require(first12.length == 12 && first12.all(Char::isDigit)) { "Потрібно 12 цифр" }
        val sum = first12.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return (10 - sum % 10) % 10
    }

    /** 12 цифр → дописываем контрольную; 13 — проверяем. Иначе `null`. */
    fun normalize(raw: String): String? {
        val d = raw.filter(Char::isDigit)
        return when (d.length) {
            12 -> d + checkDigit(d)
            13 -> d.takeIf { checkDigit(it.take(12)) == it[12] - '0' }
            else -> null
        }
    }

    /** 95 модулей: true — штрих. */
    fun modules(code13: String): BooleanArray {
        require(code13.length == 13 && code13.all(Char::isDigit)) { "Потрібно 13 цифр" }
        val d = code13.map { it - '0' }
        val p = PARITY[d[0]]
        val bits = StringBuilder("101")
        for (i in 1..6) bits.append(if (p[i - 1] == 'L') L[d[i]] else G[d[i]])
        bits.append("01010")
        for (i in 7..12) bits.append(L[d[i]].map { if (it == '0') '1' else '0' }.joinToString(""))
        bits.append("101")
        return BooleanArray(MODULES) { bits[it] == '1' }
    }

    /**
     * Штрихи как отрезки в точках вдоль символа: [start, end) от начала тихой зоны.
     * Соседние модули-штрихи сливаются в один отрезок — без щелей от округления.
     */
    fun bars(code13: String, moduleDots: Int): List<IntRange> {
        require(moduleDots >= 1)
        val m = modules(code13)
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < MODULES) {
            if (!m[i]) { i++; continue }
            var j = i
            while (j < MODULES && m[j]) j++
            out += ((QUIET + i) * moduleDots) until ((QUIET + j) * moduleDots)
            i = j
        }
        return out
    }

    /** Полная длина с тихими зонами, в точках. */
    fun lengthDots(moduleDots: Int) = (QUIET * 2 + MODULES) * moduleDots

    /** Самый широкий целый модуль, который влезает в [lengthDots]; не меньше 1. */
    fun moduleFor(lengthDots: Int) = maxOf(1, lengthDots / (QUIET * 2 + MODULES))
}
