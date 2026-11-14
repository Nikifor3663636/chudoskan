package ua.chudo.shared.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import ua.chudo.shared.auth.Employee
import ua.chudo.shared.data.LookupResult
import ua.chudo.shared.layout.Box
import ua.chudo.shared.layout.BrandMatcher
import ua.chudo.shared.layout.MapCamera
import ua.chudo.shared.layout.MapLabel
import ua.chudo.shared.layout.ShopLayout
import ua.chudo.shared.res.Res
import kotlin.math.max
import kotlin.math.roundToInt

private val ORANGE = Color(0xFFFF8A00)
private const val PIN_BOX = 0.01f

/** Место на карте: подпись или уточнение сотрудника (из Android-версии). */
private data class Place(val text: String, val box: Box)

/** Пин из Firestore: товар [key] лежит в точке [x],[y]. */
private data class Pin(val key: String, val x: Float, val y: Float, val label: String?)

private class LoadedLayout(val layout: ShopLayout, val image: ImageBitmap, val matcher: BrandMatcher)

@OptIn(ExperimentalResourceApi::class)
private suspend fun loadLayout(shopId: String): LoadedLayout? {
    if (shopId.isEmpty()) return null
    val json = runCatching { Res.readBytes("files/layout/$shopId/layout.json").decodeToString() }.getOrNull() ?: return null
    val o = Json.parseToJsonElement(json).jsonObject
    fun box(o: JsonObject) = o["box"]!!.jsonArray.map { it.jsonPrimitive.float }.let { Box(it[0], it[1], it[2], it[3]) }
    val size = o["imageSize"]!!.jsonArray
    val layout = ShopLayout(
        shopId = shopId,
        width = size[0].jsonPrimitive.int,
        height = size[1].jsonPrimitive.int,
        labels = o["labels"]?.jsonArray.orEmpty().map { MapLabel(it.jsonObject["text"]!!.jsonPrimitive.content, box(it.jsonObject)) },
        sections = o["sections"]?.jsonArray.orEmpty().map { box(it.jsonObject) },
    )
    val image = Res.readBytes("files/layout/$shopId/map.webp").decodeToImageBitmap()
    val synonyms = runCatching {
        Json.parseToJsonElement(Res.readBytes("files/layout/synonyms.json").decodeToString()).jsonObject
            .mapValues { it.value.jsonPrimitive.content }
    }.getOrDefault(emptyMap())
    return LoadedLayout(layout, image, BrandMatcher(synonyms))
}

