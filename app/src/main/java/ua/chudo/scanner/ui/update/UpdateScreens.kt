package ua.chudo.scanner.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import ua.chudo.scanner.ChudoApp
import ua.chudo.scanner.chudoApp
import ua.chudo.scanner.data.Employee
import ua.chudo.scanner.ui.common.MineButton
import ua.chudo.scanner.ui.common.MineCard
import ua.chudo.scanner.ui.common.MineHeader
import ua.chudo.scanner.ui.common.MinePrimaryButton
import ua.chudo.scanner.ui.theme.Mine
import ua.chudo.scanner.update.ApkDownloader
import ua.chudo.scanner.update.AppMeta
import ua.chudo.scanner.update.PublishDraft
import ua.chudo.scanner.update.PublishUpdate
import ua.chudo.scanner.update.UpdateDecision
import ua.chudo.scanner.update.UpdateInfo

/** «Оновити»: завантажити (якщо ще ні) → встановити; без дозволу «з цього джерела» — підказка. */
class UpdateAction(private val app: ChudoApp) {
    var needPermission by mutableStateOf(false)

    suspend fun run(info: UpdateInfo) {
        val u = app.updates
        val f = u.ui.value.ready ?: u.download(info, unmetered = true) ?: return
        if (!u.canInstall()) { needPermission = true; return }
        runCatching { u.install(f) }.onFailure { u.failed("Не вдалося почати встановлення: ${it.message}") }
    }
}

@Composable
fun rememberUpdateAction(): UpdateAction {
    val app = LocalContext.current.chudoApp
    return remember { UpdateAction(app) }
}

/** Банер «Доступне оновлення X.Y — Оновити» над нижньою панеллю. */
@Composable
fun UpdateBanner(info: UpdateInfo, progress: Float?, error: String?, onUpdate: () -> Unit) {
    val m = Mine.colors
    Column(Modifier.fillMaxWidth().background(m.accentTone).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Доступне оновлення ${info.versionName}", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = m.text)
            TextButton(onClick = onUpdate, enabled = progress == null) { Text(if (progress != null) "Завантажую…" else "Оновити") }
        }
        progress?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth()) }
        error?.let { Text(it, fontSize = 13.sp, color = m.warningText) }
    }
}

/** Повноекранне «Потрібне оновлення»: працювати не можна, лише оновитися або вийти. */
@Composable
fun RequiredUpdateScreen(info: UpdateInfo?, message: String, progress: Float?, error: String?, onUpdate: () -> Unit, onLogout: () -> Unit) {
    val m = Mine.colors
    Column(
        Modifier.fillMaxSize().background(m.bg).systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Потрібне оновлення", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = m.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(message, fontSize = 16.sp, lineHeight = 22.sp, color = m.textSecondary, textAlign = TextAlign.Center)
        info?.let {
            Spacer(Modifier.height(8.dp))
            Text("Нова версія: ${it.versionName}", fontSize = 15.sp, color = m.text)
        }
        Spacer(Modifier.height(24.dp))
        if (progress != null) {
            LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        error?.let { Text(it, fontSize = 14.sp, color = m.warningText, textAlign = TextAlign.Center); Spacer(Modifier.height(12.dp)) }
        MinePrimaryButton(if (info == null) "Немає файлу оновлення" else "Оновити", onUpdate, enabled = info != null && progress == null)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onLogout) { Text("Вийти з акаунта") }
    }
}

/** «Встановлення з цього джерела» вимкнено — кнопка в налаштування. */
@Composable
fun InstallPermissionDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Дозвольте встановлення") },
        text = {
            Text(
                "Щоб ЧудоСкан оновлювався сам, увімкніть «Дозволити з цього джерела» для ЧудоСкан у налаштуваннях і поверніться назад.",
                fontSize = 15.sp,
            )
        },
        confirmButton = { TextButton(onClick = { onDismiss(); ctx.startActivity(app.updates.installSettingsIntent()) }) { Text("Відкрити налаштування") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Пізніше") } },
    )
}

