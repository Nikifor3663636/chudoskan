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

/** Ключ и модель OpenAI для распознавания фото. Ключ хранится только на телефоне. */
@Composable
fun AiSettingsDialog(prefs: AppPrefs, onDismiss: () -> Unit) {
    var key by rememberSaveable { mutableStateOf(prefs.aiKey.value.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(prefs.aiModel.value) }
    var show by rememberSaveable { mutableStateOf(false) }
    val keyOk = key.isBlank() || key.trim().startsWith("sk-")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Розпізнавання фото") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Фото товару надсилається в ChatGPT, він повертає назву або артикул, і ми шукаємо їх на сайті. " +
                        "Ключ — з platform.openai.com, зберігається лише на цьому телефоні.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API ключ") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    isError = !keyOk,
                    supportingText = { if (!keyOk) Text("Ключ має починатися з «sk-»") },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Показати ключ")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Модель") },
                    supportingText = { Text("За замовчуванням ${defaultAiModel()}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = keyOk, onClick = { prefs.setAi(key, model); onDismiss() }) { Text("Зберегти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}
