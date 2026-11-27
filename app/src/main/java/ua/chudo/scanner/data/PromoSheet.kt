package ua.chudo.scanner.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.concurrent.TimeUnit

class PromoSheet(client: OkHttpClient) {
    companion object {
        const val REFRESH_MS = 300_000L
        private const val URL = "https://docs.google.com/spreadsheets/d/1G2AuB_eOTTqbe8Jp0X-sZvVjeXwwVmtGzfU6gEACC3I/export?format=csv&gid=0"
    }

    private val http = client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    private var lastFetch = 0L
    private var cached: List<PromoCampaign>? = null

    suspend fun read(force: Boolean = false): List<PromoCampaign> = lock.withLock {
        val now = System.nanoTime()
        cached?.takeIf { !force && now - lastFetch < TimeUnit.SECONDS.toNanos(60) }?.let { return@withLock it }
        val fresh = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(URL).build()).execute().use { response ->
                val body = checkNotNull(response.body)
                check(response.isSuccessful && body.contentType()?.subtype == "csv")
                val source = body.source()
                val maxBytes = 2_000_000L
                check(!source.request(maxBytes + 1))
                parsePromoSheet(source.readUtf8())
            }
        }
        cached = fresh
        lastFetch = System.nanoTime()
        fresh
    }
}

data class PromoCampaign(
    val title: String,
    val brand: String,
    val supplier: String,
    val startsOn: String,
    val endsOn: String,
    val status: String,
    val details: List<Pair<String, String>>,
) {
    fun isActive(today: LocalDate = LocalDate.now(ZoneId.of("Europe/Kyiv"))): Boolean {
        if (status == "Діє") {
            val start = runCatching { LocalDate.parse(startsOn, promoDate) }.getOrNull()
            val end = runCatching { LocalDate.parse(endsOn, promoDate) }.getOrNull()
            return (start == null || start <= today) && (end == null || end >= today)
        }
        return false
    }
}

private val promoDate = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)

internal fun parsePromoSheet(csv: String): List<PromoCampaign> {
    val records = readPromoCsv(csv.removePrefix("\uFEFF"))
    val columns = records.firstOrNull().orEmpty().map(String::trim)
    val required = listOf("Тема акції", "Початок", "Кінець", "Статус")
    require(required.all { it in columns }) { "Не знайдено колонки акцій." }
    val extra = listOf("Період у механіці", "Днів до старту / кінця", "Пріоритет", "Умови",
        "Канали і магазини", "Інші особливості", "Артикулів", "Дата листа")
    return records.drop(1).mapNotNull { row ->
        fun cell(column: String) = row.getOrNull(columns.indexOf(column)).orEmpty().trim()
        val title = cell("Тема акції")
        if (title.isBlank()) return@mapNotNull null
        PromoCampaign(title, cell("ТМ"), cell("Постачальник"), cell("Початок"), cell("Кінець"),
            cell("Статус"), extra.mapNotNull { key -> cell(key).takeIf(String::isNotBlank)?.let { key to it } })
    }
}

private fun readPromoCsv(csv: String): List<List<String>> {
    val records = mutableListOf<List<String>>()
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    fun endField() { fields += field.toString(); field.clear() }
    fun endRecord() { endField(); records += fields.toList(); fields.clear() }
    val reader = StringReader(csv)
    while (true) {
        val next = reader.read()
        if (next == -1) break
        val char = next.toChar()
        when {
            char == '"' && quoted -> {
                reader.mark(1)
                if (reader.read() == '"'.code) field.append('"')
                else { reader.reset(); quoted = false }
            }
            char == '"' && field.isEmpty() -> quoted = true
            quoted -> field.append(char)
            char == ',' -> endField()
            char == '\n' -> endRecord()
            char == '\r' -> {
                reader.mark(1)
                if (reader.read() != '\n'.code) reader.reset()
                endRecord()
            }
            else -> field.append(char)
        }
    }
    require(!quoted) { "Незавершене поле CSV." }
    if (fields.isNotEmpty() || field.isNotEmpty()) endRecord()
    return records
}
