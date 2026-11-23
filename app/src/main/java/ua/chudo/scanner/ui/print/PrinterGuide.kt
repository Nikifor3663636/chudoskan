package ua.chudo.scanner.ui.print

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.chudo.scanner.ui.theme.Mine

/** «Не друкує? Що перевірити за 1 хвилину» — для адміністратора. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterGuide(onClose: () -> Unit) {
    val m = Mine.colors
    ModalBottomSheet(onDismissRequest = onClose, containerColor = m.sheet, contentColor = m.text) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Не друкує? Що перевірити", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Співробітники нічого не налаштовують: застосунок сам бере принтер із реєстру магазину, а якщо той мовчить — шукає його в мережі.",
                fontSize = 14.sp, lineHeight = 20.sp, color = m.textSecondary,
            )
            Step(1, "Принтер увімкнений і без помилок", "Немає червоного індикатора, є папір / етикетки, кришка закрита.")
            Step(
                2, "Принтер у мережі магазину",
                "Надрукуй звіт конфігурації: у Brother — Меню → Друк звітів → Конфігурація мережі, у Godex — затисни FEED під час увімкнення. " +
                    "IP не має бути 0.0.0.0. Найкраще закріпити постійну IP-адресу в роутері.",
            )
            Step(3, "Телефон у Wi-Fi магазину", "Без Wi-Fi пошук у мережі не працює; Godex друкує через комп'ютер магазину з програмою «ЧудоСкан Друк».")
            Step(
                4, "«Знайти принтери»",
                "Шукає по підмережах магазину. Знайдений принтер сам потрапляє в реєстр — інші телефони одразу бачать новий IP.",
            )
            Step(
                5, "Не знайшовся або «Невідомий пристрій»",
                "«Інші способи → Додати вручну»: IP зі звіту конфігурації і тип (етикетки 40×24 або A4). Для невідомого пристрою достатньо обрати тип.",
            )
            Step(6, "Godex на комп'ютері (USB)", "Встанови на ПК програму «ЧудоСкан Друк» і підключи її кодом: «Профіль → Принтери → Підключити комп'ютер». Тоді Godex видно в розділі «Через комп'ютер».")
            Step(7, "Godex друкує блідо або повільно", "«Змінити» → щільність і швидкість. «Як у самотесті» — застосунок не змінює налаштувань принтера.")
        }
    }
}

@Composable
private fun Step(n: Int, title: String, text: String) {
    val m = Mine.colors
    Row {
        Text("$n", Modifier.width(22.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = m.accent)
        Spacer(Modifier.width(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = m.textSecondary)
        }
    }
}
