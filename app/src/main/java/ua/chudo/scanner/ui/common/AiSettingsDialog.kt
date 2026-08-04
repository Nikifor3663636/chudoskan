package ua.chudo.scanner.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ua.chudo.scanner.data.AppPrefs
import uniffi.chudo_core.defaultAiModel
import ua.chudo.scanner.i18n.tr

/** Ключ и модель OpenAI для распознавания фото. Ключ хранится только на телефоне. */
@Composable
fun AiSettingsDialog(prefs: AppPrefs, onDismiss: () -> Unit) {
    var key by rememberSaveable { mutableStateOf(prefs.aiKey.value.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(prefs.aiModel.value) }
    var show by rememberSaveable { mutableStateOf(false) }
    val keyOk = key.isBlank() || key.trim().startsWith("sk-")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Розпізнавання фото", "Распознавание фото")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    tr(
                    "Фото товару надсилається в ChatGPT, він повертає назву або артикул, і ми шукаємо їх на сайті. " +
                        "Ключ — з platform.openai.com, зберігається лише на цьому телефоні.",
                    "Фото товара отправляется в ChatGPT, он возвращает название или артикул, и мы ищем их на сайте. " +
                        "Ключ — с platform.openai.com, хранится только на этом телефоне.",
                ),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(tr("API ключ", "API ключ")) },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    isError = !keyOk,
                    supportingText = { if (!keyOk) Text(tr("Ключ має починатися з «sk-»", "Ключ должен начинаться с «sk-»")) },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, tr("Показати ключ", "Показать ключ"))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(tr("Модель", "Модель")) },
                    supportingText = { Text(tr("За замовчуванням ${defaultAiModel()}", "По умолчанию ${defaultAiModel()}")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = keyOk, onClick = { prefs.setAi(key, model); onDismiss() }) { Text(tr("Зберегти", "Сохранить")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Скасувати", "Отмена")) } },
    )
}
