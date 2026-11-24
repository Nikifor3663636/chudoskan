package ua.chudo.scanner.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import ua.chudo.scanner.ui.common.Banner
import ua.chudo.scanner.ui.common.BannerKind
import ua.chudo.scanner.ui.common.EmptyState
import ua.chudo.scanner.ui.common.Illustration
import ua.chudo.scanner.ui.common.MainTab
import ua.chudo.scanner.ui.common.MineBottomBar
import ua.chudo.scanner.ui.common.MineButton
import ua.chudo.scanner.ui.common.MineChip
import ua.chudo.scanner.ui.common.MineIllustration
import ua.chudo.scanner.ui.common.MinePrimaryButton
import ua.chudo.scanner.ui.common.MineTone
import ua.chudo.scanner.ui.common.Skeleton
import ua.chudo.scanner.ui.common.SkeletonKind
import ua.chudo.scanner.ui.common.SuccessState
import ua.chudo.scanner.ui.theme.ChudoTheme
import ua.chudo.scanner.ui.theme.Mine

/** Галерея компонентів дизайн-системи в обох темах (розділ «02 Компоненти»). */
class ComponentsSnapshots {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 7000), theme = "android:Theme.Material.NoActionBar")

    @Test fun componentsLight() = paparazzi.snapshot { Gallery(dark = false) }

    @Test fun componentsDark() = paparazzi.snapshot { Gallery(dark = true) }

    @Test fun bottomBarLight() = paparazzi.snapshot { Bar(dark = false) }

    @Test fun bottomBarDark() = paparazzi.snapshot { Bar(dark = true) }

    @Composable
    private fun Bar(dark: Boolean) = ChudoTheme(dark = dark) {
        Surface(Modifier.fillMaxSize(), color = Mine.colors.bg) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MineBottomBar(MainTab.STOCK, newOrders = 3, onTab = {}, onScan = {})
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Gallery(dark: Boolean) = ChudoTheme(dark = dark) {
        val m = Mine.colors
        Surface(Modifier.fillMaxSize(), color = m.bg, contentColor = m.text) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Кнопки", fontSize = 18.sp)
                MinePrimaryButton("Друкувати цінник", {})
                MinePrimaryButton("Завантаження", {}, busy = true)
                MinePrimaryButton("Неактивна", {}, enabled = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MineButton("Акцент", {}, Modifier.weight(1f), MineTone.ACCENT)
                    MineButton("Контур", {}, Modifier.weight(1f))
                    MineButton("Видалити", {}, Modifier.weight(1f), MineTone.DANGER)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MineButton("Текстова", {}, tone = MineTone.TEXT)
                    MineButton("Неактивна", {}, enabled = false)
                    MineButton("Підключити комп'ютер до друку", {}, Modifier.width(150.dp))
                }
                Text("Чипи", fontSize = 18.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MineChip("68×38", true, {}); MineChip("40×24", false, {}); MineChip("A4", false, {})
                }
                Text("Банери", fontSize = 18.sp)
                Banner(BannerKind.UPDATE, "Доступна версія 3.1", action = "Оновити" to {})
                Banner(BannerKind.OFFLINE, "Немає інтернету. Показуємо збережені залишки на 24.11")
                Banner(BannerKind.ERROR, "Не вдалося завантажити звіт", action = "Ще раз" to {})
                Banner(BannerKind.SUCCESS, "Звіт 1С завантажено")
                Text("Скелети", fontSize = 18.sp)
                Skeleton(SkeletonKind.CARD)
                Skeleton(SkeletonKind.ROW)
                Text("Стани", fontSize = 18.sp)
                EmptyState(Illustration.SEARCH, "Нічого не знайдено", "Перевір штрихкод або спробуй назву", action = "Сканувати ще" to {})
                SuccessState(Illustration.PRINT, "Надруковано", "2 цінники на Godex G500")
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Illustration.entries.forEach { MineIllustration(it, width = 84.dp) }
                }
            }
        }
    }
}
