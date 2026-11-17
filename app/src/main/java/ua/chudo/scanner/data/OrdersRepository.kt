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

    fun load(shopId: String) {
        if (loadedFor == shopId) return
        loadedFor = shopId
        _orders.value = source.load(shopId)
    }

    /** Заказ, который сейчас собирают (один за раз). */
    fun current(): PickupOrder? = _orders.value.firstOrNull { it.stage == OrderStage.PICKING }

    /** «Почати збирати»: новый → в сборку. */
    fun start(number: String) = _orders.update { list ->
        list.map { if (it.number == number && it.stage == OrderStage.NEW) it.copy(stage = OrderStage.PICKING) else it }
    }

    /** Отсканировали товар: +1 к строке текущего заказа; всё собрано — заказ «Готові». */
    fun scan(code: String): ScanResult {
        val order = current() ?: return ScanResult.NothingPicking
        val digits = code.filter(Char::isDigit)
        val line = order.lines.firstOrNull { it.barcode == digits } ?: return ScanResult.NotInOrder
        if (line.done) return ScanResult.AlreadyEnough
        val lines = order.lines.map { if (it === line) it.copy(picked = it.picked + 1) else it }
        val done = lines.all { it.done }
        val updated = order.copy(lines = lines, stage = if (done) OrderStage.READY else OrderStage.PICKING)
        _orders.update { list -> list.map { if (it.number == order.number) updated else it } }
        return if (done) ScanResult.Completed(updated) else ScanResult.Added(line.name, updated)
    }

    /** Покупатель забрал заказ. */
    fun issue(number: String) = _orders.update { list -> list.filterNot { it.number == number } }
}
