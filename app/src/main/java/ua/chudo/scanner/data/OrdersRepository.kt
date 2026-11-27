package ua.chudo.scanner.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Этап заказа на самовывоз в магазине. */
enum class OrderStage(val title: String) { NEW("Нові"), PICKING("Збираю"), READY("Готові") }

/** Строка заказа: сколько нужно, сколько уже собрали сканером, сколько есть в магазине (`null` — не знаем). */
data class OrderLine(val barcode: String, val name: String, val qty: Int, val picked: Int = 0, val inStock: Int? = null) {
    val done: Boolean get() = picked >= qty
}

/** Интернет-заказ на самовывоз. */
data class PickupOrder(
    val number: String,
    val customer: String,
    /** Время оформления «18:05». */
    val createdAt: String,
    val total: Double,
    val paid: Boolean,
    val lines: List<OrderLine>,
    val stage: OrderStage = OrderStage.NEW,
) {
    val items: Int get() = lines.sumOf { it.qty }
    val picked: Int get() = lines.sumOf { minOf(it.picked, it.qty) }

    /** Позиций, которых нет в магазине. */
    val missing: Int get() = lines.count { it.inStock == 0 }
}

/** Откуда приходят заказы. Сейчас — только демо; настоящий источник (сайт / 1С) подключается сюда. */
interface OrdersSource {
    /** Демо-данные: экран честно об этом пишет. */
    val demo: Boolean
    fun load(shopId: String): List<PickupOrder>
}

/** Примеры заказов для показа экрана, пока источник заказов не подключён. */
object DemoOrders : OrdersSource {
    override val demo = true
    override fun load(shopId: String) = listOf(
        PickupOrder(
            "100045871", "Олена К.", "18:05", 1298.0, paid = true,
            lines = listOf(
                OrderLine("5702016911114", "LEGO Duplo Потяг із цифрами", 1, inStock = 4),
                OrderLine("2000999159305", "Crayola, набір для творчості «Gabby's Dollhouse»", 2, inStock = 6),
                OrderLine("6903317562084", "Crackin Eggs, м'яка іграшка у яйці", 2, inStock = 12),
            ),
        ),
        PickupOrder(
            "100045902", "Андрій П.", "18:31", 629.0, paid = false,
            lines = listOf(OrderLine("5702016911114", "LEGO Duplo Потяг із цифрами", 1, inStock = 4)),
        ),
        PickupOrder(
            "100045917", "Ірина С.", "18:40", 2147.0, paid = true,
            lines = listOf(
                OrderLine("2000999159305", "Crayola, набір для творчості", 1, inStock = 6),
                OrderLine("4823077601231", "Конструктор магнітний, 64 деталі", 1, inStock = 0),
            ),
        ),
        PickupOrder(
            "100045790", "Марія Д.", "17:52", 3460.0, paid = true, stage = OrderStage.PICKING,
            lines = listOf(
                OrderLine("5702016911114", "LEGO Duplo Потяг із цифрами", 2, picked = 2, inStock = 4),
                OrderLine("6903317562084", "Crackin Eggs, м'яка іграшка у яйці", 3, picked = 1, inStock = 12),
            ),
        ),
        PickupOrder("100045755", "Олег В.", "17:20", 899.0, paid = true, stage = OrderStage.READY,
            lines = listOf(OrderLine("2000999159305", "Crayola, набір для творчості", 1, picked = 1, inStock = 6))),
        PickupOrder("100045731", "Світлана Г.", "16:58", 459.0, paid = false, stage = OrderStage.READY,
            lines = listOf(OrderLine("6903317562084", "Crackin Eggs, м'яка іграшка у яйці", 1, picked = 1, inStock = 12))),
    )
}

/** Результат сканирования при сборке — для snackbar. */
sealed interface ScanResult {
    data class Added(val name: String, val order: PickupOrder) : ScanResult
    data class Completed(val order: PickupOrder) : ScanResult
    data object NotInOrder : ScanResult
    data object AlreadyEnough : ScanResult
    data object NothingPicking : ScanResult
}

