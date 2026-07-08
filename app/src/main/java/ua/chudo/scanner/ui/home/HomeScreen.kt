package ua.chudo.scanner.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.chudo.scanner.R
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.HistoryItem
import ua.chudo.scanner.ui.common.AiSettingsDialog
import ua.chudo.scanner.ui.common.ProductImage
import ua.chudo.scanner.ui.common.RegionPickerSheet
import ua.chudo.scanner.ui.common.SectionTitle
import uniffi.chudo_core.Lang

@Composable
fun HomeScreen(
    onScan: () -> Unit,
    onPhoto: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenProduct: (String) -> Unit,
) {
    val prefs = LocalContext.current.chudoApp.prefs
    val region by prefs.region.collectAsStateWithLifecycle()
    val lang by prefs.lang.collectAsStateWithLifecycle()
    val history by prefs.history.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showRegions by rememberSaveable { mutableStateOf(false) }
    var showAi by rememberSaveable { mutableStateOf(false) }
    val aiKey by prefs.aiKey.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit() {
        if (query.isNotBlank()) {
            keyboard?.hide()
            onSearch(query.trim())
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Image(
                        painterResource(R.drawable.logo_wordmark),
                        contentDescription = "Чудо Острів",
                        modifier = Modifier.height(44.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Наявність іграшок у магазинах",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(
                    onClick = { prefs.setLang(if (lang == Lang.UA) Lang.RU else Lang.UA) },
                    label = { Text(if (lang == Lang.UA) "UA" else "RU") },
                    leadingIcon = { Icon(Icons.Rounded.Translate, null, Modifier.size(18.dp)) },
                )
            }
        }

        item {
            AssistChip(
                onClick = { showRegions = true },
                label = { Text(region.name) },
                leadingIcon = { Icon(Icons.Rounded.LocationOn, null, Modifier.size(18.dp)) },
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScanHero(onScan)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        onClick = { if (aiKey == null) showAi = true else onPhoto() },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PhotoCamera, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Фото товару", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text(
                                    if (aiKey == null) "Потрібен ключ OpenAI" else "ChatGPT знайде за упаковкою",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                    Surface(
                        onClick = { showAi = true },
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Box(Modifier.padding(16.dp).size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Key, "Ключ OpenAI")
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Артикул, штрихкод або назва") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Очистити") }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            )
        }

        if (history.isEmpty()) {
            item { Tips() }
        } else {
            item {
                SectionTitle("Нещодавні") {
                    TextButton(onClick = prefs::clearHistory) { Text("Очистити") }
                }
            }
            items(history, key = { it.urlKey }) { HistoryRow(it) { onOpenProduct(it.urlKey) } }
        }
    }

    if (showAi) AiSettingsDialog(prefs) { showAi = false }

    if (showRegions) {
        RegionPickerSheet(
            regions = prefs.allRegions,
            selected = region.code,
            onPick = { prefs.setRegion(it.code); showRegions = false },
            onDismiss = { showRegions = false },
        )
    }
}

@Composable
private fun ScanHero(onScan: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onScan, shape = MaterialTheme.shapes.large, color = Color.Transparent) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Color(0xFFFF7500), Color(0xFFFF9A3D))))
                .padding(22.dp)
        ) {
            Column {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(32.dp), tint = Color.White)
                }
                Spacer(Modifier.height(18.dp))
                Text("Сканувати штрихкод", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Наведи камеру на упаковку — покажемо ціну і де товар є зараз",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            Box(
                Modifier.align(Alignment.TopEnd).size(40.dp).clip(CircleShape).background(cs.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = cs.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(item.image, Modifier.size(52.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.article?.let {
                    Text("Арт. $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Tips() {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Як це працює", style = MaterialTheme.typography.titleMedium)
            Tip("1", "Скануй штрихкод або введи артикул з цінника (64103, HKB86).")
            Tip("2", "Обери місто — побачиш, у яких магазинах товар є зараз.")
            Tip("3", "Якщо сайт не знає штрихкод, шукаємо його у відкритій базі та запам'ятовуємо твій вибір.")
        }
    }
}

@Composable
private fun Tip(n: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(n, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