/** «Профіль → Опублікувати оновлення» (лише superAdmin). */
@Composable
fun PublishUpdateScreen(employee: Employee, onBack: () -> Unit) {
    val app = LocalContext.current.chudoApp
    val scope = rememberCoroutineScope()
    val publisher = remember {
        PublishUpdate(FirebaseFirestore.getInstance(), app.updates, ApkDownloader(okhttp3.OkHttpClient.Builder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()), app.cacheDir, app.packageName)
    }
    var link by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var required by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<PublishDraft?>(null) }
    var meta by remember { mutableStateOf<AppMeta?>(null) }
    val m = Mine.colors

    LaunchedEffect(Unit) { meta = runCatching { ua.chudo.scanner.update.AppMetaStore(FirebaseFirestore.getInstance()).read() }.getOrNull() }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MineHeader("Опублікувати оновлення", onBack)
        MineCard {
            Text(
                meta?.let { "Опубліковано: ${it.versionName.ifBlank { "—" }} (${it.versionCode}) · мінімальна ${it.minVersionCode} · джерело: ${if (it.source == AppMeta.GITHUB) "GitHub" else "Google Диск"}" }
                    ?: "Ще нічого не опубліковано — телефони беруть оновлення з GitHub.",
                fontSize = 14.sp, color = m.textSecondary,
            )
        }
        Text("1. Завантажте новий APK на Google Диск через «Керування версіями → Завантажити нову версію» (посилання не зміниться). 2. Доступ — «Усі, хто має посилання». 3. Вставте посилання сюди.", fontSize = 14.sp, lineHeight = 20.sp, color = m.textSecondary)
        OutlinedTextField(link, { link = it; error = null; done = null }, Modifier.fillMaxWidth(), label = { Text("Посилання на APK з Google Диска") }, singleLine = true)
        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Що нового (необов'язково)") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(required, { required = it })
            Text("Обов'язкове — старі версії не працюватимуть", fontSize = 15.sp)
        }
        error?.let { Text(it, fontSize = 15.sp, color = m.warningText) }
        done?.let { Text(it, fontSize = 15.sp, color = m.success) }
        MinePrimaryButton("Перевірити APK", {
            busy = true; error = null
            scope.launch {
                runCatching { publisher.prepare(link) }.onSuccess { draft = it }.onFailure { error = it.message }
                busy = false
            }
        }, enabled = link.isNotBlank(), busy = busy)
        MineButton(
            if (meta?.source == AppMeta.GITHUB) "Джерело: GitHub → перемкнути на Google Диск" else "Джерело: Google Диск → перемкнути на GitHub",
            {
                scope.launch {
                    val to = if (meta?.source == AppMeta.GITHUB) AppMeta.DRIVE else AppMeta.GITHUB
                    runCatching { publisher.setSource(to) }.onSuccess { meta = (meta ?: AppMeta()).copy(source = to) }.onFailure { error = it.message }
                }
            },
            Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }

    draft?.let { d ->
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text("Опублікувати ${d.facts.versionName}?") },
            text = {
                Text(
                    "Версія ${d.facts.versionName} (${d.facts.versionCode}), підпис збігається." +
                        (if (required) "\nОбов'язкове: старші версії буде заблоковано." else "") +
                        "\nТелефони отримають оновлення протягом 6 годин.",
                    fontSize = 15.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    draft = null; busy = true
                    scope.launch {
                        runCatching { publisher.publish(d, required, notes, employee.uid, employee.name) }
                            .onSuccess { done = "Опубліковано ${d.facts.versionName}."; meta = runCatching { ua.chudo.scanner.update.AppMetaStore(FirebaseFirestore.getInstance()).read() }.getOrNull() }
                            .onFailure { error = it.message }
                        busy = false
                    }
                }) { Text("Опублікувати") }
            },
            dismissButton = { TextButton(onClick = { draft = null }) { Text("Скасувати") } },
        )
    }
}
