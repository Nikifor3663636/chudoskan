package ua.chudo.scanner.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import uniffi.chudo_core.Region
import ua.chudo.scanner.i18n.tr
import ua.chudo.scanner.i18n.title
import ua.chudo.scanner.i18n.LocalLang

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionPickerSheet(
    regions: List<Region>,
    selected: String,
    onPick: (Region) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val lang = LocalLang.current
    val shown = remember(filter, regions, lang) {
        regions.filter { it.name.contains(filter.trim(), ignoreCase = true) || it.title(lang).contains(filter.trim(), ignoreCase = true) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(tr("Місто для наявності", "Город для наличия"), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                placeholder = { Text(tr("Пошук міста", "Поиск города")) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
        }
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(shown, key = { it.code }) { r ->
                val isSelected = r.code == selected
                ListItem(
                    headlineContent = { Text(r.title()) },
                    trailingContent = {
                        if (isSelected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.padding(horizontal = 4.dp).clickable { onPick(r) },
                )
            }
        }
    }
}
