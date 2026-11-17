package ua.chudo.scanner.print

import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/** Откуда [PrinterResolver] берёт принтеры. В приложении — реестр, сеть, USB и ПК-агент; в тестах — подделки. */
interface PrinterSource {
    /** Принтеры магазина из общего реестра (снимок слушателя). */
    fun registry(): List<PrinterRecord>

    /** Отвечает ли принтер (для сети — TCP-соединение за 1 с). */
    suspend fun alive(r: PrinterRecord): Boolean

    /** Принтер вида, подключённый кабелем к этому телефону. */
    suspend fun usb(kind: LabelKind): PrinterRecord?

    /** Фоновый поиск в сети до 6 с; найденный уже записан в реестр (новый IP — тот же id). */
    suspend fun discover(kind: LabelKind): PrinterRecord?

    /** Принтер этого вида на компьютере магазина, если агент на связи (lastSeen ≤ 3 мин). */
    suspend fun agent(kind: LabelKind): PrinterRecord?
}

/** Как нашли принтер — для журнала и тестов. */
enum class Via { CACHE, USB, REGISTRY, DISCOVERY, AGENT }

data class Pick(val record: PrinterRecord, val via: Via)

/**
 * Автовыбор принтера по виду (этикетка 40×24 → `label_40x24`, ценники и A4 → `a4`), без участия сотрудника:
 * 1. принтер, выбранный в этой сессии, отвечает → он;
 * 2. принтер вида на USB этого телефона;
 * 3. из реестра магазина — тот, кто первым ответил на isAlive();
 * 4. никто не ответил → фоновый поиск до 6 с (новый IP пишется в реестр);
 * 5. в сети не нашли, но ПК-агент на связи → печать через компьютер;
 * 6. ничего → `null`: одно сообщение «Принтер … не знайдено» и «Спробувати ще».
 */
class PrinterResolver(private val src: PrinterSource) {
    /** Кеш на сессию: вид → id принтера. */
    private val cache = mutableMapOf<LabelKind, String>()

    suspend fun resolve(kind: LabelKind): Pick? {
        val network = src.registry().filter { it.kind == kind && it.transport == Link.TCP && it.ip.isNotEmpty() }
        val cached = cache[kind]?.let { id -> network.firstOrNull { it.id == id } }
        if (cached != null && src.alive(cached)) return remember(kind, Pick(cached, Via.CACHE))
        src.usb(kind)?.let { return remember(kind, Pick(it, Via.USB)) }
        firstAlive(network.filter { it.id != cached?.id })?.let { return remember(kind, Pick(it, Via.REGISTRY)) }
        src.discover(kind)?.let { return remember(kind, Pick(it, Via.DISCOVERY)) }
        src.agent(kind)?.let { return remember(kind, Pick(it, Via.AGENT)) }
        cache.remove(kind)
        return null
    }

    /** Печать на выбранный принтер не прошла — в следующий раз ищем заново. */
    fun forget(kind: LabelKind) {
        cache.remove(kind)
    }

    /** Текущий выбор сессии (бейдж «Основний для …» у администратора). */
    fun chosen(kind: LabelKind): String? = cache[kind]

    private fun remember(kind: LabelKind, p: Pick): Pick {
        cache[kind] = p.record.id
        return p
    }

    /** Все проверяются параллельно; берём первого ответившего. */
    private suspend fun firstAlive(list: List<PrinterRecord>): PrinterRecord? {
        if (list.isEmpty()) return null
        return channelFlow { list.forEach { r -> launch { if (src.alive(r)) send(r) } } }.firstOrNull()
    }
}
