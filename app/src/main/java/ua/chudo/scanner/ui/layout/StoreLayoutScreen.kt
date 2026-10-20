package ua.chudo.scanner.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ua.chudo.scanner.ui.common.MessageState

/** Схема магазина: где лежит товар (ряд, стеллаж, полка). Раздел в разработке. */
@Composable
fun StoreLayoutScreen() {
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
        MessageState(
            icon = Icons.Rounded.Map,
            title = "Планування магазину",
            text = "Розділ у розробці. Тут буде схема магазину: знайдеш товар — побачиш ряд, стелаж і полицю, де він лежить.",
        )
    }
}