/**
 * Заказы магазина и сборка сканером. Состояние живёт всю сессию приложения
 * (переход между вкладками его не сбрасывает).
 */
class OrdersRepository(private val source: OrdersSource = DemoOrders) {
    val demo: Boolean get() = source.demo
    private val _orders = MutableStateFlow<List<PickupOrder>>(emptyList())
    val orders: StateFlow<List<PickupOrder>> = _orders.asStateFlow()
    private var loadedFor: String? = null
    private val _activeNumber = MutableStateFlow<String?>(null)
    val activeNumber = _activeNumber.asStateFlow()

    @Synchronized
    fun load(shopId: String) {
        if (loadedFor == shopId) return
        val incoming = source.load(shopId)
        loadedFor = shopId
        _orders.value = incoming
        _activeNumber.value = _orders.value.filter { it.stage == OrderStage.PICKING }.singleOrNull()?.number
    }

    /** Заказ, который сейчас собирают (один за раз). */
    fun current(): PickupOrder? = _orders.value.firstOrNull { it.number == _activeNumber.value && it.stage == OrderStage.PICKING }

    /** «Почати збирати»: новый → в сборку. */
    @Synchronized
    fun start(number: String) {
        val order = _orders.value.firstOrNull { it.number == number } ?: return
        if (order.stage == OrderStage.READY) return
        if (order.stage == OrderStage.NEW) {
            _orders.update { list -> list.map { if (it.number == number) it.copy(stage = OrderStage.PICKING) else it } }
        }
        _activeNumber.value = number
    }

    /** Отсканировали товар: +1 к строке текущего заказа; всё собрано — заказ «Готові». */
    @Synchronized
    fun scan(code: String): ScanResult {
        val order = current() ?: return ScanResult.NothingPicking
        val barcode = code.trim()
        val index = order.lines.indexOfFirst { it.barcode == barcode && !it.done }
        if (index == -1) return if (order.lines.any { it.barcode == barcode }) ScanResult.AlreadyEnough else ScanResult.NotInOrder
        val line = order.lines[index]
        val lines = order.lines.mapIndexed { at, item -> if (at == index) item.copy(picked = item.picked + 1) else item }
        val done = lines.all { it.done }
        val updated = order.copy(lines = lines, stage = if (done) OrderStage.READY else OrderStage.PICKING)
        _orders.update { list -> list.map { if (it.number == order.number) updated else it } }
        if (done) _activeNumber.value = null
        return if (done) ScanResult.Completed(updated) else ScanResult.Added(line.name, updated)
    }

    /**
     * Позначити рядок вручну (товар без штрихкоду або не читається): зібрано повністю,
     * повторне натискання знімає позначку. Усе зібрано — замовлення «Готові».
     */
    @Synchronized
    fun togglePicked(number: String, index: Int): PickupOrder? {
        val order = _orders.value.firstOrNull { it.number == number && it.stage == OrderStage.PICKING } ?: return null
        val line = order.lines.getOrNull(index) ?: return null
        val lines = order.lines.mapIndexed { at, item -> if (at == index) item.copy(picked = if (line.done) 0 else line.qty) else item }
        val done = lines.all { it.done }
        val updated = order.copy(lines = lines, stage = if (done) OrderStage.READY else OrderStage.PICKING)
        _orders.update { list -> list.map { if (it.number == number) updated else it } }
        if (done && _activeNumber.value == number) _activeNumber.value = null
        return updated
    }

    /** Покупатель забрал заказ. */
    @Synchronized
    fun issue(number: String, paymentReceived: Boolean = false): Boolean {
        val order = _orders.value.firstOrNull { it.number == number } ?: return false
        if (order.stage != OrderStage.READY || (!order.paid && !paymentReceived)) return false
        _orders.update { list -> list.filterNot { it.number == number } }
        return true
    }
}
