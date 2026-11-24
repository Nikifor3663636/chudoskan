package ua.chudo.scanner.terminal

import java.io.File

/**
 * Незбережений кошик «Термінал» — у файлі: оновлення застосунку чи перезапуск його не загублять.
 * Формат — рядки з табуляцією (без бібліотек, працює в JVM-тестах).
 */
class CartStore(private val file: File) {
    fun save(cart: Cart, saleId: String?) {
        if (cart.items.isEmpty()) { file.delete(); return }
        val d = when (val x = cart.discount) {
            Discount.None -> "N\t0"
            is Discount.Percent -> "P\t${x.percent}"
            is Discount.Amount -> "A\t${x.kop}"
        }
        val text = buildString {
            append("v1\t").append(saleId.orEmpty()).append('\n')
            append(d).append('\n')
            cart.items.forEach { i ->
                listOf(i.key, i.barcode, i.name, i.code, i.article, i.qty.toString(), i.priceKop.toString(), if (i.manualPrice) "1" else "0")
                    .joinTo(this, "\t") { clean(it) }
                append('\n')
            }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** Кошик і id продажу, що вже йде в оплату; зіпсований файл — порожній кошик. */
    fun load(): Pair<Cart, String?> = runCatching {
        val lines = file.readLines().filter { it.isNotEmpty() }
        val head = lines[0].split('\t')
        require(head[0] == "v1")
        val (kind, value) = lines[1].split('\t')
        val discount = when (kind) {
            "P" -> Discount.Percent(value.toInt())
            "A" -> Discount.Amount(value.toLong())
            else -> Discount.None
        }
        val items = lines.drop(2).map { l ->
            val f = l.split('\t')
            CartItem(f[0], f[1], f[2], f[3], f[4], f[5].toInt(), f[6].toLong(), f[7] == "1")
        }
        Cart(items, discount) to head.getOrNull(1)?.ifEmpty { null }
    }.getOrDefault(Cart() to null)

    fun clear() { file.delete() }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
