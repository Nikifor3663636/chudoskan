package ua.chudo.scanner.ui.print

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Раздел инструкции: заголовок, пошаговый список, примечание. */
private class GuideSection(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val steps: List<String>,
    val note: String? = null,
)

private val SECTIONS = listOf(
    GuideSection(
        Icons.Rounded.Route, "Який спосіб обрати", "Коротко: що підходить саме тобі",
        listOf(
            "Принтер уже підключений до Wi-Fi або кабелю магазину → «Мережа магазину». Найзручніше: друкують усі телефони в магазині.",
            "Принтер не в мережі, але має свій Wi-Fi (Wireless Direct / Wi-Fi Direct) → «Wi-Fi принтера». Роутер не потрібен.",
            "Мобільний або маленький принтер з Bluetooth → «Bluetooth».",
            "Звичайний офісний принтер A4 → додавати не обов'язково: цінники A4 друкуються через системний друк Android.",
        ),
    ),
    GuideSection(
        Icons.Rounded.Router, "Мережа магазину", "Принтер і телефон в одному Wi-Fi",
        listOf(
            "Увімкни принтер і переконайся, що він у мережі магазину: надрукуй сторінку налаштувань (у Godex — затисни FEED під час увімкнення, у Brother — зазвичай Меню → Друк звітів → Конфігурація мережі). Там має бути IP-адреса, не 0.0.0.0.",
            "Підключи телефон до того самого Wi-Fi магазину.",
            "Профіль → Принтери → «Знайти в мережі». Пошук триває до 10 секунд.",
            "У знайденому принтері перевір «Липкі» / «Звичайні» і мову, натисни «Додати».",
            "Натисни «Тест». Для липких вийде пробна етикетка з ціною 123,45.",
            "Не знайшовся? Натисни «За IP» і введи адресу зі сторінки налаштувань (порт 9100).",
        ),
        note = "Як підключити сам принтер до Wi-Fi магазину (один раз):\n" +
            "• Godex: комп'ютер + USB-кабель → програма GoLabel → Printer Setup → Wi-Fi Tool → ввести назву мережі (SSID) і пароль → Set Data. Принтер перезавантажиться і підключиться.\n" +
            "• Brother: зазвичай Меню → Мережа → WLAN → Майстер налаштування → обрати мережу → ввести пароль. Або кнопка WPS на роутері + WPS у меню принтера.\n" +
            "• Інші: меню Network / WLAN на принтері або програма виробника.\n" +
            "Порада: попроси адміністратора закріпити за принтером постійну IP-адресу в роутері — тоді він не «загубиться».",
    ),
    GuideSection(
        Icons.Rounded.Wifi, "Wi-Fi принтера (без роутера)", "Телефон підключається до мережі самого принтера",
        listOf(
            "На принтері увімкни власну мережу: у Brother — зазвичай Меню → Мережа → Wireless Direct (або Wi-Fi Direct) → Увімкнути. Точний шлях залежить від моделі.",
            "Дізнайся назву мережі (SSID) і пароль: вони є на сторінці конфігурації мережі (Меню → Друк звітів) або на екрані принтера. У Brother мережа називається «DIRECT-…».",
            "Профіль → Принтери → «Wi-Fi принтера» → введи назву мережі й пароль. IP можна не вводити — застосунок знайде принтер сам.",
            "Обери «Липкі» / «Звичайні», натисни «Тест». Android покаже запит «Підключитися до мережі…» — натисни «Підключити».",
            "Натисни «Зберегти». Під час кожного друку телефон на кілька секунд підключиться до принтера, інтернет при цьому працює через мобільні дані.",
        ),
        note = "Потрібен Android 10 або новіше. На старших телефонах підключись до Wi-Fi принтера в налаштуваннях телефона і додай принтер «За IP» (у Brother зазвичай 192.168.118.1).",
    ),
    GuideSection(
        Icons.Rounded.Bluetooth, "Bluetooth", "Мобільні й маленькі принтери етикеток",
        listOf(
            "Увімкни принтер і Bluetooth на телефоні.",
            "Налаштування телефона → Bluetooth → Додати пристрій → обери принтер. PIN зазвичай 0000 або 1234.",
            "Профіль → Принтери → «Bluetooth» → дозволь доступ до Bluetooth → обери принтер у списку.",
            "Обери мову принтера, натисни «Тест», потім «Зберегти».",
        ),
        note = "Якщо принтера немає в списку — натисни «Оновити список» або «Спарити новий». Принтер має бути спарений саме в налаштуваннях телефона.",
    ),
    GuideSection(
        Icons.Rounded.Translate, "Мова принтера і тест", "Якщо етикетка порожня або з дивними символами",
        listOf(
            "Застосунок сам вгадує мову за моделлю, але її можна змінити в картці принтера: «Мова: …».",
            "ZPL — Zebra, Godex, Honeywell, Citizen.",
            "TSPL — TSC, Xprinter, HPRT, Gprinter, більшість китайських принтерів етикеток.",
            "EPL — старі Zebra / Eltron.",
            "ESC/POS — чекові та прості мобільні принтери.",
            "Порядок перевірки, якщо не знаєш: ZPL → TSPL → EPL → ESC/POS. Після кожної зміни натискай «Тест».",
        ),
        note = "Етикетка друкується картинкою: кирилиця, штрихкод і ціни виглядають однаково на будь-якому принтері.",
    ),
    GuideSection(
        Icons.Rounded.Description, "Звичайні цінники A4", "Лист 3×8 етикеток 70×37 мм",
        listOf(
            "За замовчуванням A4 друкується через системний друк Android — підходить будь-який принтер, який бачить телефон.",
            "Якщо телефон не бачить принтер: встанови з Play Market «Brother Print Service Plugin» (для Brother) або «Mopria Print Service» (для більшості інших).",
            "«Пряма печать» (перемикач на екрані друку) надсилає PDF напряму на принтер — швидше, але працює не з усіма моделями. Для неї додай принтер у Принтери.",
        ),
    ),
    GuideSection(
        Icons.Rounded.Build, "Якщо не друкує", "Що перевірити по черзі",
        listOf(
            "Статус у картці червоний «Не в мережі» → принтер вимкнений, або телефон в іншому Wi-Fi, або в принтера змінився IP. Натисни ⟳ угорі — застосунок пошукає його знову.",
            "«Закінчились етикетки» / «Відкрита кришка» → встав рулон і закрий кришку, потім натисни FEED.",
            "Етикетка вийшла порожня або з символами замість ціни → змінити мову принтера (розділ «Мова принтера і тест»).",
            "Друк зміщений або на дві етикетки → на принтері зроби калібрування етикеток (у Godex зазвичай — затисни FEED до одного сигналу; в інших моделях — див. інструкцію до принтера, пункт «калібрування»).",
            "Розмір етикетки не той → на екрані друку обери правильний шаблон (58×40, 40×30, 30×20).",
            "Wi-Fi принтера: «Не вдалося підключитися» → перевір назву мережі й пароль (з урахуванням великих літер) і що принтер поруч.",
            "Bluetooth: «Не вдалося підключитися» → вимкни й увімкни принтер, видали його з Bluetooth телефона і спар заново.",
        ),
    ),
)

/** Карточка-вход в инструкцию на экране «Принтери». */
@Composable
fun GuideEntry(onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Інструкція з підключення", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Мережа, Wi-Fi принтера, Bluetooth, мова, що робити, якщо не друкує",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Полноэкранная пошаговая инструкция. */
@Composable
fun PrinterGuide(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var open by rememberSaveable { mutableStateOf(0) }
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Як підключити принтер", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Закрити") }
                    }
                    Text(
                        "Підходить будь-який принтер: етикеток (Godex, Zebra, TSC, Xprinter…) і звичайний A4 (Brother, HP, Canon…).",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SECTIONS.forEachIndexed { i, s ->
                    item(key = s.title) { Section(s, expanded = open == i) { open = if (open == i) -1 else i } }
                }
            }
        }
    }
}

@Composable
private fun Section(s: GuideSection, expanded: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(s.icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium)
                    Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    s.steps.forEachIndexed { n, step -> Step(n + 1, step) }
                    s.note?.let {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                            Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