/** «Де лежить товар»: план магазина сотрудника, поиск по бренду, названию или штрихкоду. */
@Composable
fun LayoutScreen(employee: Employee, nav: Navigator) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<LoadedLayout?>(null) }
    var loading by remember { mutableStateOf(true) }
    var pins by remember { mutableStateOf(emptyList<Pin>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var title by remember { mutableStateOf<String?>(null) }
    var places by remember { mutableStateOf(emptyList<Place>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var focus by remember { mutableStateOf<Place?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(employee.shopId) {
        loaded = runCatching { loadLayout(employee.shopId) }.getOrNull()
        loading = false
        // Уточнения сотрудников; без сети план всё равно работает.
        pins = runCatching {
            graph.auth.listDocuments("shops/${employee.shopId}/layoutPins").mapNotNull { f ->
                fun s(k: String) = f[k]?.jsonObject?.get("stringValue")?.jsonPrimitive?.contentOrNull
                fun d(k: String) = f[k]?.jsonObject?.let { it["doubleValue"] ?: it["integerValue"] }?.jsonPrimitive?.doubleOrNull?.toFloat()
                Pin(s("key") ?: return@mapNotNull null, d("x") ?: return@mapNotNull null, d("y") ?: return@mapNotNull null, s("label"))
            }
        }.getOrDefault(emptyList())
    }

    fun search(raw: String) {
        val q = raw.trim().ifEmpty { return }
        val l = loaded ?: return
        scope.launch {
            busy = true
            message = null
            try {
                val digits = q.filter(Char::isDigit)
                val barcode = digits.takeIf { it.length >= 8 && it.length == q.length }
                val labels = l.layout.labels
                val direct = if (barcode == null) labels.indices.filter { l.matcher.same(q, labels[it].text) } else emptyList()
                val name: String?
                val auto: List<Int>
                if (direct.isNotEmpty()) {
                    name = q
                    auto = direct
                } else {
                    name = when (val r = graph.repo.lookup(q)) {
                        is LookupResult.Single -> graph.repo.details(r.urlKey).name
                        is LookupResult.Many -> r.items.firstOrNull()?.name
                        is LookupResult.NotFound -> null
                    }
                    auto = l.matcher.find(labels, BrandMatcher.brandOf(name), name ?: q)
                }
                val brand = if (direct.isNotEmpty()) q else BrandMatcher.brandOf(name)
                val keys = setOfNotNull(barcode, brand?.let(BrandMatcher::norm))
                val mine = pins.filter { it.key in keys }
                title = name ?: q
                places = if (mine.isNotEmpty()) {
                    mine.map { Place(it.label ?: "Вказано вручну", Box(it.x - PIN_BOX / 2, it.y - PIN_BOX / 2, PIN_BOX, PIN_BOX)) }
                } else {
                    auto.map { Place(labels[it].text, labels[it].box) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.uiMessage()
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(nav.layoutCode) {
        nav.layoutCode?.let { query = it; nav.layoutCode = null; search(it) }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        val l = loaded
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            l == null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("План магазину ще не додано", style = MaterialTheme.typography.titleMedium)
                Text("Коли план твого магазину з'явиться, тут можна буде знайти, де лежить товар.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> ShopMap(l, places, focus) { focus = null }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f), singleLine = true,
                    shape = RoundedCornerShape(16.dp), enabled = l != null,
                    placeholder = { Text("Де лежить: бренд або штрихкод") },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            if (query.isNotEmpty()) IconButton(onClick = { query = ""; title = null; places = emptyList() }) { Icon(Icons.Rounded.Close, "Очистити") }
                            IconButton(onClick = { nav.go(Screen.ScanLayout) }) { Icon(Icons.Rounded.QrCodeScanner, "Сканувати") }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); search(query) }),
                )
            }
            message?.let { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp)) } }
        }
        title?.let { t ->
            Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (places.isEmpty()) {
                        Text("Місце не знайдено", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("Знайдено місць: ${places.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(Modifier.heightIn(max = 160.dp)) {
                            items(places) { p -> Text("• ${p.text}", Modifier.fillMaxWidth().clickable { focus = p }.padding(vertical = 8.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShopMap(l: LoadedLayout, places: List<Place>, focus: Place?, onFocused: () -> Unit) {
    var cam by remember { mutableStateOf<MapCamera?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val image = l.image
    LaunchedEffect(size) {
        if (size.width > 0) cam = MapCamera(image.width.toFloat(), image.height.toFloat(), size.width.toFloat(), size.height.toFloat()).clamped()
    }
    LaunchedEffect(places, cam != null) {
        val c = cam ?: return@LaunchedEffect
        if (places.isNotEmpty()) cam = c.fit(places.map { it.box })
    }
    LaunchedEffect(focus) {
        val p = focus ?: return@LaunchedEffect
        cam?.let { cam = it.centerOn(p.box.cx, p.box.cy, max(it.zoom, 3f)) }
        onFocused()
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "pulse",
    )
    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    cam = cam?.let { it.zoomAt(centroid.x, centroid.y, it.zoom * zoom).pan(pan.x, pan.y) }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { o ->
                    cam = cam?.let { it.zoomAt(o.x, o.y, if (it.zoom >= MapCamera.MAX_ZOOM - 0.01f) 1f else it.zoom * 2f) }
                })
            },
    ) {
        val c = cam ?: return@Canvas
        drawImage(
            image,
            dstOffset = IntOffset(c.tx.roundToInt(), c.ty.roundToInt()),
            dstSize = IntSize(c.contentW.roundToInt(), c.contentH.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        val r = 8.dp.toPx()
        places.forEach { p ->
            val (sx, sy) = c.toScreen(p.box.cx, p.box.cy)
            val center = Offset(sx, sy)
            drawCircle(ORANGE.copy(alpha = 0.45f * (1f - pulse)), radius = r + 22.dp.toPx() * pulse, center = center)
            drawCircle(Color.White, radius = r + 2.dp.toPx(), center = center)
            drawCircle(ORANGE, radius = r, center = center)
        }
    }
}
